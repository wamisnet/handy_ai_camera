package jp.hirameq.handycam.ui

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import jp.hirameq.handycam.App
import jp.hirameq.handycam.R
import jp.hirameq.handycam.databinding.ActivityMainBinding
import jp.hirameq.handycam.databinding.ItemProductBinding
import jp.hirameq.handycam.model.Product
import jp.hirameq.handycam.model.ProductKind
import jp.hirameq.handycam.store.ProductStore
import jp.hirameq.handycam.util.shareFile
import jp.hirameq.handycam.util.toast
import java.io.File

class MainActivity : AppCompatActivity() {
    private lateinit var b: ActivityMainBinding
    private val app get() = application as App
    private var products: List<Product> = emptyList()

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        val tmp = File(cacheDir, "import.zip")
        contentResolver.openInputStream(uri)?.use { it.copyTo(tmp.outputStream()) }
        runCatching { app.store.importAll(tmp) }
            .onSuccess { app.library.invalidate(); reload(); toast(getString(R.string.imported)) }
            .onFailure { toast("インポート失敗: ${it.message}") }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)
        if (!app.opencvReady) toast("OpenCV の初期化に失敗しました")
        b.list.layoutManager = LinearLayoutManager(this)
        b.list.adapter = adapter
        b.fabAdd.setOnClickListener { showNewProductDialog() }
    }

    override fun onResume() { super.onResume(); reload() }

    private fun reload() {
        products = app.store.list()
        adapter.notifyDataSetChanged()
        b.empty.visibility = if (products.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun showNewProductDialog() {
        val v = layoutInflater.inflate(R.layout.dialog_new_product, null)
        val name = v.findViewById<EditText>(R.id.editName)
        val kind = v.findViewById<Spinner>(R.id.spinnerKind)
        val kinds = ProductKind.values()
        kind.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, kinds.map { kindLabel(it) })
        AlertDialog.Builder(this).setTitle(R.string.new_product).setView(v)
            .setPositiveButton(R.string.create) { _, _ ->
                val n = name.text.toString().trim()
                if (n.isEmpty()) { toast(getString(R.string.name_required)); return@setPositiveButton }
                val p = ProductStore.newProduct(n, kinds[kind.selectedItemPosition])
                app.store.save(p)
                app.library.invalidate()
                startActivity(Intent(this, ProductEditActivity::class.java).putExtra(ProductEditActivity.EXTRA_ID, p.id))
            }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean { menuInflater.inflate(R.menu.main, menu); return true }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_settings -> startActivity(Intent(this, SettingsActivity::class.java))
            R.id.action_eval -> startActivity(Intent(this, EvalActivity::class.java))
            R.id.action_export -> {
                val f = File(cacheDir, "handycam_products.zip")
                app.store.exportAll(f)
                shareFile(f, "application/zip", "HandyCam テンプレート")
            }
            R.id.action_import -> importLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
            R.id.action_share_log -> {
                val f = jp.hirameq.handycam.verify.VerifyLog(this).file
                if (f.exists()) shareFile(f, "text/csv", "HandyCam 検査ログ") else toast(getString(R.string.no_log))
            }
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private val adapter = object : RecyclerView.Adapter<VH>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ItemProductBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        override fun getItemCount() = products.size
        override fun onBindViewHolder(h: VH, pos: Int) {
            val p = products[pos]
            h.b.name.text = p.name
            h.b.meta.text = "${kindLabel(p.kind)} ・ パーツ ${p.partCount} ・ 登録ビュー ${p.viewCount}"
            h.b.root.setOnClickListener {
                startActivity(Intent(this@MainActivity, ProductEditActivity::class.java).putExtra(ProductEditActivity.EXTRA_ID, p.id))
            }
            h.b.btnVerify.isEnabled = p.parts.all { it.views.isNotEmpty() }
            h.b.btnVerify.setOnClickListener {
                startActivity(Intent(this@MainActivity, CaptureActivity::class.java)
                    .putExtra(CaptureActivity.EXTRA_PRODUCT_ID, p.id)
                    .putExtra(CaptureActivity.EXTRA_MODE, CaptureActivity.MODE_VERIFY))
            }
        }
    }
    class VH(val b: ItemProductBinding) : RecyclerView.ViewHolder(b.root)

    companion object {
        fun kindLabel(k: ProductKind) = when (k) {
            ProductKind.FOAM_PAIR -> "ウレタン2点セット(黒背景)"
            ProductKind.METAL_IN_BOX -> "金属部品(グレー箱)"
            ProductKind.SINGLE -> "単品(汎用)"
        }
    }
}
