package jp.hirameq.handycam.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import jp.hirameq.handycam.model.Roi

/** 正規化画像の上でドラッグして ROI(矩形)を描く View。座標は 0..1 正規化で保持。 */
class RoiView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    var bitmap: Bitmap? = null
        set(v) { field = v; invalidate() }
    val rois = ArrayList<Roi>()
    var onChanged: (() -> Unit)? = null
    private var dragStart: Pair<Float, Float>? = null
    private var dragCur: Pair<Float, Float>? = null
    private val paintImg = Paint(Paint.FILTER_BITMAP_FLAG)
    private val paintRoi = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 4f; color = Color.YELLOW }
    private val paintDrag = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 3f; color = Color.CYAN }
    private val paintText = Paint().apply { color = Color.YELLOW; textSize = 32f; isFakeBoldText = true }

    private fun imageRect(): RectF {
        val bmp = bitmap ?: return RectF(0f, 0f, width.toFloat(), height.toFloat())
        val s = minOf(width / bmp.width.toFloat(), height / bmp.height.toFloat())
        val w = bmp.width * s; val h = bmp.height * s
        val x = (width - w) / 2; val y = (height - h) / 2
        return RectF(x, y, x + w, y + h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.DKGRAY)
        val r = imageRect()
        bitmap?.let { canvas.drawBitmap(it, null, r, paintImg) }
        for ((i, roi) in rois.withIndex()) {
            val rr = RectF(r.left + roi.x * r.width(), r.top + roi.y * r.height(), r.left + (roi.x + roi.w) * r.width(), r.top + (roi.y + roi.h) * r.height())
            canvas.drawRect(rr, paintRoi)
            canvas.drawText("#${i + 1} ×${roi.weight}", rr.left + 6, rr.top + 34, paintText)
        }
        val s = dragStart; val c = dragCur
        if (s != null && c != null) canvas.drawRect(minOf(s.first, c.first), minOf(s.second, c.second), maxOf(s.first, c.first), maxOf(s.second, c.second), paintDrag)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { dragStart = e.x to e.y; dragCur = dragStart; parent.requestDisallowInterceptTouchEvent(true) }
            MotionEvent.ACTION_MOVE -> { dragCur = e.x to e.y }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val s = dragStart; val c = e.x to e.y
                dragStart = null; dragCur = null
                if (s != null) {
                    val r = imageRect()
                    val x0 = ((minOf(s.first, c.first) - r.left) / r.width()).coerceIn(0f, 1f)
                    val y0 = ((minOf(s.second, c.second) - r.top) / r.height()).coerceIn(0f, 1f)
                    val x1 = ((maxOf(s.first, c.first) - r.left) / r.width()).coerceIn(0f, 1f)
                    val y1 = ((maxOf(s.second, c.second) - r.top) / r.height()).coerceIn(0f, 1f)
                    if (x1 - x0 > 0.03f && y1 - y0 > 0.03f) { rois += Roi(x0, y0, x1 - x0, y1 - y0); onChanged?.invoke() }
                }
            }
        }
        invalidate()
        return true
    }
}
