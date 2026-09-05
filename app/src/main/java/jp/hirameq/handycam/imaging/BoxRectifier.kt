package jp.hirameq.handycam.imaging

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * グレー箱の「一番上の箱の内側矩形」を検出し、射影変換で正面視に補正する。
 *
 * 箱が何段か積まれていたり隣に別の箱があっても、解析対象を一番上の箱の内側に限定できる。
 * 検出方法: 「エッジに囲まれた、画像の縁に触れない最大の低勾配領域」を連結成分で探す。
 * 縁の輪郭を 4 点近似する方式より、隣の箱の線が縁に接している状況に強い(pctools/boxsim.py で検証)。
 *
 * 箱が画面からはみ出している(領域が画像の縁に触れる)場合は検出しない → 呼び出し側でフォールバック。
 */
object BoxRectifier {

    /** 検出した内側矩形。tl, tr, br, bl の順。 */
    class Quad(val pts: Array<Point>) {
        fun toMatOfPoint(): MatOfPoint = MatOfPoint(*pts)
        fun width(): Double = max(dist(pts[0], pts[1]), dist(pts[3], pts[2]))
        fun height(): Double = max(dist(pts[0], pts[3]), dist(pts[1], pts[2]))
        fun offset(dx: Double, dy: Double) = Quad(Array(4) { Point(pts[it].x + dx, pts[it].y + dy) })
        companion object { fun dist(a: Point, b: Point) = hypot(a.x - b.x, a.y - b.y) }
    }

    fun detect(bgr: Mat, minAreaRatio: Double = 0.15, borderPx: Int = 6, gradThreshold: Double = 60.0): Quad? {
        val h = bgr.rows(); val w = bgr.cols()
        val gray = Preprocess.toGray(bgr)
        Imgproc.GaussianBlur(gray, gray, Size(5.0, 5.0), 0.0)
        val gx = Mat(); val gy = Mat(); val mag = Mat()
        Imgproc.Sobel(gray, gx, CvType.CV_32F, 1, 0)
        Imgproc.Sobel(gray, gy, CvType.CV_32F, 0, 1)
        Core.magnitude(gx, gy, mag)
        gx.release(); gy.release(); gray.release()
        val edge = Mat()
        Imgproc.threshold(mag, edge, gradThreshold, 255.0, Imgproc.THRESH_BINARY)
        mag.release()
        edge.convertTo(edge, CvType.CV_8U)
        Imgproc.dilate(edge, edge, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(7.0, 7.0)))
        val free = Mat()
        Core.bitwise_not(edge, free)
        edge.release()

        val labels = Mat(); val stats = Mat(); val centroids = Mat()
        val n = Imgproc.connectedComponentsWithStats(free, labels, stats, centroids, 4, CvType.CV_32S)
        free.release(); centroids.release()
        var bestIdx = -1; var bestArea = 0
        val minArea = (minAreaRatio * h * w).toInt()
        for (i in 1 until n) {
            val x = stats.get(i, Imgproc.CC_STAT_LEFT)[0].toInt()
            val y = stats.get(i, Imgproc.CC_STAT_TOP)[0].toInt()
            val cw = stats.get(i, Imgproc.CC_STAT_WIDTH)[0].toInt()
            val ch = stats.get(i, Imgproc.CC_STAT_HEIGHT)[0].toInt()
            val a = stats.get(i, Imgproc.CC_STAT_AREA)[0].toInt()
            if (a < minArea) continue
            if (x <= borderPx || y <= borderPx || x + cw >= w - borderPx || y + ch >= h - borderPx) continue
            if (a > bestArea) { bestArea = a; bestIdx = i }
        }
        stats.release()
        if (bestIdx < 0) { labels.release(); return null }

