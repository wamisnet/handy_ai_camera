package jp.hirameq.handycam.verify

import jp.hirameq.handycam.imaging.CanonicalImage
import jp.hirameq.handycam.match.Matcher
import jp.hirameq.handycam.model.AppSettings
import jp.hirameq.handycam.model.MethodId
import jp.hirameq.handycam.model.MethodScore
import jp.hirameq.handycam.model.Product
import jp.hirameq.handycam.model.ProductKind

/** 手法ごとの (query, part) スコア = max over テンプレートビュー, max over 向きバリアント。 */
object Scoring {

    /** 製品種別に応じて意味のない手法を除外(例: ウレタンに穴パターン)。 */
    fun activeMethods(settings: AppSettings, product: Product, matchers: List<Matcher>): List<Matcher> =
        matchers.filter { m ->
            val cfg = settings.methods[m.id] ?: return@filter false
            if (!cfg.enabled) return@filter false
            when (m.id) {
                MethodId.HOLES -> product.kind == ProductKind.METAL_IN_BOX || product.kind == ProductKind.SINGLE
                else -> true
            }
        }

    /**
     * 1 つのクエリ(向きバリアント群)を 1 パーツのテンプレート群と比較。
     * @return 手法 → 最良スコア(unavailable のみなら null)
     */
    fun scorePart(
        queryVariants: List<CanonicalImage>,
        part: LoadedPart,
        matchers: List<Matcher>,
        settings: AppSettings,
    ): Map<MethodId, MethodScore?> {
        val out = HashMap<MethodId, MethodScore?>()
        val rois = part.part.rois
        for (m in matchers) {
            var best: MethodScore? = null
            val variants = if (m.orientationSensitive) queryVariants else queryVariants.take(1)
            for (tv in part.views) for (qv in variants) {
                val s = runCatching { m.compare(qv, tv.image, rois, settings) }
                    .getOrElse { MethodScore(m.id, 0f, "error: ${it.message}", unavailable = true) }
                if (s.unavailable) { if (best == null) best = s; continue }
                if (best == null || best.unavailable || s.score > best.score) best = s
            }
            out[m.id] = best
        }
        return out
    }

    fun aggregate(values: List<Float>, mode: String): Float {
        if (values.isEmpty()) return 0f
        return when (mode.uppercase()) {
            "MAX" -> values.max()
            "MEAN" -> values.average().toFloat()
            else -> { val s = values.sorted(); if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2f }
        }
    }
}
