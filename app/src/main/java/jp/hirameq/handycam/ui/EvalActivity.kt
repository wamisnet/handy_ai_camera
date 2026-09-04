package jp.hirameq.handycam.ui

import android.os.Bundle
import android.view.Gravity
import android.widget.TableRow
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import jp.hirameq.handycam.App
import jp.hirameq.handycam.databinding.ActivityEvalBinding
import jp.hirameq.handycam.eval.LeaveOneOut
import jp.hirameq.handycam.util.pct
import jp.hirameq.handycam.util.shareFile
import jp.hirameq.handycam.util.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 手法評価: 登録済みビューの Leave-One-Out で、手法ごとの本人/他者スコア分布と推奨しきい値を表示。
 * 「推奨しきい値を適用」で設定に反映できる。
 */
class EvalActivity : AppCompatActivity() {
    private lateinit var b: ActivityEvalBinding
    private val app get() = application as App
    private var report: LeaveOneOut.Report? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityEvalBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.btnRun.setOnClickListener { run() }
        b.btnApply.setOnClickListener { apply() }
        b.btnCsv.setOnClickListener {
            val r = report ?: return@setOnClickListener
            val f = File(cacheDir, "loo_eval.csv"); f.writeText(LeaveOneOut(app.library, app.settingsStore.load()).toCsv(r))
            shareFile(f, "text/csv", "LOO 評価")
        }
        b.btnApply.isEnabled = false; b.btnCsv.isEnabled = false
    }

    private fun run() {
        b.btnRun.isEnabled = false
        b.progress.text = "計算中…"
        lifecycleScope.launch {
            val settings = app.settingsStore.load()
            val r = withContext(Dispatchers.Default) {
                LeaveOneOut(app.library, settings).run { d, t -> runOnUiThread { b.progress.text = "計算中… $d / $t ビュー" } }
            }
            report = r
            render(r)
            b.btnRun.isEnabled = true
            b.btnApply.isEnabled = r.stats.any { it.genuine.isNotEmpty() }
            b.btnCsv.isEnabled = r.pairs.isNotEmpty()
        }
    }

    private fun render(r: LeaveOneOut.Report) {
        b.progress.text = "本人ペア ${r.pairs.count { it.genuine }} / 他者ペア ${r.pairs.count { !it.genuine }}   ${r.note}"
        b.table.removeAllViews()
        b.table.addView(row("手法", "本人 min/中央", "他者 max/中央", "余裕", "推奨thr", "分離", bold = true))
        for (st in r.stats) {
            if (st.genuine.isEmpty() && st.impostor.isEmpty()) continue
            b.table.addView(row(st.method.shortLabel,
                "${st.genuineMin.pct()} / ${st.genuineMedian.pct()}",
                if (st.impostor.isEmpty()) "-" else "${st.impostorMax.pct()} / ${st.impostorMedian.pct()}",
                if (st.impostor.isEmpty()) "-" else st.gap.pct(),
                st.suggestedThreshold.pct(),
                if (st.impostor.isEmpty()) "?" else if (st.separable) "◎" else "✗"))
        }
        b.explain.text = """
            読み方:
            ・「余裕」= 本人最小 − 他者最大。正の値で大きいほどその手法は識別に効いている。負なら重なりがあり単独では危険。
            ・推奨しきい値は分離できる場合は中点、できない場合は誤受入と誤拒否が釣り合う点。
            ・他製品と最も差が出る手法の重みを上げ、余裕が負の手法は重みを下げる/無効化するのが基本方針。
            ・登録画像同士の評価なので実運用より楽観的。実際の検査ログ(CSV)で最終調整すること。
        """.trimIndent()
    }

    private fun apply() {
        val r = report ?: return
        val s = app.settingsStore.load()
        for (st in r.stats) {
            val c = s.methods[st.method] ?: continue
            if (st.genuine.isEmpty()) continue
            // 少し安全側(推奨値の 95%)。余裕は分離幅の 1/3 か 0.02 の大きい方。
            c.threshold = (st.suggestedThreshold * 0.95f).coerceIn(0f, 1f)
            c.minMargin = if (st.impostor.isEmpty()) c.minMargin else maxOf(0.02f, st.gap / 3f).coerceAtMost(0.3f)
        }
        app.settingsStore.save(s)
        toast("しきい値を適用しました(設定画面で確認可)")
    }

    private fun row(vararg cells: String, bold: Boolean = false): TableRow {
        val tr = TableRow(this)
        for (c in cells) tr.addView(TextView(this).apply { text = c; gravity = Gravity.CENTER; setPadding(6, 6, 6, 6); textSize = 13f; if (bold) setTypeface(null, android.graphics.Typeface.BOLD) })
        return tr
    }
}
