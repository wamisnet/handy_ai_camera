package jp.hirameq.handycam.imaging

import org.opencv.core.Mat

/** 撮影された 1 フレーム。bgr は 8UC3。 */
data class Frame(
    val bgr: Mat,
    val flash: Boolean,
    val index: Int,
    val capturedAt: Long = System.currentTimeMillis(),
) {
    fun release() = bgr.release()
}
