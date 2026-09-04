package jp.hirameq.handycam

import android.app.Application
import jp.hirameq.handycam.match.EmbeddingMatcher
import jp.hirameq.handycam.store.ProductStore
import jp.hirameq.handycam.store.SettingsStore
import jp.hirameq.handycam.verify.TemplateLibrary
import org.opencv.android.OpenCVLoader
import java.io.File

class App : Application() {
    lateinit var store: ProductStore
    lateinit var settingsStore: SettingsStore
    lateinit var library: TemplateLibrary
    var opencvReady = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        opencvReady = OpenCVLoader.initLocal()
        store = ProductStore(this)
        settingsStore = SettingsStore(this)
        library = TemplateLibrary(store)
        EmbeddingMatcher.modelFile = File(File(filesDir, "models").apply { mkdirs() }, "embedding.tflite")
    }

    val debugDir: File get() = File(cacheDir, "debug")

    companion object {
        lateinit var instance: App
    }
}
