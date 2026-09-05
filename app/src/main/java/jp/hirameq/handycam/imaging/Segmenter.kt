package jp.hirameq.handycam.imaging

import jp.hirameq.handycam.model.AppSettings
import jp.hirameq.handycam.model.BackgroundKind
import jp.hirameq.handycam.model.SegmenterKind
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.RotatedRect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/** 検出された 1 物体。 */
data class SegmentObject(
    val contour: MatOfPoint,
    val area: Double,
    val bbox: Rect,
    val rotRect: RotatedRect,
    val centroid: Point,
)

data class Segmentation(
    /** 画像全体の前景マスク(8UC1, 0/255)。 */
    val mask: Mat,
    /** 面積降順。 */
    val objects: List<SegmentObject>,
    val method: String,
) {
    fun release() { mask.release(); objects.forEach { it.contour.release() } }
}

/**
 * 背景から物体を切り出す。
 *
 * - 黒背景: 輝度 Otsu。フラッシュ有無のペアがあれば「フラッシュ差分」と AND して、
 *   遠景(黒背景よりさらに遠いもの/映り込み)を強力に除去する。
 * - グレー箱: 画像周辺から背景色(Lab)を推定し、色距離で前景抽出。
 *
 * どの手法も後段は共通: モルフォロジー → 連結成分 → 面積フィルタ。
 */
class Segmenter(private val settings: AppSettings) {

    fun segment(frame: Frame, partner: Frame?, background: BackgroundKind, expectedCount: Int): Segmentation {
        val kind = resolveKind(background, partner != null)
        val raw: Mat
        val name: String
        when (kind) {
            SegmenterKind.FLASH_DIFF -> {
                val otsu = darkBackgroundOtsu(frame.bgr)
                if (partner != null) {
                    val diff = flashDiff(frame, partner)
                    Core.bitwise_and(otsu, diff, otsu)
                    diff.release()
                    name = "flash_diff+otsu"
                } else name = "otsu"
                raw = otsu
            }
            SegmenterKind.GRAY_BOX_COLOR -> { raw = grayBoxColor(frame.bgr); name = "gray_box_color" }
            else -> { raw = darkBackgroundOtsu(frame.bgr); name = "otsu" }
        }
        val clean = cleanup(raw)
        raw.release()
        val objects = extractObjects(clean, expectedCount)
        return Segmentation(clean, objects, name)
    }

    private fun resolveKind(background: BackgroundKind, hasPartner: Boolean): SegmenterKind {
        if (settings.segmenter != SegmenterKind.AUTO) return settings.segmenter
        return when (background) {
            BackgroundKind.GRAY_BOX -> SegmenterKind.GRAY_BOX_COLOR
            BackgroundKind.BLACK -> if (hasPartner) SegmenterKind.FLASH_DIFF else SegmenterKind.DARK_BG_OTSU
            BackgroundKind.AUTO -> if (hasPartner) SegmenterKind.FLASH_DIFF else SegmenterKind.DARK_BG_OTSU
        }
    }

    /** 黒背景前提: 輝度 Otsu。前景が明るい。Otsu が破綻(前景 >70%)したら固定しきい値にフォールバック。 */
    fun darkBackgroundOtsu(bgr: Mat): Mat {
        val gray = Preprocess.toGray(bgr)
        Imgproc.GaussianBlur(gray, gray, Size(5.0, 5.0), 0.0)
        val bin = Mat()
        Imgproc.threshold(gray, bin, 0.0, 255.0, Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU)
        val ratio = Core.countNonZero(bin).toDouble() / (bin.rows() * bin.cols())
        if (ratio > 0.7 || ratio < 0.002) {
            Imgproc.threshold(gray, bin, 60.0, 255.0, Imgproc.THRESH_BINARY)
        }
        gray.release()
        return bin
    }

