package jp.hirameq.handycam.ui

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import jp.hirameq.handycam.App
import jp.hirameq.handycam.databinding.ActivityRoiEditorBinding
import jp.hirameq.handycam.imaging.ImageConvert
import jp.hirameq.handycam.model.Product
import jp.hirameq.handycam.util.toast

/**
 * ROI 編集: パーツの最初の登録ビュー(正規化画像)上でドラッグして注目領域を追加し、重みを指定。
 * ROI は Feature/HOG/NCC/Emboss の各手法で重み付けに使われる(押印文字の領域などを指定)。
 */
class RoiEditorActivity : AppCompatActivity() {
    private lateinit var b: ActivityRoiEditorBinding
    private val app get() = application as App
    private lateinit var product: Product
    private var partIdx = 0
    private val weights = listOf(1.5f, 2f, 3f, 5f)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityRoiEditorBinding.inflate(layoutInflater)
        setContentView(b.root)
        product = app.store.load(intent.getStringExtra(EXTRA_PRODUCT_ID) ?: run { finish(); return })
        partIdx = intent.getIntExtra(EXTRA_PART, 0)
        val part = product.parts[partIdx]
        b.title.text = "${product.name} / ${part.name} の ROI"
        val v = part.views.firstOrNull() ?: run { toast("ビューがありません"); finish(); return }
        val m = app.store.loadViewImage(product, v)
        b.roiView.bitmap = ImageConvert.toBitmap(m); m.release()
        b.roiView.rois.addAll(part.rois)
        b.roiView.onChanged = { renderList() }
        renderList()
        b.btnClear.setOnClickListener { b.roiView.rois.clear(); b.roiView.invalidate(); renderList() }
        b.btnSave.setOnClickListener {
            part.rois.clear(); part.rois.addAll(b.roiView.rois)
            app.store.save(product); app.library.invalidate()
            toast("保存しました"); finish()
        }
    }

    private fun renderList() {
        b.roiList.removeAllViews()
        for ((i, roi) in b.roiView.rois.withIndex()) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 4, 0, 4) }
            row.addView(TextView(this).apply { text = "#${i + 1}  (%.2f, %.2f) %.2f×%.2f  重み".format(roi.x, roi.y, roi.w, roi.h) }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            val sp = Spinner(this)
            sp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, weights.map { "×$it" })
            sp.setSelection(weights.indexOf(roi.weight).coerceAtLeast(0))
            sp.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) { b.roiView.rois[i] = roi.copy(weight = weights[pos]); b.roiView.invalidate() }
                override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
            }
            row.addView(sp)
            row.addView(TextView(this).apply { text = "  ✕"; textSize = 18f; setOnClickListener { b.roiView.rois.removeAt(i); b.roiView.invalidate(); renderList() } })
            b.roiList.addView(row)
        }
    }

    companion object { const val EXTRA_PRODUCT_ID = "pid"; const val EXTRA_PART = "part" }
}
