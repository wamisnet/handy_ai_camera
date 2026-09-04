package jp.hirameq.handycam.match

import jp.hirameq.handycam.imaging.CanonicalImage
import jp.hirameq.handycam.imaging.Preprocess
import jp.hirameq.handycam.model.AppSettings
import jp.hirameq.handycam.model.FeatureDetectorKind
import jp.hirameq.handycam.model.MethodId
import jp.hirameq.handycam.model.MethodScore
import jp.hirameq.handycam.model.Roi
import org.opencv.calib3d.Calib3d
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfDMatch
import org.opencv.core.MatOfKeyPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.features2d.AKAZE
import org.opencv.features2d.BFMatcher
import org.opencv.features2d.Feature2D
import org.opencv.features2d.ORB
import org.opencv.features2d.SIFT
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 局所特徴照合: ORB/AKAZE/SIFT でキーポイントを検出し、比率テスト → RANSAC ホモグラフィで
 * 幾何的に整合するインライアの割合をスコアとする。
 *
 * - 押印文字はコーナー特徴を多く持つので、文字の違いに敏感。
 * - 面内回転/スケール差に不変(canonical 化の誤差やパースの傾きも RANSAC が吸収)。
 * - 推定されたホモグラフィの妥当性(スケール・パース成分)を検査し、偶然の一致を弾く。
 * - ROI 内のキーポイントには重みを付け、ユーザ指示領域を重視する。
 */
class FeatureMatcher : Matcher {
    override val id = MethodId.FEATURE
    override val orientationSensitive = false
    override val cost = 2

    class Feats(val kps: MatOfKeyPoint, val desc: Mat, val kind: FeatureDetectorKind)

    private fun detector(kind: FeatureDetectorKind): Feature2D = when (kind) {
        FeatureDetectorKind.ORB -> ORB.create(1200, 1.2f, 8, 15, 0, 2, ORB.HARRIS_SCORE, 31, 10)
        FeatureDetectorKind.AKAZE -> AKAZE.create()
        FeatureDetectorKind.SIFT -> SIFT.create(800)
    }

    private fun feats(img: CanonicalImage, kind: FeatureDetectorKind): Feats = img.cached("feat_$kind") {
        val gray = Preprocess.clahe(img.gray())
        val mask = Preprocess.erode(img.mask, 3)   // 境界のアーティファクトを避ける
        val kps = MatOfKeyPoint(); val desc = Mat()
        detector(kind).detectAndCompute(gray, mask, kps, desc)
        gray.release(); mask.release()
        Feats(kps, desc, kind)
    }

    override fun compare(query: CanonicalImage, template: CanonicalImage, rois: List<Roi>, settings: AppSettings): MethodScore {
        val kind = settings.featureDetector
        val q = feats(query, kind); val t = feats(template, kind)
        val nq = q.kps.rows(); val nt = t.kps.rows()
        if (nq < 8 || nt < 8) return MethodScore(id, 0f, "kp q=$nq t=$nt", unavailable = true)

        val norm = if (kind == FeatureDetectorKind.SIFT) Core.NORM_L2 else Core.NORM_HAMMING
        val matcher = BFMatcher.create(norm, false)
        val knn = ArrayList<MatOfDMatch>()
        matcher.knnMatch(q.desc, t.desc, knn, 2)
        val good = knn.mapNotNull { m ->
            val arr = m.toArray()
            if (arr.size >= 2 && arr[0].distance < 0.8f * arr[1].distance) arr[0] else if (arr.size == 1) arr[0] else null
        }
        if (good.size < 8) return MethodScore(id, 0f, "good=${good.size} kp q=$nq t=$nt")

        val qk = q.kps.toArray(); val tk = t.kps.toArray()
        val src = MatOfPoint2f(*good.map { qk[it.queryIdx].pt }.toTypedArray())
        val dst = MatOfPoint2f(*good.map { tk[it.trainIdx].pt }.toTypedArray())
        val inlierMask = Mat()
        val hmg = Calib3d.findHomography(src, dst, Calib3d.RANSAC, 5.0, inlierMask, 2000, 0.995)
        src.release(); dst.release()
        if (hmg.empty()) { inlierMask.release(); return MethodScore(id, 0f, "no homography good=${good.size}") }

        // ホモグラフィ妥当性: スケール 0.5..2, 回転任意, パース成分小, 反転なし
        val h = DoubleArray(9) { hmg.get(it / 3, it % 3)[0] }
        hmg.release()
        val det = h[0] * h[4] - h[1] * h[3]
        val scale = sqrt(abs(det))
        val persp = maxOf(abs(h[6]), abs(h[7]))
        val geomOk = det > 0 && scale in 0.5..2.0 && persp < 0.004
        val inl = ByteArray(inlierMask.rows() * inlierMask.cols()).also { if (it.isNotEmpty()) inlierMask.get(0, 0, it) }
        inlierMask.release()
        if (!geomOk) return MethodScore(id, 0f, "bad H scale=%.2f persp=%.4f det=%.2f".format(scale, persp, det))

        // ROI 重み付きインライア率(テンプレート側キーポイントを母数とする)
        val canvas = template.size
        var wInl = 0.0
        var nInl = 0
        for (i in good.indices) if (inl.getOrNull(i)?.toInt() == 1) {
            val p: Point = tk[good[i].trainIdx].pt
            wInl += RoiUtil.weightAt(p.x, p.y, rois, canvas); nInl++
        }
        var wAll = 0.0
        for (k in tk) wAll += RoiUtil.weightAt(k.pt.x, k.pt.y, rois, canvas)
        val denom = maxOf(minOf(wAll, sumWeights(qk, rois, canvas)), 1.0)
        val ratio = wInl / denom
        // インライア絶対数が少ないときは信頼度を落とす(偶然一致対策)
        val conf = minOf(1.0, nInl / 25.0)
        val score = (ratio * (0.5 + 0.5 * conf)).coerceIn(0.0, 1.0)
        return MethodScore(id, score.toFloat(), "inl=$nInl/${good.size} kp q=$nq t=$nt s=%.2f".format(scale))
    }

    private fun sumWeights(kps: Array<org.opencv.core.KeyPoint>, rois: List<Roi>, canvas: Int): Double {
        var s = 0.0
        for (k in kps) s += RoiUtil.weightAt(k.pt.x, k.pt.y, rois, canvas)
        return s
    }
}
