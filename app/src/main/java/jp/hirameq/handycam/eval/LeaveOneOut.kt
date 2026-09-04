package jp.hirameq.handycam.eval

import jp.hirameq.handycam.match.MatcherRegistry
import jp.hirameq.handycam.model.AppSettings
import jp.hirameq.handycam.model.MethodId
import jp.hirameq.handycam.verify.LoadedPart
import jp.hirameq.handycam.verify.Scoring
import jp.hirameq.handycam.verify.TemplateLibrary

/**
 * 登録済みビューだけを使った手法評価(Leave-One-Out)。
 * 各ビューをクエリとして取り出し、同一パーツの残りビュー(本人)と、他製品の全パーツ(他者)に対するスコアを計算。
 * 本人分布と他者分布の分離度から、手法ごとの推奨しきい値を算出する。
 *
 * 実際の検査画像とは条件が違う(登録時の画像のみ)ので「手法の相対比較・初期しきい値の目安」として使う。
 * 実運用のしきい値は VerifyLog の CSV から詰める。
 */
class LeaveOneOut(private val library: TemplateLibrary, private val settings: AppSettings) {

    data class Pair(val queryLabel: String, val targetLabel: String, val genuine: Boolean, val scores: Map<MethodId, Float>)

    data class MethodStat(
        val method: MethodId,
        val genuine: List<Float>,
        val impostor: List<Float>,
    ) {
        val genuineMin get() = genuine.minOrNull() ?: 0f
        val genuineMedian get() = median(genuine)
        val impostorMax get() = impostor.maxOrNull() ?: 0f
        val impostorMedian get() = median(impostor)
        /** 本人最小 > 他者最大 なら完全分離 */
        val separable get() = genuine.isNotEmpty() && (impostor.isEmpty() || genuineMin > impostorMax)
        /** 推奨しきい値: 分離できるなら中点、できないなら誤受入と誤拒否の数が等しくなる点 */
        val suggestedThreshold: Float get() {
            if (genuine.isEmpty()) return 0.5f
            if (impostor.isEmpty()) return (genuineMin * 0.9f)
            if (separable) return (genuineMin + impostorMax) / 2f
            val cands = (genuine + impostor).sorted()
            var best = cands[0]; var bestErr = Int.MAX_VALUE
            for (t in cands) {
                val fr = genuine.count { it < t }; val fa = impostor.count { it >= t }
                val err = maxOf(fr, fa)
                if (err < bestErr) { bestErr = err; best = t }
            }
            return best
        }
        /** 分離余裕(本人最小 − 他者最大)。負なら重なりあり。 */
        val gap get() = genuineMin - impostorMax
        companion object {
            fun median(v: List<Float>): Float { if (v.isEmpty()) return 0f; val s = v.sorted(); return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2f }
        }
    }

    data class Report(val pairs: List<Pair>, val stats: List<MethodStat>, val note: String)

    fun run(progress: (Int, Int) -> Unit = { _, _ -> }): Report {
        val parts = library.parts().filter { it.views.isNotEmpty() }
        val matchers = MatcherRegistry.all().filter { settings.methods[it.id]?.enabled == true }
        val pairs = ArrayList<Pair>()
        val total = parts.sumOf { it.views.size }
        var done = 0
        for (lp in parts) for (v in lp.views) {
            val qVariants = v.image.variants(lp.part.allowMirror)
            // 本人: 同一パーツの残りビュー
            val rest = lp.views.filter { it !== v }
            if (rest.isNotEmpty()) {
                val tmp = LoadedPart(lp.product, lp.partIndex, rest)
                val sc = Scoring.scorePart(qVariants, tmp, Scoring.activeMethods(settings, lp.product, matchers), settings)
                pairs += Pair(lp.displayName + "#" + v.view.id, lp.displayName, true, sc.filterValues { it != null && !it.unavailable }.mapValues { it.value!!.score })
            }
            // 他者: 他製品の全パーツ
            for (op in parts) if (op.product.id != lp.product.id) {
                val sc = Scoring.scorePart(qVariants, op, Scoring.activeMethods(settings, lp.product, matchers), settings)
                pairs += Pair(lp.displayName + "#" + v.view.id, op.displayName, false, sc.filterValues { it != null && !it.unavailable }.mapValues { it.value!!.score })
            }
            qVariants.filter { it !== v.image }.forEach { it.release() }
            done++; progress(done, total)
        }
        val stats = matchers.map { m ->
            MethodStat(m.id,
                pairs.filter { it.genuine }.mapNotNull { it.scores[m.id] },
                pairs.filter { !it.genuine }.mapNotNull { it.scores[m.id] })
        }
        val note = when {
            parts.size < 2 -> "製品が 1 つしか無いため他者分布は空です。2 製品以上登録すると識別余裕が評価できます。"
            parts.any { it.views.size < 2 } -> "ビューが 1 枚しかないパーツは本人分布に含まれません。距離/角度を変えて 3 枚以上登録してください。"
            else -> ""
        }
        return Report(pairs, stats, note)
    }

    fun toCsv(r: Report): String {
        val ms = MethodId.values()
        val sb = StringBuilder("query,target,genuine," + ms.joinToString(",") { it.name } + "\n")
        for (p in r.pairs) sb.append("\"${p.queryLabel}\",\"${p.targetLabel}\",${p.genuine},").append(ms.joinToString(",") { p.scores[it]?.let { s -> "%.4f".format(s) } ?: "" }).append('\n')
        return sb.toString()
    }
}
