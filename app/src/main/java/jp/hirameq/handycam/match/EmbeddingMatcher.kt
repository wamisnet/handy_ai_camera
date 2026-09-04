package jp.hirameq.handycam.match

import jp.hirameq.handycam.imaging.CanonicalImage
import jp.hirameq.handycam.model.AppSettings
import jp.hirameq.handycam.model.MethodId
import jp.hirameq.handycam.model.MethodScore
import jp.hirameq.handycam.model.Roi
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * CNN 埋め込みベクトルのコサイン類似度(任意)。
 * files/models/embedding.tflite(入力 1x224x224x3 float32 [-1,1], 出力 1xD)を端末に置いたときだけ有効。
 * MobileNetV3 等の ImageNet 学習済み特徴を使えば、照明・多少の形状差に対してロバストな
 * 「全体として同じ物か」の判定ができる。細かな押印の違いには鈍いので他手法との併用前提。
 */
class EmbeddingMatcher : Matcher {
    override val id = MethodId.EMBEDDING
    override val orientationSensitive = true
    override val cost = 2

    companion object {
        var modelFile: File? = null
        private var interpreter: Any? = null
        private var inputSize = 224
        private var outDim = 0

        @Synchronized
        fun available(): Boolean {
            val f = modelFile ?: return false
            if (interpreter != null) return true
            if (!f.exists()) return false
            return try {
                val itp = org.tensorflow.lite.Interpreter(f)
                val inShape = itp.getInputTensor(0).shape()
                inputSize = inShape[1]
                outDim = itp.getOutputTensor(0).shape().last()
                interpreter = itp
                true
            } catch (e: Throwable) { false }
        }

        @Synchronized
        fun embed(bgr: Mat): FloatArray? {
            if (!available()) return null
            val itp = interpreter as org.tensorflow.lite.Interpreter
            val rgb = Mat()
            Imgproc.cvtColor(bgr, rgb, Imgproc.COLOR_BGR2RGB)
            Imgproc.resize(rgb, rgb, Size(inputSize.toDouble(), inputSize.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
            val bytes = ByteArray(inputSize * inputSize * 3)
            rgb.get(0, 0, bytes); rgb.release()
            val buf = ByteBuffer.allocateDirect(4 * bytes.size).order(ByteOrder.nativeOrder())
            for (b in bytes) buf.putFloat((b.toInt() and 0xFF) / 127.5f - 1f)
            buf.rewind()
            val out = Array(1) { FloatArray(outDim) }
            itp.run(buf, out)
            return out[0]
        }
    }

    private fun vec(img: CanonicalImage): FloatArray? = img.cached("embedding") { embed(img.bgr) ?: FloatArray(0) }.takeIf { it.isNotEmpty() }

    override fun compare(query: CanonicalImage, template: CanonicalImage, rois: List<Roi>, settings: AppSettings): MethodScore {
        if (!available()) return MethodScore(id, 0f, "model not installed", unavailable = true)
        val a = vec(query) ?: return MethodScore(id, 0f, "embed failed", unavailable = true)
        val b = vec(template) ?: return MethodScore(id, 0f, "embed failed", unavailable = true)
        var dot = 0.0; var na = 0.0; var nb = 0.0
        for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        val cos = dot / maxOf(sqrt(na * nb), 1e-9)
        // コサインは [-1,1] → [0,1]
        return MethodScore(id, ((cos + 1) / 2).toFloat().coerceIn(0f, 1f), "cos=%.3f".format(cos))
    }
}