        val comp = Mat()
        Core.compare(labels, Scalar(bestIdx.toDouble()), comp, Core.CMP_EQ)
        labels.release()
        val contours = ArrayList<MatOfPoint>()
        Imgproc.findContours(comp, contours, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        comp.release()
        val c = contours.maxByOrNull { Imgproc.contourArea(it) } ?: return null
        val hullIdx = MatOfInt()
        Imgproc.convexHull(c, hullIdx)
        val cPts = c.toArray()
        val hull = MatOfPoint2f(*hullIdx.toArray().map { cPts[it] }.toTypedArray())
        contours.forEach { it.release() }; hullIdx.release()
        val peri = Imgproc.arcLength(hull, true)
        val approx = MatOfPoint2f()
        Imgproc.approxPolyDP(hull, approx, 0.03 * peri, true)
        val quadPts: Array<Point> = if (approx.rows() == 4) approx.toArray() else {
            val rr = Imgproc.minAreaRect(hull); val p = arrayOfNulls<Point>(4); rr.points(p); p.requireNoNulls()
        }
        hull.release(); approx.release()
        val q = order(quadPts)
        // 妥当性: 対辺の長さ比
        val w1 = Quad.dist(q[0], q[1]); val w2 = Quad.dist(q[3], q[2]); val h1 = Quad.dist(q[0], q[3]); val h2 = Quad.dist(q[1], q[2])
        if (min(w1, w2) / max(w1, w2) < 0.5 || min(h1, h2) / max(h1, h2) < 0.5) return null
        // エッジ膨張分だけ外側へ広げる
        val cx = q.sumOf { it.x } / 4; val cy = q.sumOf { it.y } / 4
        val grown = Array(4) { i ->
            val dx = q[i].x - cx; val dy = q[i].y - cy; val d = max(hypot(dx, dy), 1e-6)
            Point(q[i].x + dx / d * 4, q[i].y + dy / d * 4)
        }
        return Quad(grown)
    }

    /** tl, tr, br, bl に並べ替える。 */
    private fun order(p: Array<Point>): Array<Point> {
        val tl = p.minByOrNull { it.x + it.y }!!
        val br = p.maxByOrNull { it.x + it.y }!!
        val tr = p.minByOrNull { it.y - it.x }!!
        val bl = p.maxByOrNull { it.y - it.x }!!
        return arrayOf(tl, tr, br, bl)
    }

    /**
     * 内側矩形を正面視に補正して切り出す。shrink は縁の残りを落とすための内側マージン(辺の比)。
     * 出力サイズは矩形の実寸(px)に合わせる → 距離による見え方の差はここで一部吸収される。
     */
    fun rectify(bgr: Mat, quad: Quad, shrink: Double = 0.05): Mat {
        val w = quad.width().toInt().coerceAtLeast(32)
        val h = quad.height().toInt().coerceAtLeast(32)
        val src = MatOfPoint2f(*quad.pts)
        val dst = MatOfPoint2f(Point(0.0, 0.0), Point(w.toDouble(), 0.0), Point(w.toDouble(), h.toDouble()), Point(0.0, h.toDouble()))
        val hm = Imgproc.getPerspectiveTransform(src, dst)
        val out = Mat()
        Imgproc.warpPerspective(bgr, out, hm, Size(w.toDouble(), h.toDouble()), Imgproc.INTER_LINEAR)
        src.release(); dst.release(); hm.release()
        val sx = (w * shrink).toInt(); val sy = (h * shrink).toInt()
        val roi = Rect(sx, sy, w - 2 * sx, h - 2 * sy)
        val cropped = Mat(out, roi).clone()
        out.release()
        return cropped
    }

    /** ガイド枠(中央の ratio 倍の矩形)。 */
    fun guideRect(w: Int, h: Int, ratio: Float): Rect {
        val r = ratio.toDouble().coerceIn(0.3, 1.0)
        val gw = (w * r).toInt(); val gh = (h * r).toInt()
        return Rect((w - gw) / 2, (h - gh) / 2, gw, gh)
    }
}
