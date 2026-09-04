package jp.hirameq.handycam.ui

import android.os.Bundle
import android.text.InputType
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import jp.hirameq.handycam.App
import jp.hirameq.handycam.databinding.ActivitySettingsBinding
import jp.hirameq.handycam.model.AppSettings
import jp.hirameq.handycam.model.FeatureDetectorKind
import jp.hirameq.handycam.model.FlashStep
import jp.hirameq.handycam.model.FusionMode
import jp.hirameq.handycam.model.MethodId
import jp.hirameq.handycam.model.SegmenterKind
import jp.hirameq.handycam.util.toast

/**
 * 設定画面(コードでフォーム生成)。手法ごとの 有効/重み/しきい値/余裕/必須 と、撮影・セグメンテーション・融合の各パラメータ。
 */
class SettingsActivity : AppCompatActivity() {
    private lateinit var b: ActivitySettingsBinding
    private val app get() = application as App
    private lateinit var s: AppSettings
    private val binders = ArrayList<() -> Unit>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(b.root)
        s = app.settingsStore.load()
        build()
        b.btnSave.setOnClickListener {
            runCatching { binders.forEach { it() } }.onFailure { toast("入力エラー: ${it.message}"); return@setOnClickListener }
            app.settingsStore.save(s); toast("保存しました"); finish()
        }
        b.btnReset.setOnClickListener { app.settingsStore.reset(); s = AppSettings(); binders.clear(); b.form.removeAllViews(); build(); toast("既定値に戻しました(保存で確定)") }
    }

    private fun header(t: String) = b.form.addView(TextView(this).apply { text = t; textSize = 17f; setTypeface(null, android.graphics.Typeface.BOLD); setPadding(0, 28, 0, 8) })

    private fun number(label: String, value: Number, decimal: Boolean, apply: (Double) -> Unit) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(TextView(this).apply { text = label }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val e = EditText(this).apply {
            setText(if (decimal) "%.3f".format(value.toDouble()).trimEnd('0').trimEnd('.') else value.toString())
            inputType = InputType.TYPE_CLASS_NUMBER or (if (decimal) InputType.TYPE_NUMBER_FLAG_DECIMAL else 0)
            minWidth = 220
        }
        row.addView(e)
        b.form.addView(row)
        binders += { apply(e.text.toString().toDouble()) }
    }

    private fun check(label: String, value: Boolean, apply: (Boolean) -> Unit) {
        val c = CheckBox(this).apply { text = label; isChecked = value }
        b.form.addView(c); binders += { apply(c.isChecked) }
    }

    private fun <T> choice(label: String, options: List<T>, value: T, labels: (T) -> String, apply: (T) -> Unit) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(TextView(this).apply { text = label }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val sp = Spinner(this)
        sp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, options.map(labels))
        sp.setSelection(options.indexOf(value).coerceAtLeast(0))
        row.addView(sp); b.form.addView(row)
        binders += { apply(options[sp.selectedItemPosition]) }
    }

    private fun build() {
        header("撮影")
        check("フラッシュなし(環境光)フレームを撮る", s.flashSequence.contains(FlashStep.OFF)) { v -> s.flashSequence.remove(FlashStep.OFF); if (v) s.flashSequence.add(0, FlashStep.OFF) }
        check("フラッシュあり(トーチ)フレームを撮る", s.flashSequence.contains(FlashStep.ON)) { v -> s.flashSequence.remove(FlashStep.ON); if (v) s.flashSequence.add(FlashStep.ON) }
        number("各ステップのフレーム数", s.framesPerStep, false) { s.framesPerStep = it.toInt().coerceIn(1, 6) }
        number("トーチ切替後の待ち(ms)", s.settleMillis, false) { s.settleMillis = it.toLong().coerceIn(100, 2000) }
        number("解析画像の長辺(px)", s.analysisLongEdge, false) { s.analysisLongEdge = it.toInt().coerceIn(480, 2048) }
        number("正規化画像サイズ(px, 24の倍数)", s.canonicalLongEdge, false) { s.canonicalLongEdge = (it.toInt() / 24 * 24).coerceIn(192, 768) }

        header("セグメンテーション")
        choice("手法", SegmenterKind.values().toList(), s.segmenter, { it.name }) { s.segmenter = it }
        number("最小物体面積(画面比)", s.minObjectAreaRatio, true) { s.minObjectAreaRatio = it.toFloat().coerceIn(0.0005f, 0.5f) }
        number("フラッシュ差分しきい値(0-255)", s.flashDiffThreshold, false) { s.flashDiffThreshold = it.toInt().coerceIn(1, 200) }
        number("グレー箱: 背景色距離しきい値", s.grayBoxColorDistance, false) { s.grayBoxColorDistance = it.toInt().coerceIn(5, 200) }
        number("モルフォロジーカーネル(px)", s.morphKernel, false) { s.morphKernel = it.toInt().coerceIn(1, 31) }

        header("照合・判定")
        choice("局所特徴検出器", FeatureDetectorKind.values().toList(), s.featureDetector, { it.name }) { s.featureDetector = it }
        choice("融合モード", FusionMode.values().toList(), s.fusion, {
            when (it) { FusionMode.ALL_PASS -> "全手法が合格"; FusionMode.WEIGHTED -> "重み付き平均"; FusionMode.WEIGHTED_WITH_GATES -> "重み付き平均 + 必須手法" }
        }) { s.fusion = it }
        number("総合しきい値", s.overallThreshold, true) { s.overallThreshold = it.toFloat().coerceIn(0f, 1f) }
        number("総合: 他製品との必要余裕", s.overallMinMargin, true) { s.overallMinMargin = it.toFloat().coerceIn(0f, 1f) }
        choice("フレーム集約", listOf("MEDIAN", "MEAN", "MAX"), s.frameAggregation, { it }) { s.frameAggregation = it }
        check("デバッグ画像を保存(結果画面に表示)", s.saveDebugImages) { s.saveDebugImages = it }

        for (m in MethodId.values()) {
            val c = s.methods.getOrPut(m) { AppSettings.defaultMethods()[m]!! }
            header(m.label)
            check("有効", c.enabled) { c.enabled = it }
            number("重み", c.weight, true) { c.weight = it.toFloat().coerceIn(0f, 10f) }
            number("しきい値(本人スコア下限)", c.threshold, true) { c.threshold = it.toFloat().coerceIn(0f, 1f) }
            number("他製品との必要余裕", c.minMargin, true) { c.minMargin = it.toFloat().coerceIn(0f, 1f) }
            check("必須(gate)手法にする", c.gate) { c.gate = it }
        }
    }
}
