package jp.hirameq.handycam.match

import jp.hirameq.handycam.imaging.CanonicalImage
import jp.hirameq.handycam.model.AppSettings
import jp.hirameq.handycam.model.MethodId
import jp.hirameq.handycam.model.MethodScore
import jp.hirameq.handycam.model.Roi
import org.opencv.core.Rect

/**
 * 照合手法の共通インタフェース。
 * 入力は両方とも正規化画像(CanonicalImage)なので、各手法はスケール/回転をほぼ気にせずよい。
 * ただし canonical 化で残る 180° の曖昧さは、orientationSensitive な手法に対して呼び出し側がバリアントを回して吸収する。
 */
interface Matcher {
    val id: MethodId
    /** 180°/鏡像のバリアントごとに評価が必要か(false なら base のみで十分)。 */
    val orientationSensitive: Boolean
    /** 計算コストの目安。1=軽い(全候補で回す), 3=重い(上位候補のみ)。 */
    val cost: Int
    fun compare(query: CanonicalImage, template: CanonicalImage, rois: List<Roi>, settings: AppSettings): MethodScore
}

object RoiUtil {
    fun toRect(r: Roi, canvas: Int): Rect = Rect(
        (r.x * canvas).toInt().coerceIn(0, canvas - 1),
        (r.y * canvas).toInt().coerceIn(0, canvas - 1),
        (r.w * canvas).toInt().coerceIn(1, canvas),
        (r.h * canvas).toInt().coerceIn(1, canvas),
    ).let { Rect(it.x, it.y, minOf(it.width, canvas - it.x), minOf(it.height, canvas - it.y)) }

    /** 点 (x,y) に対する重み: 含まれる ROI の最大 weight、なければ 1。 */
    fun weightAt(x: Double, y: Double, rois: List<Roi>, canvas: Int): Float {
        var w = 1f
        for (r in rois) {
            val rx = r.x * canvas; val ry = r.y * canvas
            if (x >= rx && y >= ry && x <= rx + r.w * canvas && y <= ry + r.h * canvas) w = maxOf(w, r.weight)
        }
        return w
    }
}

object MatcherRegistry {
    fun all(): List<Matcher> = listOf(
        ShapeMatcher(), FeatureMatcher(), HogMatcher(), NccMatcher(), EmbossMatcher(), HoleMatcher(), EmbeddingMatcher(),
    )
    fun byId(id: MethodId): Matcher = all().first { it.id == id }
}
