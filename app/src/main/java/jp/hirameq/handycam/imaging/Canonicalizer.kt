package jp.hirameq.handycam.imaging

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * 正規化画像。物体を「長軸水平・中央配置・長辺一定」に写した固定サイズのキャンバス。
 * 手持ち撮影による距離差(スケール)と面内回転をここで吸収する。
 */
class CanonicalImage(
    val bgr: Mat,
    val mask: Mat,
    /** 180° 回転や鏡像などの派生バリアントを識別する名前。 */
    val variant: String = "base",
) {
    val size: Int get() = bgr.rows()
    private val gray: Mat by lazy { Preprocess.toGray(bgr) }
    fun gray(): Mat = gray
    private val cache = HashMap<String, Any>()
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> cached(key: String, compute: () -> T): T = cache.getOrPut(key) { compute() } as T

    fun rotated180(): CanonicalImage {
        val b = Mat(); val m = Mat()
        Core.rotate(bgr, b, Core.ROTATE_180)
        Core.rotate(mask, m, Core.ROTATE_180)
        return CanonicalImage(b, m, "$variant+rot180")
    }

    fun mirrored(): CanonicalImage {
        val b = Mat(); val m = Mat()
        Core.flip(bgr, b, 1)
        Core.flip(mask, m, 1)
        return CanonicalImage(b, m, "$variant+mirror")
    }

    /** 照合対象のバリアント一覧。canonical 化で残る 180° の曖昧さ(+必要なら鏡像)を列挙。 */
    fun variants(allowMirror: Boolean): List<CanonicalImage> {
        val list = mutableListOf(this, rotated180())
        if (allowMirror) { val mir = mirrored(); list += mir; list += mir.rotated180() }
        return list
    }

    fun release() {
        bgr.release(); mask.release()
        cache.values.forEach { (it as? Mat)?.release() }
        cache.clear()
    }
}

object Canonicalizer {

    /**
     * @param fillRatio キャンバス長辺に対する物体長辺の比。周囲に余白を残しておく(特徴点の境界効果対策)。
     */
    fun canonicalize(bgr: Mat, objectMask: Mat, obj: SegmentObject, canvas: Int, fillRatio: Double = 0.88): CanonicalImage {
        val rr = obj.rotRect
        var angle = rr.angle
        var w = rr.size.width
        var h = rr.size.height
        if (w < h) { angle += 90.0; val t = w; w = h; h = t }
        // 長軸を水平に。angle は物体をこの角度だけ「反時計回り」に回せば水平になる値。
        val scale = canvas * fillRatio / maxOf(w, 1.0)
        val m = Imgproc.getRotationMatrix2D(rr.center, angle, scale)
        // 回転中心をキャンバス中心へ平行移動
        val cx = canvas / 2.0; val cy = canvas / 2.0
        m.put(0, 2, m.get(0, 2)[0] + cx - rr.center.x)
        m.put(1, 2, m.get(1, 2)[0] + cy - rr.center.y)

        val outBgr = Mat()
        val outMask = Mat()
        val sz = Size(canvas.toDouble(), canvas.toDouble())
        Imgproc.warpAffine(bgr, outBgr, m, sz, Imgproc.INTER_LINEAR, Core.BORDER_CONSTANT, Scalar(0.0, 0.0, 0.0))
        Imgproc.warpAffine(objectMask, outMask, m, sz, Imgproc.INTER_NEAREST, Core.BORDER_CONSTANT, Scalar(0.0))
        m.release()
        // マスク外を黒に(背景の影響を排除)
        val masked = Mat.zeros(outBgr.size(), outBgr.type())
        outBgr.copyTo(masked, outMask)
        outBgr.release()
        return CanonicalImage(masked, outMask)
    }

    /**
     * 2 つの正規化画像の 180° 曖昧性を解消: query をそのまま/180° 回した場合のうち、
     * reference と勾配相関が高い方を返す。登録時に複数ビューの向きを揃えるために使う。
     */
    fun alignOrientationTo(reference: CanonicalImage, query: CanonicalImage, allowMirror: Boolean): CanonicalImage {
        val cands = query.variants(allowMirror)
        val refG = Preprocess.gradientMagnitude(reference.gray(), reference.mask)
        var best = cands[0]; var bestScore = -2.0
        for (c in cands) {
            val g = Preprocess.gradientMagnitude(c.gray(), c.mask)
            val s = maskedCorrelation(refG, g, reference.mask, c.mask)
            g.release()
            if (s > bestScore) { bestScore = s; best = c }
        }
        refG.release()
        cands.filter { it !== best && it !== query }.forEach { it.release() }
        return if (best === query) query else CanonicalImage(best.bgr, best.mask, "base")
    }

    /** 両マスクの共通領域での Pearson 相関。 */
    fun maskedCorrelation(a: Mat, b: Mat, maskA: Mat, maskB: Mat): Double {
        val m = Mat()
        Core.bitwise_and(maskA, maskB, m)
        val n = Core.countNonZero(m)
        if (n < 50) { m.release(); return 0.0 }
        val fa = Mat(); val fb = Mat()
        a.convertTo(fa, CvType.CV_32F); b.convertTo(fb, CvType.CV_32F)
        val ma = Core.mean(fa, m).`val`[0]; val mb = Core.mean(fb, m).`val`[0]
        Core.subtract(fa, Scalar(ma), fa); Core.subtract(fb, Scalar(mb), fb)
        val prod = Mat(); Core.multiply(fa, fb, prod)
        val sa = Mat(); Core.multiply(fa, fa, sa)
        val sb = Mat(); Core.multiply(fb, fb, sb)
        val cov = Core.sumElems(Preprocess.applyMask(prod, m)).`val`[0]
        val va = Core.sumElems(Preprocess.applyMask(sa, m)).`val`[0]
        val vb = Core.sumElems(Preprocess.applyMask(sb, m)).`val`[0]
        listOf(m, fa, fb, prod, sa, sb).forEach { it.release() }
        val den = Math.sqrt(va * vb)
        return if (den < 1e-6) 0.0 else cov / den
    }

    fun centroidOf(mask: Mat): Point {
        val mo = Imgproc.moments(mask, true)
        return if (mo.m00 > 0) Point(mo.m10 / mo.m00, mo.m01 / mo.m00) else Point(mask.cols() / 2.0, mask.rows() / 2.0)
    }
}
