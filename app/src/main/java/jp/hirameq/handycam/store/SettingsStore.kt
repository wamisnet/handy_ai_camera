package jp.hirameq.handycam.store

import android.content.Context
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.InstanceCreator
import jp.hirameq.handycam.model.AppSettings
import jp.hirameq.handycam.model.MethodConfig
import jp.hirameq.handycam.model.MethodId
import jp.hirameq.handycam.model.PartTemplate
import jp.hirameq.handycam.model.Product
import jp.hirameq.handycam.model.Roi
import jp.hirameq.handycam.model.TemplateView

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("handycam", Context.MODE_PRIVATE)
    private val gson = gsonWithDefaults()

    fun load(): AppSettings {
        val json = prefs.getString(KEY, null) ?: return AppSettings()
        val s = runCatching { gson.fromJson(json, AppSettings::class.java) }.getOrNull() ?: AppSettings()
        // 新しい手法が追加された場合の後方互換
        AppSettings.defaultMethods().forEach { (k, v) -> s.methods.putIfAbsent(k, v) }
        return s
    }

    fun save(s: AppSettings) {
        prefs.edit().putString(KEY, gson.toJson(s)).apply()
    }

    fun reset() = prefs.edit().remove(KEY).apply()

    companion object {
        private const val KEY = "settings_json"

        /**
         * Gson は既定では Unsafe でインスタンス化し Kotlin の既定値を通さないため、古い JSON に無いフィールドが null になる。
         * InstanceCreator でコンストラクタ既定値を持つインスタンスに上書きする形にして後方互換を保つ。
         */
        fun gsonWithDefaults(pretty: Boolean = false): Gson = GsonBuilder().apply {
            registerTypeAdapter(AppSettings::class.java, InstanceCreator { AppSettings() })
            registerTypeAdapter(MethodConfig::class.java, InstanceCreator { MethodConfig() })
            registerTypeAdapter(Product::class.java, InstanceCreator { Product(name = "") })
            registerTypeAdapter(PartTemplate::class.java, InstanceCreator { PartTemplate(name = "") })
            registerTypeAdapter(TemplateView::class.java, InstanceCreator { TemplateView(file = "", maskFile = "", flash = false) })
            registerTypeAdapter(Roi::class.java, InstanceCreator { Roi(0f, 0f, 0f, 0f) })
            if (pretty) setPrettyPrinting()
        }.create()
    }
}
