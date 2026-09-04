package jp.hirameq.handycam.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.Bundle
import android.util.Size
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import jp.hirameq.handycam.App
import jp.hirameq.handycam.R
import jp.hirameq.handycam.databinding.ActivityCaptureBinding
import jp.hirameq.handycam.imaging.Frame
import jp.hirameq.handycam.imaging.ImageConvert
import jp.hirameq.handycam.imaging.Segmenter
import jp.hirameq.handycam.model.FlashStep
import jp.hirameq.handycam.model.Product
import jp.hirameq.handycam.util.toast
import jp.hirameq.handycam.verify.VerificationEngine
import jp.hirameq.handycam.verify.VerifyLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * ライブプレビュー + バースト撮影。
 * - プレビュー中は解析ストリームを間引いてセグメンテーションし、輪郭と検出数を重畳表示する(構図・距離の確認用)。
 * - 撮影ボタンで設定のフラッシュシーケンス(例: OFF→ON)に従いトーチを切り替え、各ステップで N フレーム取得。
 * - REGISTER: 各ステップの最後のフレームから物体を切り出し、テンプレートビューとして保存。
 * - VERIFY  : 全フレームを VerificationEngine に渡し、結果画面へ。
 */
class CaptureActivity : AppCompatActivity() {
    private lateinit var b: ActivityCaptureBinding
    private val app get() = application as App
    private lateinit var product: Product
    private var mode = MODE_VERIFY
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private var camera: Camera? = null
    private val settings by lazy { app.settingsStore.load() }

    // 解析フレーム受け渡し
    @Volatile private var frameSink: ((Frame) -> Unit)? = null
    private val capturing = AtomicBoolean(false)
    private val frameCounter = AtomicInteger(0)
    private var torchOnPreview = false

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startCamera() else { toast(getString(R.string.camera_permission)); finish() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityCaptureBinding.inflate(layoutInflater)
        setContentView(b.root)
        val id = intent.getStringExtra(EXTRA_PRODUCT_ID) ?: run { finish(); return }
        product = app.store.load(id)
        mode = intent.getIntExtra(EXTRA_MODE, MODE_VERIFY)
        b.title.text = (if (mode == MODE_REGISTER) "登録: " else "検査: ") + product.name
        b.hint.text = if (mode == MODE_REGISTER) registerHint() else "製品を枠内に収め、背景だけが写るようにして撮影"
        b.previewView.scaleType = PreviewView.ScaleType.FIT_CENTER
        b.btnCapture.setOnClickListener { onCapture() }
        b.btnTorch.setOnClickListener {
            torchOnPreview = !torchOnPreview
            camera?.cameraControl?.enableTorch(torchOnPreview)
        }
        b.btnDone.visibility = if (mode == MODE_REGISTER) View.VISIBLE else View.GONE
        b.btnDone.setOnClickListener { finish() }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else permission.launch(Manifest.permission.CAMERA)
    }

