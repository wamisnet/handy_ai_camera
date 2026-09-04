package jp.hirameq.handycam.match

import jp.hirameq.handycam.imaging.CanonicalImage
import jp.hirameq.handycam.imaging.Preprocess
import jp.hirameq.handycam.model.AppSettings
import jp.hirameq.handycam.model.MethodId
import jp.hirameq.handycam.model.MethodScore
import jp.hirameq.handycam.model.Roi
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.exp

/**
 * 押印エッジの Chamfer 照合。
 * 高域強調(gray − ぼかし)で押印の陰影だけを取り出し、Canny でエッジ化、
 * 相手側エッジまでの距離変換(Distance Transform)を使って「エッジがどれだけ相手のエッジ近傍に乗るか」を測る。
 * 数 px の位置ずれ・太さの違いに寛容で、文字の有無・形の違いには敏感。
 * 小さな平行移動探索(±shift)で canonical 化の残差を吸収する。ROI があれば ROI 内を重視。
 */
class EmbossMatcher : Matcher {
    override val id = MethodId.EMBOSS
    override val orientationSensitive = true
    override val cost = 2

    companion object {
        const val TAU = 3.0        // 距離減衰スケール(px)
        const val SHIFT = 6        // 平行移動探索範囲(px)
        const val STEP = 3
    }

    class EdgeData(val edges: Mat, val dt: Mat)

    private fun edges(img: CanonicalImage): EdgeData = img.cached("emboss") {
        val hp = Preprocess.highpass(img.gray(), sigma = img.size / 48.0)
        // 外形輪郭のエッジがスコアを支配しないよう、境界帯(サイズの 1/24)を除外
        val inner = Preprocess.erode(img.mask, img.size / 24)
        val masked = Preprocess.applyMask(hp, inner)
        Imgproc.GaussianBlur(masked, masked, Size(3.0, 3.0), 0.0)
        val e = Mat()
        Imgproc.Canny(masked, e, 40.0, 110.0)
        Core.bitwise_and(e, inner, e)
        hp.release(); masked.release(); inner.release()
        // 距離変換: エッジ=0 の画像に対して
        val inv = Mat()
        Core.bitwise_not(e, inv)
        val dt = Mat()
        Imgproc.distanceTransform(inv, dt, Imgproc.DIST_L2, 3)
        inv.release()
        EdgeData(e, dt)
    }

    /** a のエッジ画素が b の距離変換上でどれだけ近いか: mean(exp(-d/τ))。 */
    private fun directed(aEdges: Mat, bDt: Mat, dx: Int, dy: Int, roi: Rect?): Double {
        val n = aEdges.rows()
        val r = roi ?: Rect(0, 0, n, n)
        // a を (dx,dy) シフトして比較 → 共通領域を切り出す
        val x0 = maxOf(r.x, -dx); val y0 = maxOf(r.y, -dy)
        val x1 = minOf(r.x + r.width, n - dx); val y1 = minOf(r.y + r.height, n - dy)
        if (x1 - x0 < 4 || y1 - y0 < 4) return 0.0
        val aSub = aEdges.submat(y0, y1, x0, x1)
        val bSub = bDt.submat(y0 + dy, y1 + dy, x0 + dx, x1 + dx)
        val cnt = Core.countNonZero(aSub)
        if (cnt < 20) { aSub.release(); bSub.release(); return -1.0 }
        val w = Mat()
        // exp(-d/τ) を計算: exp(-d/τ) = exp(x) with x = -d/τ
        Core.multiply(bSub, Scalar(-1.0 / TAU), w)
        Core.exp(w, w)
        val masked = Mat.zeros(w.size(), CvType.CV_32F)
        w.copyTo(masked, aSub)
        val s = Core.sumElems(masked).`val`[0] / cnt
        w.release(); masked.release(); aSub.release(); bSub.release()
        return s
    }

    private fun symmetric(q: EdgeData, t: EdgeData, roi: Rect?): Double {
        var best = -1.0
        for (dy in -SHIFT..SHIFT step STEP) for (dx in -SHIFT..SHIFT step STEP) {
            val f = directed(q.edges, t.dt, dx, dy, roi)
            if (f < 0) continue
            val b = directed(t.edges, q.dt, -dx, -dy, roi)
            if (b < 0) continue
            val s = 2 * f * b / maxOf(f + b, 1e-6)   // 調和平均: 片方だけ良い(部分一致)を抑える
            if (s > best) best = s
        }
        return best
    }

    override fun compare(query: CanonicalImage, template: CanonicalImage, rois: List<Roi>, settings: AppSettings): MethodScore {
        val q = edges(query); val t = edges(template)
        val nq = Core.countNonZero(q.edges); val nt = Core.countNonZero(t.edges)
        if (nq < 30 || nt < 30) return MethodScore(id, 0f, "edges q=$nq t=$nt", unavailable = true)
        val global = symmetric(q, t, null)
        var score = maxOf(0.0, global)
        var detail = "chamfer=%.3f e=%d/%d".format(global, nq, nt)
        if (rois.isNotEmpty()) {
            var acc = 0.0; var wsum = 0.0
            for (r in rois) {
                val s = symmetric(q, t, RoiUtil.toRect(r, query.size))
                if (s < 0) continue
                acc += r.weight * s; wsum += r.weight
            }
            if (wsum > 0) {
                val roiScore = acc / wsum
                score = 0.35 * score + 0.65 * roiScore
                detail += " roi=%.3f".format(roiScore)
            }
        }
        return MethodScore(id, score.toFloat().coerceIn(0f, 1f), detail)
    }
}
