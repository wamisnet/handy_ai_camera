package jp.hirameq.handycam.ui

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import jp.hirameq.handycam.App
import jp.hirameq.handycam.R
import jp.hirameq.handycam.databinding.ActivityProductEditBinding
import jp.hirameq.handycam.imaging.ImageConvert
import jp.hirameq.handycam.model.BackgroundKind
import jp.hirameq.handycam.model.Product
import jp.hirameq.handycam.util.toast

/**
 * 製品編集: 名前/背景/パーツ名/鏡像許可、登録ビューの一覧・削除、ROI 編集、撮影(登録)への導線。
 */
class ProductEditActivity : AppCompatActivity() {
    private lateinit var b: ActivityProductEditBinding
    private val app get() = application as App
    private lateinit var product: Product

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityProductEditBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        val id = intent.getStringExtra(EXTRA_ID) ?: run { finish(); return }
        product = app.store.load(id)

        b.editName.setText(product.name)
        val bgs = BackgroundKind.values()
        b.spinnerBg.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, bgs.map { bgLabel(it) })
        b.spinnerBg.setSelection(bgs.indexOf(product.background))
        b.kind.text = MainActivity.kindLabel(product.kind)

        b.btnSave.setOnClickListener { save(); toast(getString(R.string.saved)) }
        b.btnRegister.setOnClickListener {
            save()
            startActivity(Intent(this, CaptureActivity::class.java)
                .putExtra(CaptureActivity.EXTRA_PRODUCT_ID, product.id)
                .putExtra(CaptureActivity.EXTRA_MODE, CaptureActivity.MODE_REGISTER))
        }
        b.btnVerify.setOnClickListener {
            save()
            if (product.parts.any { it.views.isEmpty() }) { toast(getString(R.string.need_views)); return@setOnClickListener }
            startActivity(Intent(this, CaptureActivity::class.java)
                .putExtra(CaptureActivity.EXTRA_PRODUCT_ID, product.id)
                .putExtra(CaptureActivity.EXTRA_MODE, CaptureActivity.MODE_VERIFY))
        }
        b.btnDelete.setOnClickListener {
            AlertDialog.Builder(this).setMessage(getString(R.string.confirm_delete, product.name))
                .setPositiveButton(R.string.delete) { _, _ -> app.store.delete(product.id); app.library.invalidate(); finish() }
                .setNegativeButton(android.R.string.cancel, null).show()
        }
    }

    override fun onResume() { super.onResume(); product = app.store.load(product.id); renderParts() }
    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    private fun save() {
        product.name = b.editName.text.toString().trim().ifEmpty { product.name }
        product.background = BackgroundKind.values()[b.spinnerBg.selectedItemPosition]
        for ((i, part) in product.parts.withIndex()) {
            val row = b.partsContainer.getChildAt(i) ?: continue
            val name = row.findViewById<EditText>(R.id.editPartName).text.toString().trim()
            val mirror = row.findViewById<CheckBox>(R.id.checkMirror).isChecked
            product.parts[i] = part.copy(name = name.ifEmpty { part.name }, allowMirror = mirror)
        }
        app.store.save(product)
        app.library.invalidate()
    }

    private fun renderParts() {
        b.partsContainer.removeAllViews()
        for ((i, part) in product.parts.withIndex()) {
            val row = LayoutInflater.from(this).inflate(R.layout.item_part, b.partsContainer, false)
            row.findViewById<EditText>(R.id.editPartName).setText(part.name)
            row.findViewById<CheckBox>(R.id.checkMirror).isChecked = part.allowMirror
            row.findViewById<TextView>(R.id.viewCount).text = getString(R.string.view_count, part.views.size, part.rois.size)
            val strip = row.findViewById<LinearLayout>(R.id.thumbStrip)
            for (v in part.views) {
                val iv = ImageView(this)
                val px = (96 * resources.displayMetrics.density).toInt()
                iv.layoutParams = LinearLayout.LayoutParams(px, px).apply { marginEnd = 8 }
                iv.scaleType = ImageView.ScaleType.FIT_CENTER
                iv.setBackgroundColor(0xFF202020.toInt())
                val m = app.store.loadViewImage(product, v)
                if (!m.empty()) {
                    val bmp: Bitmap = ImageConvert.toBitmap(m); m.release()
                    iv.setImageBitmap(bmp)
                }
                iv.setOnLongClickListener {
                    AlertDialog.Builder(this).setMessage(R.string.confirm_delete_view)
                        .setPositiveButton(R.string.delete) { _, _ -> app.store.removeView(product, i, v.id); app.library.invalidate(); product = app.store.load(product.id); renderParts() }
                        .setNegativeButton(android.R.string.cancel, null).show()
                    true
                }
                iv.setOnClickListener { toast("${if (v.flash) "フラッシュ" else "環境光"} / ${java.text.SimpleDateFormat("MM/dd HH:mm").format(java.util.Date(v.capturedAt))} (長押しで削除)") }
                strip.addView(iv)
            }
            row.findViewById<View>(R.id.btnRoi).apply {
                isEnabled = part.views.isNotEmpty()
                setOnClickListener {
                    save()
                    startActivity(Intent(this@ProductEditActivity, RoiEditorActivity::class.java)
                        .putExtra(RoiEditorActivity.EXTRA_PRODUCT_ID, product.id).putExtra(RoiEditorActivity.EXTRA_PART, i))
                }
            }
            b.partsContainer.addView(row)
        }
    }

    companion object {
        const val EXTRA_ID = "id"
        fun bgLabel(k: BackgroundKind) = when (k) {
            BackgroundKind.BLACK -> "黒背景(Otsu + フラッシュ差分)"
            BackgroundKind.GRAY_BOX -> "グレー箱(背景色距離)"
            BackgroundKind.AUTO -> "自動"
        }
    }
}
