package jp.hirameq.handycam.store

import android.content.Context
import jp.hirameq.handycam.model.PartTemplate
import jp.hirameq.handycam.model.Product
import jp.hirameq.handycam.model.TemplateView
import org.opencv.core.Mat
import org.opencv.imgcodecs.Imgcodecs
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 製品テンプレートの永続化。
 *
 * files/products/<productId>/product.json
 * files/products/<productId>/parts/<partIdx>/views/<viewId>.png       正規化 RGB
 * files/products/<productId>/parts/<partIdx>/views/<viewId>_mask.png  正規化マスク
 *
 * PC 側ツール(pctools)もこのレイアウトをそのまま読む。
 */
class ProductStore(context: Context) {
    private val gson = SettingsStore.gsonWithDefaults(pretty = true)
    val root: File = File(context.filesDir, "products").apply { mkdirs() }

    fun productDir(id: String): File = File(root, id).apply { mkdirs() }

    fun list(): List<Product> =
        root.listFiles { f -> f.isDirectory && File(f, "product.json").exists() }
            ?.mapNotNull { runCatching { load(it.name) }.getOrNull() }
            ?.sortedBy { it.name }
            ?: emptyList()

    fun load(id: String): Product =
        gson.fromJson(File(productDir(id), "product.json").readText(), Product::class.java)

    fun save(product: Product) {
        File(productDir(product.id), "product.json").writeText(gson.toJson(product))
    }

    fun delete(id: String) {
        productDir(id).deleteRecursively()
    }

    fun viewFile(product: Product, partIdx: Int, rel: String): File = File(productDir(product.id), rel)

    /** 正規化画像とマスクを保存してビューを追加する。 */
    fun addView(product: Product, partIdx: Int, canonicalBgr: Mat, mask: Mat, flash: Boolean, note: String = ""): TemplateView {
        val part = product.parts[partIdx]
        val id = java.util.UUID.randomUUID().toString().substring(0, 8)
        val dir = File(productDir(product.id), "parts/$partIdx/views").apply { mkdirs() }
        val rel = "parts/$partIdx/views/$id.png"
        val relMask = "parts/$partIdx/views/${id}_mask.png"
        Imgcodecs.imwrite(File(dir, "$id.png").absolutePath, canonicalBgr)
        Imgcodecs.imwrite(File(dir, "${id}_mask.png").absolutePath, mask)
        val v = TemplateView(id = id, file = rel, maskFile = relMask, flash = flash, note = note)
        part.views.add(v)
        save(product)
        return v
    }

    fun removeView(product: Product, partIdx: Int, viewId: String) {
        val part = product.parts[partIdx]
        val v = part.views.firstOrNull { it.id == viewId } ?: return
        File(productDir(product.id), v.file).delete()
        File(productDir(product.id), v.maskFile).delete()
        part.views.remove(v)
        save(product)
    }

    fun loadViewImage(product: Product, view: TemplateView): Mat =
        Imgcodecs.imread(File(productDir(product.id), view.file).absolutePath, Imgcodecs.IMREAD_COLOR)

    fun loadViewMask(product: Product, view: TemplateView): Mat =
        Imgcodecs.imread(File(productDir(product.id), view.maskFile).absolutePath, Imgcodecs.IMREAD_GRAYSCALE)

    /** 全製品を zip にまとめる(PC 解析・バックアップ用)。 */
    fun exportAll(dest: File) {
        ZipOutputStream(FileOutputStream(dest)).use { zos ->
            root.walkTopDown().filter { it.isFile }.forEach { f ->
                zos.putNextEntry(ZipEntry("products/" + f.relativeTo(root).path.replace(File.separatorChar, '/')))
                f.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
    }

    /** exportAll の zip を取り込む(同一 id は上書き)。 */
    fun importAll(zip: File) {
        ZipInputStream(zip.inputStream()).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                if (!e.isDirectory && e.name.startsWith("products/")) {
                    val out = File(root, e.name.removePrefix("products/"))
                    if (out.canonicalPath.startsWith(root.canonicalPath)) {
                        out.parentFile?.mkdirs()
                        FileOutputStream(out).use { zis.copyTo(it) }
                    }
                }
                e = zis.nextEntry
            }
        }
    }

    companion object {
        fun newProduct(name: String, kind: jp.hirameq.handycam.model.ProductKind): Product {
            val p = Product(name = name, kind = kind)
            when (kind) {
                jp.hirameq.handycam.model.ProductKind.FOAM_PAIR -> {
                    p.background = jp.hirameq.handycam.model.BackgroundKind.BLACK
                    p.parts += PartTemplate("パーツA")
                    p.parts += PartTemplate("パーツB")
                }
                jp.hirameq.handycam.model.ProductKind.METAL_IN_BOX -> {
                    p.background = jp.hirameq.handycam.model.BackgroundKind.GRAY_BOX
                    p.parts += PartTemplate("本体")
                }
                jp.hirameq.handycam.model.ProductKind.SINGLE -> {
                    p.parts += PartTemplate("本体")
                }
            }
            return p
        }
    }
}