    /**
     * フラッシュ差分: (フラッシュあり − なし) の輝度差。カメラ近傍の物体は強く明るくなるが、
     * 遠い背景はほとんど変化しない。手持ち撮影の微小なブレは事前のぼかしで吸収。
     */
    fun flashDiff(a: Frame, b: Frame): Mat {
        val on = if (a.flash) a else b
        val off = if (a.flash) b else a
        val gOn = Preprocess.toGray(on.bgr)
        val gOff = Preprocess.toGray(off.bgr)
        if (gOff.size() != gOn.size()) Imgproc.resize(gOff, gOff, gOn.size())
        Imgproc.GaussianBlur(gOn, gOn, Size(7.0, 7.0), 0.0)
        Imgproc.GaussianBlur(gOff, gOff, Size(7.0, 7.0), 0.0)
        val diff = Mat()
        Core.subtract(gOn, gOff, diff)  // 8U 飽和減算 → 負は 0
        val bin = Mat()
        Imgproc.threshold(diff, bin, settings.flashDiffThreshold.toDouble(), 255.0, Imgproc.THRESH_BINARY)
        gOn.release(); gOff.release(); diff.release()
        return bin
    }

    /**
     * グレー箱: 画像外周のリング(6%)を背景サンプルにする。
     * 明度 L は平面 a·x + b·y + c で最小二乗フィット(1 回外れ値除去)して内壁の影の勾配を吸収し、
     * 色 a/b は中央値。金属も箱も無彩色なので L の差は等倍で効かせる。
     * 事前に BoxRectifier で一番上の箱の内側に切り出してあることを想定(縁や隣の箱が外周に入ると推定が狂う)。
     */
    fun grayBoxColor(bgr: Mat): Mat {
        val lab = Mat()
        Imgproc.cvtColor(bgr, lab, Imgproc.COLOR_BGR2Lab)
        val h = lab.rows(); val w = lab.cols()
        val band = (minOf(w, h) * 0.06).toInt().coerceAtLeast(2)
        val data = ByteArray(h * w * 3)
        lab.get(0, 0, data)
        // --- リング画素を集める
        val xs = ArrayList<Int>(); val ys = ArrayList<Int>(); val ls = ArrayList<Double>()
        val histA = IntArray(256); val histB = IntArray(256)
        for (y in 0 until h) {
            val rowIsBand = y < band || y >= h - band
            var x = 0
            while (x < w) {
                if (rowIsBand || x < band || x >= w - band) {
                    val i = (y * w + x) * 3
                    xs += x; ys += y; ls += (data[i].toInt() and 0xFF).toDouble()
                    histA[data[i + 1].toInt() and 0xFF]++; histB[data[i + 2].toInt() and 0xFF]++
                    x++
                } else x = w - band  // 中央部はスキップ
            }
        }
        val aBg = median(histA); val bBg = median(histB)
        var coef = fitPlane(xs, ys, ls, null)
        // 外れ値除去して再フィット
        val resid = DoubleArray(xs.size) { i -> Math.abs(coef[0] * xs[i] + coef[1] * ys[i] + coef[2] - ls[i]) }
        val medRes = resid.sorted()[resid.size / 2]
        val keepThr = maxOf(8.0, 2.0 * medRes)
        coef = fitPlane(xs, ys, ls, BooleanArray(xs.size) { resid[it] < keepThr })
        // --- 距離画像
        val dist = ByteArray(h * w)
        for (y in 0 until h) {
            val base = coef[1] * y + coef[2]
            for (x in 0 until w) {
                val i = (y * w + x) * 3
                val dl = Math.abs((data[i].toInt() and 0xFF) - (coef[0] * x + base))
                val da = Math.abs((data[i + 1].toInt() and 0xFF) - aBg)
                val db = Math.abs((data[i + 2].toInt() and 0xFF) - bBg)
                dist[y * w + x] = minOf(255.0, dl + da + db).toInt().toByte()
            }
        }
        lab.release()
        val d = Mat(h, w, CvType.CV_8UC1)
        d.put(0, 0, dist)
        Imgproc.GaussianBlur(d, d, Size(5.0, 5.0), 0.0)
        val bin = Mat()
        Imgproc.threshold(d, bin, settings.grayBoxColorDistance.toDouble(), 255.0, Imgproc.THRESH_BINARY)
        d.release()
        return bin
    }

