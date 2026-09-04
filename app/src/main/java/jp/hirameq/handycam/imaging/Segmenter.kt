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
     * グレー箱: 画像外周 8% の帯を背景サンプルとして Lab 中央値を求め、
     * 各画素の色距離がしきい値を超える部分を前景とする。金属は明暗いずれにも振れるので色距離で拾う。
     */
    fun grayBoxColor(bgr: Mat): Mat {
        val lab = Mat()
        Imgproc.cvtColor(bgr, lab, Imgproc.COLOR_BGR2Lab)
        val h = lab.rows(); val w = lab.cols()
        val bw = (w * 0.08).toInt().coerceAtLeast(2)
        val bh = (h * 0.08).toInt().coerceAtLeast(2)
        val border = Mat.zeros(lab.size(), CvType.CV_8UC1)
        Imgproc.rectangle(border, Point(0.0, 0.0), Point(w - 1.0, h - 1.0), Scalar(255.0), bw.coerceAtMost(bh) * 2)
        val bgMean = Core.mean(lab, border)
        border.release()
        val bgMat = Mat(lab.size(), lab.type(), bgMean)
        val diff = Mat()
        Core.absdiff(lab, bgMat, diff)
        bgMat.release()
        val ch = ArrayList<Mat>()
        Core.split(diff, ch)
        // L の差は半分、a/b の差は等倍で合成(照明ムラ耐性を少し持たせる)
        val d = Mat()
        Core.addWeighted(ch[0], 0.5, ch[1], 1.0, 0.0, d)
        Core.add(d, ch[2], d)
        ch.forEach { it.release() }; diff.release(); lab.release()
        Imgproc.GaussianBlur(d, d, Size(5.0, 5.0), 0.0)
        val bin = Mat()
        Imgproc.threshold(d, bin, settings.grayBoxColorDistance.toDouble(), 255.0, Imgproc.THRESH_BINARY)
        d.release()
        return bin
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
