package jp.hirameq.handycam.imaging

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * 照明変動を吸収するための前処理群。
 * 各照合手法は「輝度そのもの」ではなく、ここで作る勾配/高域成分/CLAHE 画像を比較する。
 */
object Preprocess {

    fun toGray(bgr: Mat): Mat {
        val g = Mat()
        if (bgr.channels() == 1) bgr.copyTo(g) else Imgproc.cvtColor(bgr, g, Imgproc.COLOR_BGR2GRAY)
        return g
    }

    /** CLAHE: 局所コントラスト正規化。フラッシュ有無で変わる全体輝度・陰影の差を縮める。 */
    fun clahe(gray: Mat, clip: Double = 3.0, tiles: Int = 8): Mat {
        val c = Imgproc.createCLAHE(clip, Size(tiles.toDouble(), tiles.toDouble()))
        val out = Mat()
        c.apply(gray, out)
        return out
    }

    /** 勾配強度(8U 正規化)。押印の陰影・エッジを照明に依存しにくい形で表す。 */
    fun gradientMagnitude(gray: Mat, mask: Mat? = null): Mat {
        val gx = Mat(); val gy = Mat()
        Imgproc.Scharr(gray, gx, CvType.CV_32F, 1, 0)
        Imgproc.Scharr(gray, gy, CvType.CV_32F, 0, 1)
        val mag = Mat()
        Core.magnitude(gx, gy, mag)
        gx.release(); gy.release()
        val out = Mat()
        Core.normalize(mag, out, 0.0, 255.0, Core.NORM_MINMAX, CvType.CV_8U, mask ?: Mat())
        mag.release()
        if (mask != null) {
            val masked = Mat.zeros(out.size(), out.type())
            out.copyTo(masked, mask)
            out.release()
            return masked
        }
        return out
    }

    /**
     * 高域強調(gray − ガウスぼかし)。凹凸押印のように「陰影だけで見える」パターンを、
     * 大域的な明るさムラから切り離して取り出す。
     */
    fun highpass(gray: Mat, sigma: Double = 8.0): Mat {
        val f = Mat(); gray.convertTo(f, CvType.CV_32F)
        val blur = Mat()
        Imgproc.GaussianBlur(f, blur, Size(), sigma)
        val hp = Mat()
        Core.subtract(f, blur, hp)
        f.release(); blur.release()
        // ±σ の範囲を 0..255 に伸長。ロバストに標準偏差で正規化。
        val mean = org.opencv.core.MatOfDouble(); val std = org.opencv.core.MatOfDouble()
        Core.meanStdDev(hp, mean, std)
        val s = maxOf(std.get(0, 0)[0], 1e-3)
        val out = Mat()
        hp.convertTo(out, CvType.CV_8U, 128.0 / (2.5 * s), 128.0)
        hp.release()
        return out
    }

    /** マスク外を 0 で塗る。 */
    fun applyMask(src: Mat, mask: Mat): Mat {
        val out = Mat.zeros(src.size(), src.type())
        src.copyTo(out, mask)
        return out
    }

    fun erode(mask: Mat, px: Int): Mat {
        if (px <= 0) return mask.clone()
        val k = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(px * 2.0 + 1, px * 2.0 + 1))
        val out = Mat()
        Imgproc.erode(mask, out, k)
        return out
    }

    fun maskArea(mask: Mat): Int = Core.countNonZero(mask)

    fun zerosLike(m: Mat): Mat = Mat.zeros(m.size(), m.type())

    fun scalarGray(v: Double) = Scalar(v)
}