    private fun registerHint(): String = when (product.parts.size) {
        1 -> "物体を 1 つだけ置いて撮影。距離・角度を変えて 3〜5 回登録してください"
        else -> "パーツを ${product.parts.map { it.name }.joinToString("→")} の順に左から並べて撮影(初回のみ順序が使われます)。距離・角度を変えて 3〜5 回登録"
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(b.previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(ResolutionSelector.Builder()
                    .setResolutionStrategy(ResolutionStrategy(Size(1280, 960), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)).build())
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()
            analysis.setAnalyzer(analysisExecutor) { img -> analyze(img) }
            provider.unbindAll()
            camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            b.btnTorch.isEnabled = camera?.cameraInfo?.hasFlashUnit() == true
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyze(img: ImageProxy) {
        try {
            val sink = frameSink
            if (sink != null) {
                val mat = ImageConvert.imageProxyToBgr(img, settings.analysisLongEdge)
                sink(Frame(mat, torchState, frameCounter.getAndIncrement()))
                return
            }
            if (capturing.get()) return
            // プレビュー用: 3 フレームに 1 回、半分の解像度でセグメンテーション
            if (frameCounter.getAndIncrement() % 3 != 0) return
            val mat = ImageConvert.imageProxyToBgr(img, settings.analysisLongEdge / 2)
            val seg = Segmenter(settings).segment(Frame(mat, torchState, 0), null, product.background, product.parts.size)
            val bmp = Bitmap.createBitmap(mat.cols(), mat.rows(), Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val ok = seg.objects.size == product.parts.size
            val paint = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 4f; color = if (ok) Color.GREEN else Color.RED; isAntiAlias = true }
            for (o in seg.objects) {
                val pts = o.contour.toArray()
                if (pts.isEmpty()) continue
                val path = Path().apply { moveTo(pts[0].x.toFloat(), pts[0].y.toFloat()); for (p in pts) lineTo(p.x.toFloat(), p.y.toFloat()); close() }
                c.drawPath(path, paint)
            }
            val count = seg.objects.size
            seg.release(); mat.release()
            runOnUiThread {
                b.overlay.setImageBitmap(bmp)
                b.status.text = "検出 $count / 期待 ${product.parts.size}"
                b.status.setTextColor(if (ok) Color.GREEN else Color.YELLOW)
            }
        } catch (e: Throwable) {
            runOnUiThread { b.status.text = "解析エラー: ${e.message}" }
        } finally {
            img.close()
        }
    }

    @Volatile private var torchState = false

    private suspend fun setTorch(on: Boolean) {
        withContext(Dispatchers.Main) { camera?.cameraControl?.enableTorch(on) }
        torchState = on
        delay(settings.settleMillis)
    }

    /** 設定のフラッシュシーケンスに従いフレームを収集。 */
    private suspend fun burst(): List<Frame> {
        val frames = ArrayList<Frame>()
        val steps = settings.flashSequence.ifEmpty { listOf(FlashStep.OFF) }
        val hasFlash = camera?.cameraInfo?.hasFlashUnit() == true
        for (step in steps) {
            val on = step == FlashStep.ON && hasFlash
            setTorch(on)
            // 直近フレームを捨て、AE 安定後の N 枚を集める
            var got = 0
            var skip = 1
            val need = settings.framesPerStep
            val collected = ArrayList<Frame>()
            val lock = Object()
            frameSink = { f ->
                synchronized(lock) {
                    if (skip > 0) { skip--; f.release() }
                    else if (got < need) { collected += f; got++ }
                    else f.release()
                    lock.notifyAll()
                }
            }
            withTimeoutOrNull(4000) {
                while (true) { synchronized(lock) { if (got >= need) return@withTimeoutOrNull }; delay(30) }
            }
            frameSink = null
            // 以降に届いたフレームは sink 内で release される(got>=need)。コピーはロック下で行う
            synchronized(lock) { frames += collected; got = need }
        }
        setTorch(torchOnPreview)
        return frames
    }

    private fun onCapture() {
        if (!capturing.compareAndSet(false, true)) return
        b.btnCapture.isEnabled = false
        b.progress.visibility = View.VISIBLE
        b.status.text = getString(R.string.processing)
        lifecycleScope.launch {
            val frames = burst()
            if (frames.isEmpty()) { finishCapture("フレームを取得できませんでした"); return@launch }
            try {
                withContext(Dispatchers.Default) {
                    if (mode == MODE_REGISTER) register(frames) else verify(frames)
                }
            } catch (e: Throwable) {
                finishCapture("エラー: ${e.message}")
            } finally {
                frames.forEach { it.release() }
            }
        }
    }

    private fun finishCapture(msg: String?) {
        runOnUiThread {
            b.progress.visibility = View.GONE
            b.btnCapture.isEnabled = true
            capturing.set(false)
            msg?.let { b.status.text = it; toast(it) }
        }
    }

    private fun verify(frames: List<Frame>) {
        val engine = VerificationEngine(app.library, settings, app.debugDir.also { it.deleteRecursively() })
        val result = engine.verify(frames, product)
        VerifyLog(this).append(result)
        ResultActivity.lastResult = result
        runOnUiThread {
            finishCapture(null)
            startActivity(Intent(this, ResultActivity::class.java))
        }
    }

    /**
     * 登録: フラッシュステップごとに最後のフレームを使い、期待数の物体が取れたものだけ保存。
     * 既存ビューがあれば パーツ割り当て・向きを既存に揃える。
     */
    private fun register(frames: List<Frame>) {
        val engine = VerificationEngine(app.library, settings, null)
        val expected = product.parts.size
        val extracted = engine.extract(frames, product, expected, makeOverlay = false)
        // ステップ(flash 有/無)ごとに最後の採用可能フレーム
        val chosen = extracted.filter { it.queries.isNotEmpty() }.groupBy { it.frame.flash }.mapNotNull { it.value.lastOrNull() }
        if (chosen.isEmpty()) {
            extracted.forEach { fo -> fo.queries.flatten().forEach { it.release() } }
            finishCapture("検出数が ${expected} 個になりませんでした。背景・距離を調整してください"); return
        }
        var added = 0
        val fresh = app.store.load(product.id)
        for (fo in chosen) {
            val parts = app.library.partsOf(fresh.id)
            val base = fo.queries.map { it.first() }
            val assign = VerificationEngine.assignForRegistration(fo.queries, parts, settings)
            for ((pi, si) in assign.withIndex()) {
                val oriented = VerificationEngine.orientForRegistration(base[si], parts.getOrNull(pi))
                app.store.addView(fresh, pi, oriented.bgr, oriented.mask, fo.frame.flash)
                added++
            }
            app.library.invalidate()
        }
        extracted.forEach { fo -> fo.queries.flatten().forEach { it.release() } }
        product = fresh
        finishCapture("ビューを ${added} 枚登録しました(合計 ${fresh.viewCount})。距離・角度を変えてもう一度")
    }

    override fun onDestroy() { super.onDestroy(); analysisExecutor.shutdown() }

    companion object {
        const val EXTRA_PRODUCT_ID = "product_id"
        const val EXTRA_MODE = "mode"
        const val MODE_VERIFY = 0
        const val MODE_REGISTER = 1
    }
}
