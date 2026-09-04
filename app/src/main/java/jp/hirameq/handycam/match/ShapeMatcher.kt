package jp.hirameq.handycam.match

import jp.hirameq.handycam.imaging.CanonicalImage
import jp.hirameq.handycam.model.AppSettings
import jp.hirameq.handycam.model.MethodId
import jp.hirameq.handycam.model.MethodScore
import jp.hirameq.handycam.model.Roi
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.exp

/**
 * 形状照合: 外形輪郭の Hu モーメント距離 + アスペクト比 + ソリディティ(凸包充填率)。
 * 回転/スケール/鏡像に不変。安価なので全候補に対するゲート/事前絞り込みに使う。
 * 押印の違いは見えないので単独では製品の識別に使わないこと。
 */
class ShapeMatcher : Matcher {
    override val id = MethodId.SHAPE
    override val orientationSensitive = false
    override val cost = 1

    data class ShapeDesc(val contour: MatOfPoint, val aspect: Double, val solidity: Double, val extent: Double)

    private fun desc(img: CanonicalImage): ShapeDesc = img.cached("shape") {
        val contours = ArrayList<MatOfPoint>()
        Imgproc.findContours(img.mask.clone(), contours, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_NONE)
        val c = contours.maxByOrNull { Imgproc.contourArea(it) } ?: MatOfPoint()
        val area = Imgproc.contourArea(c)
        val rr = if (c.rows() >= 5) Imgproc.minAreaRect(org.opencv.core.MatOfPoint2f(*c.toArray())) else null
        val w = rr?.size?.width ?: 1.0; val h = rr?.size?.height ?: 1.0
        val aspect = maxOf(w, h) / maxOf(minOf(w, h), 1.0)
        val hull = org.opencv.core.MatOfInt()
        var solidity = 1.0
        if (c.rows() >= 3) {
            Imgproc.convexHull(c, hull)
            val hullPts = hull.toArray().map { c.toArray()[it] }
            val hullArea = Imgproc.contourArea(MatOfPoint(*hullPts.toTypedArray()))
            if (hullArea > 0) solidity = area / hullArea
        }
        val extent = if (rr != null && w * h > 0) area / (w * h) else 1.0
        ShapeDesc(c, aspect, solidity, extent)
    }

    override fun compare(query: CanonicalImage, template: CanonicalImage, rois: List<Roi>, settings: AppSettings): MethodScore {
        val q = desc(query); val t = desc(template)
        if (q.contour.rows() < 5 || t.contour.rows() < 5) return MethodScore(id, 0f, "contour too small", unavailable = true)
        val d = Imgproc.matchShapes(q.contour, t.contour, Imgproc.CONTOURS_MATCH_I1, 0.0)
        val sHu = exp(-4.0 * d)
        val sAr = 1.0 - minOf(1.0, abs(q.aspect - t.aspect) / maxOf(q.aspect, t.aspect))
        val sSol = 1.0 - minOf(1.0, abs(q.solidity - t.solidity) * 2.0)
        val sExt = 1.0 - minOf(1.0, abs(q.extent - t.extent) * 2.0)
        val score = 0.45 * sHu + 0.25 * sAr + 0.15 * sSol + 0.15 * sExt
        return MethodScore(id, score.toFloat().coerceIn(0f, 1f),
            "hu=%.3f ar=%.2f/%.2f sol=%.2f/%.2f".format(d, q.aspect, t.aspect, q.solidity, t.solidity))
    }
}