    private fun median(hist: IntArray): Int {
        val total = hist.sum(); var acc = 0
        for (v in hist.indices) { acc += hist[v]; if (acc * 2 >= total) return v }
        return 128
    }

    /** L = a·x + b·y + c の最小二乗(正規方程式 3x3)。 */
    private fun fitPlane(xs: List<Int>, ys: List<Int>, ls: List<Double>, keep: BooleanArray?): DoubleArray {
        var sxx = 0.0; var sxy = 0.0; var sx = 0.0; var syy = 0.0; var sy = 0.0; var n = 0.0
        var sxl = 0.0; var syl = 0.0; var sl = 0.0
        for (i in xs.indices) {
            if (keep != null && !keep[i]) continue
            val x = xs[i].toDouble(); val y = ys[i].toDouble(); val l = ls[i]
            sxx += x * x; sxy += x * y; sx += x; syy += y * y; sy += y; n += 1.0
            sxl += x * l; syl += y * l; sl += l
        }
        if (n < 3) return doubleArrayOf(0.0, 0.0, if (n > 0) sl / n else 128.0)
        val a = Mat(3, 3, CvType.CV_64F); val b = Mat(3, 1, CvType.CV_64F); val x = Mat()
        a.put(0, 0, sxx, sxy, sx, sxy, syy, sy, sx, sy, n)
        b.put(0, 0, sxl, syl, sl)
        val ok = Core.solve(a, b, x, org.opencv.core.Core.DECOMP_SVD)
        val out = if (ok) doubleArrayOf(x.get(0, 0)[0], x.get(1, 0)[0], x.get(2, 0)[0]) else doubleArrayOf(0.0, 0.0, sl / n)
        a.release(); b.release(); x.release()
        return out
    }

    private fun cleanup(bin: Mat): Mat {
        val k = settings.morphKernel.coerceAtLeast(1).let { if (it % 2 == 0) it + 1 else it }
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(k.toDouble(), k.toDouble()))
        val out = Mat()
        Imgproc.morphologyEx(bin, out, Imgproc.MORPH_OPEN, kernel)
        Imgproc.morphologyEx(out, out, Imgproc.MORPH_CLOSE, kernel)
        return out
    }

    /**
     * 外側輪郭を面積フィルタして物体一覧を作る。
     * 期待数より多い場合は面積上位を採用(呼び出し側で「検出数不一致」を判断できるよう全数も返す)。
     */
    private fun extractObjects(mask: Mat, expectedCount: Int): List<SegmentObject> {
        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(mask.clone(), contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        hierarchy.release()
        val minArea = settings.minObjectAreaRatio * mask.rows() * mask.cols()
        val objs = contours.mapNotNull { c ->
            val area = Imgproc.contourArea(c)
            if (area < minArea) { c.release(); return@mapNotNull null }
            val pts = MatOfPoint2f(*c.toArray())
            val rr = Imgproc.minAreaRect(pts)
            pts.release()
            val m = Imgproc.moments(c)
            val cen = if (m.m00 > 0) Point(m.m10 / m.m00, m.m01 / m.m00) else rr.center
            SegmentObject(c, area, Imgproc.boundingRect(c), rr, cen)
        }.sortedByDescending { it.area }
        return objs
    }

    companion object {
        /** 物体マスク(個別)を全体マスクから輪郭で切り出す。 */
        fun objectMask(seg: Segmentation, obj: SegmentObject): Mat {
            val m = Mat.zeros(seg.mask.size(), CvType.CV_8UC1)
            Imgproc.drawContours(m, listOf(obj.contour), -1, Scalar(255.0), Imgproc.FILLED)
            // 穴(内側輪郭)を保持するため全体マスクと AND
            Core.bitwise_and(m, seg.mask, m)
            return m
        }
    }
}
