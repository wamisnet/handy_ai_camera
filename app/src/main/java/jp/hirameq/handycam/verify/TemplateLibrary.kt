package jp.hirameq.handycam.verify

import jp.hirameq.handycam.imaging.CanonicalImage
import jp.hirameq.handycam.model.Product
import jp.hirameq.handycam.model.TemplateView
import jp.hirameq.handycam.store.ProductStore

/** 登録済みの 1 ビューをメモリ上に展開したもの。各手法の記述子は CanonicalImage 側にキャッシュされる。 */
class LoadedView(val product: Product, val partIndex: Int, val view: TemplateView, val image: CanonicalImage)

class LoadedPart(val product: Product, val partIndex: Int, val views: List<LoadedView>) {
    val part get() = product.parts[partIndex]
    val key: String get() = "${product.id}/$partIndex"
    val displayName: String get() = "${product.name} / ${part.name}"
}

/**
 * 全製品のテンプレートを読み込んで保持。記述子計算はアクセス時に遅延評価され、以後キャッシュされる。
 * 製品追加・削除後は invalidate() する。
 */
class TemplateLibrary(private val store: ProductStore) {
    private var parts: List<LoadedPart>? = null

    @Synchronized
    fun parts(): List<LoadedPart> {
        parts?.let { return it }
        val list = ArrayList<LoadedPart>()
        for (p in store.list()) {
            for ((i, part) in p.parts.withIndex()) {
                val views = part.views.mapNotNull { v ->
                    val bgr = store.loadViewImage(p, v)
                    val mask = store.loadViewMask(p, v)
                    if (bgr.empty() || mask.empty()) null else LoadedView(p, i, v, CanonicalImage(bgr, mask))
                }
                list += LoadedPart(p, i, views)
            }
        }
        parts = list
        return list
    }

    @Synchronized
    fun invalidate() {
        parts?.forEach { lp -> lp.views.forEach { it.image.release() } }
        parts = null
    }

    fun partsOf(productId: String): List<LoadedPart> = parts().filter { it.product.id == productId }
    fun partsNotOf(productId: String): List<LoadedPart> = parts().filter { it.product.id != productId && it.views.isNotEmpty() }
}
