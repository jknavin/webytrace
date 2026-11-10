package com.artrace.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Draws a reference image over the camera preview. One finger moves it,
 * two fingers scale + rotate it. Can show the image as a green outline.
 */
class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var source: Bitmap? = null
    private var outline: Bitmap? = null
    private var outlineToken = 0
    private var needsFit = false

    private val xform = Matrix()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply { alpha = 128 }

    /** 0.05 .. 1.0 */
    var imageAlpha: Float = 0.5f
        set(value) {
            field = value.coerceIn(0.05f, 1f)
            paint.alpha = (field * 255).toInt()
            invalidate()
        }

    /** When true, touches are ignored so the image can't be moved by accident. */
    var locked: Boolean = false

    var outlineMode: Boolean = false
        set(value) {
            field = value
            if (value) ensureOutline()
            invalidate()
        }

    val hasImage: Boolean
        get() = source != null

    fun setImage(bmp: Bitmap?) {
        source = bmp
        outline = null
        outlineToken++
        if (bmp == null) {
            invalidate()
            return
        }
        fit()
        if (outlineMode) ensureOutline()
    }

    fun clearImage() = setImage(null)

    fun resetTransform() = fit()

    fun flip() {
        val s = source ?: return
        val pts = floatArrayOf(s.width / 2f, s.height / 2f)
        xform.mapPoints(pts)
        xform.postScale(-1f, 1f, pts[0], pts[1])
        invalidate()
    }

    private fun fit() {
        val s = source ?: return
        if (width == 0 || height == 0) {
            needsFit = true
            return
        }
        xform.setRectToRect(
            RectF(0f, 0f, s.width.toFloat(), s.height.toFloat()),
            RectF(0f, 0f, width.toFloat(), height.toFloat()),
            Matrix.ScaleToFit.CENTER
        )
        needsFit = false
        invalidate()
    }

    private fun ensureOutline() {
        val src = source ?: return
        if (outline != null) return
        val token = ++outlineToken
        Thread {
            val result = try {
                EdgeFilter.outline(src)
            } catch (e: Throwable) {
                null
            }
            post {
                if (token == outlineToken && source === src) {
                    outline = result
                    invalidate()
                }
            }
        }.start()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (needsFit) fit()
    }

    override fun onDraw(canvas: Canvas) {
        val bmp = (if (outlineMode) outline else null) ?: source ?: return
        canvas.drawBitmap(bmp, xform, paint)
    }

    // ---- touch handling ----

    private var gesture = 0 // 0 none, 1 drag, 2 pinch
    private var lastX = 0f
    private var lastY = 0f
    private var lastDist = 0f
    private var lastAngle = 0f
    private var lastMx = 0f
    private var lastMy = 0f

    private fun startPinch(e: MotionEvent) {
        val dx = e.getX(1) - e.getX(0)
        val dy = e.getY(1) - e.getY(0)
        lastDist = hypot(dx, dy)
        lastAngle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
        lastMx = (e.getX(0) + e.getX(1)) / 2f
        lastMy = (e.getY(0) + e.getY(1)) / 2f
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (source == null || locked) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = e.x
                lastY = e.y
                gesture = 1
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (e.pointerCount >= 2) {
                    startPinch(e)
                    gesture = 2
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (gesture == 1 && e.pointerCount == 1) {
                    xform.postTranslate(e.x - lastX, e.y - lastY)
                    lastX = e.x
                    lastY = e.y
                } else if (gesture == 2 && e.pointerCount >= 2) {
                    val dx = e.getX(1) - e.getX(0)
                    val dy = e.getY(1) - e.getY(0)
                    val dist = hypot(dx, dy)
                    val angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
                    val mx = (e.getX(0) + e.getX(1)) / 2f
                    val my = (e.getY(0) + e.getY(1)) / 2f
                    if (lastDist > 10f && dist > 10f) {
                        val s = dist / lastDist
                        xform.postScale(s, s, mx, my)
                        var da = angle - lastAngle
                        if (da > 180f) da -= 360f else if (da < -180f) da += 360f
                        xform.postRotate(da, mx, my)
                    }
                    xform.postTranslate(mx - lastMx, my - lastMy)
                    lastDist = dist
                    lastAngle = angle
                    lastMx = mx
                    lastMy = my
                }
                invalidate()
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (e.pointerCount - 1 == 1) {
                    val keep = if (e.actionIndex == 0) 1 else 0
                    lastX = e.getX(keep)
                    lastY = e.getY(keep)
                    gesture = 1
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> gesture = 0
        }
        return true
    }
}

/** Simple Sobel edge detector: returns a transparent bitmap with green lines. */
internal object EdgeFilter {

    fun outline(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)

        // grayscale, composited on white
        val gray = IntArray(w * h)
        for (i in px.indices) {
            val p = px[i]
            val a = p ushr 24
            val r = (p shr 16) and 255
            val g = (p shr 8) and 255
            val b = p and 255
            val lum = (r * 299 + g * 587 + b * 114) / 1000
            gray[i] = (lum * a + 255 * (255 - a)) / 255
        }

        // 3x3 box blur to calm down noise
        val blur = gray.copyOf()
        for (y in 1 until h - 1) {
            val row = y * w
            for (x in 1 until w - 1) {
                val i = row + x
                blur[i] = (gray[i - w - 1] + gray[i - w] + gray[i - w + 1] +
                    gray[i - 1] + gray[i] + gray[i + 1] +
                    gray[i + w - 1] + gray[i + w] + gray[i + w + 1]) / 9
            }
        }

        // Sobel -> alpha map (lines are thickened by one pixel right/down)
        val alpha = IntArray(w * h)
        for (y in 1 until h - 1) {
            val row = y * w
            for (x in 1 until w - 1) {
                val i = row + x
                val tl = blur[i - w - 1]
                val t = blur[i - w]
                val tr = blur[i - w + 1]
                val l = blur[i - 1]
                val r = blur[i + 1]
                val bl = blur[i + w - 1]
                val bm = blur[i + w]
                val br = blur[i + w + 1]
                val gx = -tl - 2 * l - bl + tr + 2 * r + br
                val gy = -tl - 2 * t - tr + bl + 2 * bm + br
                val mag = abs(gx) + abs(gy)
                val a = ((mag - 60) * 255 / 240).coerceIn(0, 255)
                if (a > 0) {
                    if (a > alpha[i]) alpha[i] = a
                    if (a > alpha[i + 1]) alpha[i + 1] = a
                    if (a > alpha[i + w]) alpha[i + w] = a
                }
            }
        }

        val out = IntArray(w * h)
        for (i in out.indices) {
            val a = alpha[i]
            if (a > 0) out[i] = (a shl 24) or 0x0000E676
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }
}
