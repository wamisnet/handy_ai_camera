package jp.hirameq.handycam.verify

import jp.hirameq.handycam.model.AppSettings
import jp.hirameq.handycam.model.FusionMode
import jp.hirameq.handycam.model.MethodId

/**
 * 手法別スコアから合否を決める純粋ロジック(Android 依存なし、ユニットテスト対象)。
 *
 * 各手法: pass = self ≥ threshold かつ (self − impostor) ≥ minMargin
 * 融合  : ALL_PASS / WEIGHTED / WEIGHTED_WITH_GATES
 *
 * 「他製品を登録していない」場合は impostor が存在しないので margin 条件は評価しない(理由に明記)。
 */
object Decision {

    data class Outcome(
        val methodPass: Map<MethodId, Boolean>,
        val fused: Float,
        val fusedImpostor: Float,
        val pass: Boolean,
        val reasons: List<String>,
    )

    fun decide(
        self: Map<MethodId, Float>,
        impostor: Map<MethodId, Float>,
        hasImpostors: Boolean,
        settings: AppSettings,
    ): Outcome {
        val reasons = ArrayList<String>()
        val methodPass = HashMap<MethodId, Boolean>()
        var wsum = 0f; var accSelf = 0f; var accImp = 0f
        var anyGateFail = false
        var allPass = true

        for ((id, s) in self) {
            val cfg = settings.methods[id] ?: continue
            val imp = impostor[id]
            val thrOk = s >= cfg.threshold
            val marginOk = !hasImpostors || imp == null || (s - imp) >= cfg.minMargin
            val ok = thrOk && marginOk
            methodPass[id] = ok
            if (!thrOk) reasons += "${id.shortLabel}: スコア %.2f < しきい値 %.2f".format(s, cfg.threshold)
            else if (!marginOk) reasons += "${id.shortLabel}: 他製品との差 %.2f < 必要余裕 %.2f".format(s - (imp ?: 0f), cfg.minMargin)
            if (!ok) { allPass = false; if (cfg.gate) anyGateFail = true }
            wsum += cfg.weight; accSelf += cfg.weight * s; accImp += cfg.weight * (imp ?: 0f)
        }
        val fused = if (wsum > 0) accSelf / wsum else 0f
        val fusedImp = if (wsum > 0) accImp / wsum else 0f

        if (self.isEmpty()) return Outcome(methodPass, 0f, 0f, false, listOf("評価可能な手法がありません"))

        val fusedThrOk = fused >= settings.overallThreshold
        val fusedMarginOk = !hasImpostors || (fused - fusedImp) >= settings.overallMinMargin
        val pass = when (settings.fusion) {
            FusionMode.ALL_PASS -> allPass
            FusionMode.WEIGHTED -> fusedThrOk && fusedMarginOk
            FusionMode.WEIGHTED_WITH_GATES -> fusedThrOk && fusedMarginOk && !anyGateFail
        }
        if (settings.fusion != FusionMode.ALL_PASS) {
            if (!fusedThrOk) reasons += "総合: %.2f < しきい値 %.2f".format(fused, settings.overallThreshold)
            if (!fusedMarginOk) reasons += "総合: 他製品との差 %.2f < 必要余裕 %.2f".format(fused - fusedImp, settings.overallMinMargin)
            if (settings.fusion == FusionMode.WEIGHTED_WITH_GATES && anyGateFail) reasons += "必須手法が不合格"
        }
        if (!hasImpostors) reasons += "(他製品未登録のため識別余裕は未評価)"
        return Outcome(methodPass, fused, fusedImp, pass, reasons)
    }

    /** n パーツ × m セグメントの割り当て。n ≤ 4 は全順列、以降は貪欲。 */
    fun assign(scoreMatrix: Array<FloatArray>): IntArray {
        val n = scoreMatrix.size
        if (n == 0) return IntArray(0)
        val m = scoreMatrix[0].size
        if (n <= 4 && m <= 6) {
            var best: IntArray? = null; var bestSum = Float.NEGATIVE_INFINITY
            fun rec(i: Int, used: BooleanArray, cur: IntArray, sum: Float) {
                if (i == n) { if (sum > bestSum) { bestSum = sum; best = cur.clone() }; return }
                for (j in 0 until m) if (!used[j]) {
                    used[j] = true; cur[i] = j
                    rec(i + 1, used, cur, sum + scoreMatrix[i][j])
                    used[j] = false
                }
            }
            rec(0, BooleanArray(m), IntArray(n), 0f)
            return best ?: IntArray(n) { it }
        }
        val used = BooleanArray(m); val out = IntArray(n)
        for (i in 0 until n) {
            var bj = -1; var bs = Float.NEGATIVE_INFINITY
            for (j in 0 until m) if (!used[j] && scoreMatrix[i][j] > bs) { bs = scoreMatrix[i][j]; bj = j }
            out[i] = bj; if (bj >= 0) used[bj] = true
        }
        return out
    }
}
