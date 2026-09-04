package jp.hirameq.handycam.ui

import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import jp.hirameq.handycam.R
import jp.hirameq.handycam.databinding.ActivityResultBinding
import jp.hirameq.handycam.model.MethodId
import jp.hirameq.handycam.model.VerificationResult
import jp.hirameq.handycam.util.pct
import jp.hirameq.handycam.util.toast
import java.io.File

/** 検査結果: OK/NG バナー、パーツ別・手法別の本人/他者スコア表、不合格理由、デバッグ画像。 */
class ResultActivity : AppCompatActivity() {
    private lateinit var b: ActivityResultBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityResultBinding.inflate(layoutInflater)
        setContentView(b.root)
        val r = lastResult ?: run { finish(); return }
        render(r)
        b.btnRetry.setOnClickListener { finish() }
        b.btnCorrect.setOnClickListener { feedback(r, "confirmed_correct") }
        b.btnWrong.setOnClickListener { feedback(r, "confirmed_wrong") }
    }

    private fun feedback(r: VerificationResult, label: String) {
        val f = File(File(filesDir, "logs").apply { mkdirs() }, "feedback.csv")
        if (!f.exists()) f.writeText("timestamp,product,pass,label\n")
        f.appendText("${r.timestamp},\"${r.productName}\",${r.pass},$label\n")
        toast("記録しました")
        b.btnCorrect.isEnabled = false; b.btnWrong.isEnabled = false
    }

    private fun render(r: VerificationResult) {
        val ok = r.pass
        b.banner.text = if (ok) getString(R.string.result_ok) else getString(R.string.result_ng)
        b.banner.setBackgroundColor(ContextCompat.getColor(this, if (ok) R.color.ok_green else R.color.ng_red))
        b.summary.text = "${r.summary}\n処理 ${r.elapsedMillis} ms ・ 検出 ${r.detectedSegments}/${r.expectedParts}"
        b.parts.removeAllViews()
        for (v in r.partVerdicts) {
            val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(16, 16, 16, 16); setBackgroundColor(if (v.pass) 0x1A2E9E4F else 0x1AD13B2F) }
            card.addView(TextView(this).apply {
                text = "${v.partName}  →  ${if (v.pass) "OK" else "NG"}   総合 ${v.fused.pct()} (他製品最良 ${v.fusedImpostor.pct()}: ${v.bestImpostorName})"
                textSize = 15f; setTypeface(null, android.graphics.Typeface.BOLD)
            })
            val table = TableLayout(this).apply { isStretchAllColumns = true }
            table.addView(row("手法", "本人", "他製品", "判定", bold = true))
            for (m in MethodId.values()) {
                val s = v.selfScores[m] ?: continue
                val imp = v.bestImpostorScores[m]
                val p = v.methodPass[m]
                table.addView(row(m.shortLabel, s.pct(), imp?.pct() ?: "-", when (p) { true -> "✓"; false -> "✗"; null -> "" }))
            }
            card.addView(table)
            if (v.reasons.isNotEmpty()) card.addView(TextView(this).apply { text = v.reasons.joinToString("\n"); textSize = 13f })
            b.parts.addView(card, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 12 })
        }
        b.debugStrip.removeAllViews()
        for (path in r.debugImages) {
            val f = File(path); if (!f.exists()) continue
            val iv = ImageView(this)
            val px = (220 * resources.displayMetrics.density).toInt()
            iv.layoutParams = LinearLayout.LayoutParams(px, px).apply { marginEnd = 8 }
            iv.scaleType = ImageView.ScaleType.FIT_CENTER
            iv.setImageBitmap(BitmapFactory.decodeFile(path))
            iv.setOnClickListener { toast(f.nameWithoutExtension) }
            b.debugStrip.addView(iv)
        }
        b.debugLabel.visibility = if (r.debugImages.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun row(vararg cells: String, bold: Boolean = false): TableRow {
        val tr = TableRow(this)
        for (c in cells) tr.addView(TextView(this).apply {
            text = c; gravity = Gravity.CENTER; setPadding(6, 4, 6, 4); textSize = 14f
            if (bold) setTypeface(null, android.graphics.Typeface.BOLD)
        })
        return tr
    }

    companion object {
        /** Activity 間で結果を渡すための簡易ホルダ(Parcelable 化を避ける)。 */
        var lastResult: VerificationResult? = null
    }
}
