package jp.hirameq.handycam.match

import jp.hirameq.handycam.imaging.CanonicalImage
import jp.hirameq.handycam.imaging.Canonicalizer
import jp.hirameq.handycam.imaging.Preprocess
import jp.hirameq.handycam.model.AppSettings
import jp.hirameq.handycam.model.MethodId
import jp.hirameq.handycam.model.MethodScore
import jp.hirameq.handycam.model.Roi
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.core.TermCriteria
import org.opencv.imgproc.Imgproc
import org.opencv.video.Video

/**
 * 整列後の勾配相関。
 * 1. 低解像度で ECC(Enhanced Correlation Coefficient)によるアフィン整列を試み、canonical 化の残差(数px のずれ・僅かな回転)を補正。
 * 2. CLAHE → 勾配強度 画像同士の、共通マスク内 Pearson 相関を取る。輝度ではなく勾配なので照明差に強い。
 * 3. ROI があれば ROI 内相関を重み付きで合成(押印領域の違いを強調)。
 */
class NccMatcher : Matcher {
    override val id = MethodId.NCC
    override val orientationSensitive = true
    override val cost = 2

    /** CLAHE → 軽いぼかし(表面の微細テクスチャを落とす) → 勾配強度。マスクは境界帯を除外して輪郭エッジの支配を防ぐ。 */
    private fun grad(img: CanonicalImage): Mat = img.cached("grad_clahe") {
        val c = Preprocess.clahe(img.gray())
        Imgproc.GaussianBlur(c, c, Size(0.0, 0.0), 1.5)
        val g = Preprocess.gradientMagnitude(c, innerMask(img))
        c.release(); g
    }

    private fun innerMask(img: CanonicalImage): Mat = img.cached("inner_mask") { Preprocess.erode(img.mask, img.size / 32) }

    /** query → template のアフィン整列行列(2x3)。失敗時は単位行列。 */
    fun align(query: CanonicalImage, template: CanonicalImage): Mat {
        val warp = Mat.eye(2, 3, CvType.CV_32F)
        try {
            val s = 128.0 / query.size
            val q = Mat(); val t = Mat()
            Imgproc.resize(query.gray(), q, Size(), s, s, Imgproc.INTER_AREA)
            Imgproc.resize(template.gray(), t, Size(), s, s, Imgproc.INTER_AREA)
            val qm = Mat()
            Imgproc.resize(query.mask, qm, Size(), s, s, Imgproc.INTER_NEAREST)
            Imgproc.GaussianBlur(q, q, Size(5.0, 5.0), 0.0)
            Imgproc.GaussianBlur(t, t, Size(5.0, 5.0), 0.0)
            val crit = TermCriteria(TermCriteria.COUNT or TermCriteria.EPS, 40, 1e-4)
            Video.findTransformECC(t, q, warp, Video.MOTION_AFFINE, crit, qm, 5)
            // 低解像度で推定した平行移動成分をフル解像度へ
            warp.put(0, 2, warp.get(0, 2)[0] / s)
            warp.put(1, 2, warp.get(1, 2)[0] / s)
            q.release(); t.release(); qm.release()
            // 妥当性: スケール 0.7..1.4
            val a = warp.get(0, 0)[0]; val b = warp.get(0, 1)[0]; val c = warp.get(1, 0)[0]; val d = warp.get(1, 1)[0]
            val det = a * d - b * c
            if (det < 0.5 || det > 2.0) return Mat.eye(2, 3, CvType.CV_32F)
        } catch (e: Exception) {
            return Mat.eye(2, 3, CvType.CV_32F)
        }
        return warp
    }

    override fun compare(query: CanonicalImage, template: CanonicalImage, rois: List<Roi>, settings: AppSettings): MethodScore {
        val warp = align(query, template)
        val gq = grad(query); val gt = grad(template)
        val n = query.size
        val aligned = Mat(); val alignedMask = Mat()
        // warp は template→query 方向(ECC の慣例)なので逆変換で query を template 座標へ
        Imgproc.warpAffine(gq, aligned, warp, Size(n.toDouble(), n.toDouble()), Imgproc.INTER_LINEAR or Imgproc.WARP_INVERSE_MAP)
        Imgproc.warpAffine(innerMask(query), alignedMask, warp, Size(n.toDouble(), n.toDouble()), Imgproc.INTER_NEAREST or Imgproc.WARP_INVERSE_MAP)
        warp.release()
        val tMask = innerMask(template)

        val global = Canonicalizer.maskedCorrelation(aligned, gt, alignedMask, tMask)
        var score = maxOf(0.0, global)
        var detail = "r=%.3f".format(global)
        if (rois.isNotEmpty()) {
            var wsum = 0.0; var acc = 0.0
            for (r in rois) {
                val rect = RoiUtil.toRect(r, n)
                val roiMask = Mat.zeros(n, n, CvType.CV_8UC1)
                Imgproc.rectangle(roiMask, rect, org.opencv.core.Scalar(255.0), Imgproc.FILLED)
                val ma = Mat(); val mb = Mat()
                Core.bitwise_and(alignedMask, roiMask, ma); Core.bitwise_and(tMask, roiMask, mb)
                val rr = Canonicalizer.maskedCorrelation(aligned, gt, ma, mb)
                roiMask.release(); ma.release(); mb.release()
                acc += r.weight * maxOf(0.0, rr); wsum += r.weight
            }
            val roiScore = if (wsum > 0) acc / wsum else score
            score = 0.4 * score + 0.6 * roiScore
            detail += " roi=%.3f".format(roiScore)
        }
        aligned.release(); alignedMask.release()
        return MethodScore(id, score.toFloat().coerceIn(0f, 1f), detail)
    }
}
