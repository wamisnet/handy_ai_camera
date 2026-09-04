package jp.hirameq.handycam.match

import jp.hirameq.handycam.imaging.CanonicalImage
import jp.hirameq.handycam.imaging.Preprocess
import jp.hirameq.handycam.model.AppSettings
import jp.hirameq.handycam.model.MethodId
import jp.hirameq.handycam.model.MethodScore
import jp.hirameq.handycam.model.Roi
import org.opencv.core.MatOfFloat
import org.opencv.core.Size
import org.opencv.objdetect.HOGDescriptor
import kotlin.math.sqrt

/**
 * HOG(勾配方向ヒストグラム)の重み付きコサイン類似度。
 * 正規化画像全体の「見た目の構造」を、局所的な位置ずれ(セルサイズ程度)を許容しつつ比較する。
 * 押印のような低コントラスト陰影も CLAHE 後の勾配として拾える。ROI 内のブロックは重みを上げる。
 */
class HogMatcher : Matcher {
    override val id = MethodId.HOG
    override val orientationSensitive = true
    override val cost = 1

    companion object {
        const val CELL = 24
        const val BLOCK = 48
        const val STRIDE = 24
        const val BINS = 9
    }

    private fun hog(img: CanonicalImage): FloatArray = img.cached("hog") {
        val n = img.size
        val win = Size(n.toDouble(), n.toDouble())
        val d = HOGDescriptor(win, Size(BLOCK.toDouble(), BLOCK.toDouble()), Size(STRIDE.toDouble(), STRIDE.toDouble()),
            Size(CELL.toDouble(), CELL.toDouble()), BINS)
        val g = Preprocess.clahe(img.gray())
        val gm = Preprocess.applyMask(g, img.mask)
        val out = MatOfFloat()
        d.compute(gm, out)
        g.release(); gm.release()
        val arr = out.toArray(); out.release()
        arr
    }

    /** ブロック index → 画像上の中心座標 → ROI 重み。 */
    private fun blockWeights(canvas: Int, rois: List<Roi>): FloatArray {
        val per = (canvas - BLOCK) / STRIDE + 1
        val perBlock = (BLOCK / CELL) * (BLOCK / CELL) * BINS
        val w = FloatArray(per * per * perBlock)
        var i = 0
        // OpenCV HOG の並び: ブロック列(x)が外側, 行(y)が内側
        for (bx in 0 until per) for (by in 0 until per) {
            val cx = bx * STRIDE + BLOCK / 2.0; val cy = by * STRIDE + BLOCK / 2.0
            val wt = RoiUtil.weightAt(cx, cy, rois, canvas)
            for (k in 0 until perBlock) w[i++] = wt
        }
        return w
    }

    override fun compare(query: CanonicalImage, template: CanonicalImage, rois: List<Roi>, settings: AppSettings): MethodScore {
        val a = hog(query); val b = hog(template)
        if (a.size != b.size) return MethodScore(id, 0f, "size mismatch", unavailable = true)
        val w = blockWeights(template.size, rois)
        var dot = 0.0; var na = 0.0; var nb = 0.0
        for (i in a.indices) {
            val wi = if (i < w.size) w[i] else 1f
            dot += wi * a[i] * b[i]; na += wi * a[i] * a[i]; nb += wi * b[i] * b[i]
        }
        val den = sqrt(na * nb)
        val cos = if (den < 1e-9) 0.0 else dot / den
        return MethodScore(id, cos.toFloat().coerceIn(0f, 1f), "cos=%.3f".format(cos))
    }
}
