package jp.hirameq.handycam.verify

import android.content.Context
import jp.hirameq.handycam.model.MethodId
import jp.hirameq.handycam.model.VerificationResult
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 検査結果を CSV に追記。手法ごとの本人/他者スコアを全部残すので、
 * PC 側(pctools/analyze_log.py)でしきい値・手法の良し悪しを後から分析できる。
 */
class VerifyLog(context: Context) {
    val file: File = File(File(context.filesDir, "logs").apply { mkdirs() }, "verify_log.csv")
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    fun append(r: VerificationResult, operatorLabel: String = "") {
        val methods = MethodId.values()
        if (!file.exists()) {
            val head = mutableListOf("timestamp", "product_id", "product", "overall_pass", "part", "part_pass", "segment", "fused_self", "fused_impostor", "best_impostor", "elapsed_ms", "label")
            methods.forEach { head += "${it.name}_self"; head += "${it.name}_impostor"; head += "${it.name}_pass" }
            head += "reasons"
            file.writeText(head.joinToString(",") + "\n")
        }
        val ts = fmt.format(Date(r.timestamp))
        val sb = StringBuilder()
        if (r.partVerdicts.isEmpty()) {
            val row = mutableListOf(ts, r.productId, q(r.productName), r.pass.toString(), "", "false", "", "", "", "", r.elapsedMillis.toString(), q(operatorLabel))
            methods.forEach { row += ""; row += ""; row += "" }
            row += q(r.summary)
            sb.append(row.joinToString(",")).append('\n')
        }
        for (v in r.partVerdicts) {
            val row = mutableListOf(ts, r.productId, q(r.productName), r.pass.toString(), q(v.partName), v.pass.toString(), v.segmentIndex.toString(),
                "%.4f".format(v.fused), "%.4f".format(v.fusedImpostor), q(v.bestImpostorName), r.elapsedMillis.toString(), q(operatorLabel))
            for (m in methods) {
                row += v.selfScores[m]?.let { "%.4f".format(it) } ?: ""
                row += v.bestImpostorScores[m]?.let { "%.4f".format(it) } ?: ""
                row += v.methodPass[m]?.toString() ?: ""
            }
            row += q(v.reasons.joinToString(" | "))
            sb.append(row.joinToString(",")).append('\n')
        }
        file.appendText(sb.toString())
    }

    private fun q(s: String) = "\"" + s.replace("\"", "\"\"") + "\""
}
