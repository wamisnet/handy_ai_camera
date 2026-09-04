package jp.hirameq.handycam.match

import jp.hirameq.handycam.imaging.CanonicalImage
import jp.hirameq.handycam.model.AppSettings
import jp.hirameq.handycam.model.MethodId
import jp.hirameq.handycam.model.MethodScore
import jp.hirameq.handycam.model.Roi
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Point
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * 穴パターン照合(金属部品向け)。
 * マスクの内側輪郭(=貫通穴/背景が見える領域)を抽出し、正規化座標系での穴中心と等価半径の点集合を比較する。
 * 平行移動の残差は「対応点の平均オフセット」で 1 回補正。F1 的な一致率 × 距離減衰で 0..1。
 * 穴が両方に無い場合は評価不能(unavailable)として融合から除く。
 */
class HoleMatcher : Matcher {
    override val id = MethodId.HOLES
    override val orientationSensitive = true
    override val cost = 1

    data class Hole(val c: Point, val r: Double)

    private fun holes(img: CanonicalImage): List<Hole> = img.cached("holes") {
        val contours = ArrayList<MatOfPoint>()
        val hier = Mat()
        Imgproc.findContours(img.mask.clone(), contours, hier, Imgproc.RETR_CCOMP, Imgproc.CHAIN_APPROX_SIMPLE)
        val minArea = img.size * img.size * 0.0004
        val out = ArrayList<Hole>()
        for (i in contours.indices) {
            val parent = hier.get(0, i)[3]
            if (parent < 0) continue // 外側輪郭
            val a = Imgproc.contourArea(contours[i])
            if (a < minArea) continue
            val m = Imgproc.moments(contours[i])
            if (m.m00 <= 0) continue
            out += Hole(Point(m.m10 / m.m00, m.m01 / m.m00), sqrt(a / Math.PI))
        }
        hier.release(); contours.forEach { it.release() }
        out
    }

    private fun matchSets(q: List<Hole>, t: List<Hole>, tol: Double, dx: Double, dy: Double): Pair<Int, Double> {
        val used = BooleanArray(t.size)
        var matched = 0; var acc = 0.0
        for (h in q) {
            var bi = -1; var bd = Double.MAX_VALUE
            for (j in t.indices) if (!used[j]) {
                val d = hypot(h.c.x + dx - t[j].c.x, h.c.y + dy - t[j].c.y)
                if (d < bd) { bd = d; bi = j }
            }
            if (bi >= 0 && bd <= tol && abs(h.r - t[bi].r) / maxOf(t[bi].r, 1.0) < 0.6) {
                used[bi] = true; matched++; acc += exp(-bd / (tol / 2))
            }
        }
        return matched to acc
    }

    override fun compare(query: CanonicalImage, template: CanonicalImage, rois: List<Roi>, settings: AppSettings): MethodScore {
        val q = holes(query); val t = holes(template)
        if (q.isEmpty() && t.isEmpty()) return MethodScore(id, 1f, "no holes on both", unavailable = true)
        if (q.isEmpty() || t.isEmpty()) return MethodScore(id, 0f, "holes q=${q.size} t=${t.size}")
        val tol = query.size * 0.05
        // 1 回目: 補正なし → 対応から平均オフセットを推定 → 2 回目
        var dx = 0.0; var dy = 0.0
        run {
            var n = 0; var sx = 0.0; var sy = 0.0
            for (h in q) {
                val nearest = t.minByOrNull { hypot(h.c.x - it.c.x, h.c.y - it.c.y) } ?: continue
                val d = hypot(h.c.x - nearest.c.x, h.c.y - nearest.c.y)
                if (d <= tol) { sx += nearest.c.x - h.c.x; sy += nearest.c.y - h.c.y; n++ }
            }
            if (n > 0) { dx = sx / n; dy = sy / n }
        }
        val (matched, acc) = matchSets(q, t, tol, dx, dy)
        val f1 = 2.0 * matched / (q.size + t.size)
        val quality = if (matched > 0) acc / matched else 0.0
        val score = f1 * (0.5 + 0.5 * quality)
        return MethodScore(id, score.toFloat().coerceIn(0f, 1f), "holes q=${q.size} t=${t.size} m=$matched")
    }
}
