package jp.hirameq.handycam.imaging

import android.graphics.Bitmap
import android.graphics.ImageFormat
import androidx.camera.core.ImageProxy
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.nio.ByteBuffer

object ImageConvert {

    /**
     * CameraX の YUV_420_888 を BGR Mat に変換し、回転補正と長辺リサイズを行う。
     * 端末により U/V が interleaved(NV21/NV12 相当) か planar かが異なるので pixelStride で分岐。
     */
    fun imageProxyToBgr(image: ImageProxy, longEdge: Int): Mat {
        require(image.format == ImageFormat.YUV_420_888)
        val w = image.width
        val h = image.height
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val nv21 = ByteArray(w * h * 3 / 2)
        // Y
        copyPlane(yPlane.buffer, yPlane.rowStride, yPlane.pixelStride, w, h, nv21, 0, 1)
        // VU interleaved (NV21)
        val chromaW = w / 2
        val chromaH = h / 2
        if (uPlane.pixelStride == 2 && vPlane.pixelStride == 2) {
            // 半平面 interleaved: V バッファは通常 U より 1 バイト先行 → V を基準に読むと VUVU...
            val vBuf = vPlane.buffer
            val uBuf = uPlane.buffer
            var out = w * h
            for (row in 0 until chromaH) {
                val vRow = row * vPlane.rowStride
                val uRow = row * uPlane.rowStride
                for (col in 0 until chromaW) {
                    nv21[out++] = vBuf.get(vRow + col * 2)
                    nv21[out++] = uBuf.get(uRow + col * 2)
                }
            }
        } else {
            val vBuf = vPlane.buffer
            val uBuf = uPlane.buffer
            var out = w * h
            for (row in 0 until chromaH) {
                val vRow = row * vPlane.rowStride
                val uRow = row * uPlane.rowStride
                for (col in 0 until chromaW) {
                    nv21[out++] = vBuf.get(vRow + col * vPlane.pixelStride)
                    nv21[out++] = uBuf.get(uRow + col * uPlane.pixelStride)
                }
            }
        }
        val yuv = Mat(h + h / 2, w, CvType.CV_8UC1)
        yuv.put(0, 0, nv21)
        val bgr = Mat()
        Imgproc.cvtColor(yuv, bgr, Imgproc.COLOR_YUV2BGR_NV21)
        yuv.release()

        val rotated = rotate(bgr, image.imageInfo.rotationDegrees)
        if (rotated !== bgr) bgr.release()
        return resizeLongEdge(rotated, longEdge, releaseSrc = true)
    }

    private fun copyPlane(buf: ByteBuffer, rowStride: Int, pixelStride: Int, w: Int, h: Int, out: ByteArray, offset: Int, step: Int) {
        var o = offset
        if (pixelStride == 1 && rowStride == w) {
            buf.rewind(); buf.get(out, offset, w * h); return
        }
        val row = ByteArray(rowStride)
        for (r in 0 until h) {
            buf.position(r * rowStride)
            val len = minOf(rowStride, buf.remaining())
            buf.get(row, 0, len)
            for (c in 0 until w) { out[o] = row[c * pixelStride]; o += step }
        }
    }

    fun rotate(src: Mat, degrees: Int): Mat {
        val dst = Mat()
        when ((degrees % 360 + 360) % 360) {
            90 -> Core.rotate(src, dst, Core.ROTATE_90_CLOCKWISE)
            180 -> Core.rotate(src, dst, Core.ROTATE_180)
            270 -> Core.rotate(src, dst, Core.ROTATE_90_COUNTERCLOCKWISE)
            else -> return src
        }
        return dst
    }

    fun resizeLongEdge(src: Mat, longEdge: Int, releaseSrc: Boolean = false): Mat {
        val cur = maxOf(src.cols(), src.rows())
        if (cur <= longEdge) return src
        val s = longEdge.toDouble() / cur
        val dst = Mat()
        Imgproc.resize(src, dst, Size(), s, s, Imgproc.INTER_AREA)
        if (releaseSrc) src.release()
        return dst
    }

    fun toBitmap(bgr: Mat): Bitmap {
        val rgba = Mat()
        when (bgr.channels()) {
            1 -> Imgproc.cvtColor(bgr, rgba, Imgproc.COLOR_GRAY2RGBA)
            3 -> Imgproc.cvtColor(bgr, rgba, Imgproc.COLOR_BGR2RGBA)
            else -> bgr.copyTo(rgba)
        }
        val bmp = Bitmap.createBitmap(rgba.cols(), rgba.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(rgba, bmp)
        rgba.release()
        return bmp
    }
}
