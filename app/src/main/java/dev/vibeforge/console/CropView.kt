package dev.vibeforge.console

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View

/**
 * Pinch and drag to frame an icon.
 *
 * The first version used three sliders, which is the kind of thing that looks
 * reasonable in code and is miserable in the hand: you cannot see what you are
 * doing while dragging a bar under the picture. Here the picture moves under a
 * fixed square, which is how every crop tool on a phone works and therefore
 * the only thing that needs no explaining.
 *
 * The image is positioned by a Matrix rather than by regenerating bitmaps, so
 * dragging stays smooth on a large photo, and the crop is read back out of
 * that matrix at the end.
 */
@SuppressLint("ViewConstructor")
class CropView(context: Context, private val source: Bitmap) : View(context) {

    private val matrix = Matrix()
    private val dim = Paint().apply { color = Color.argb(150, 0, 0, 0) }
    private val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val guide = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(120, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }

    private var scale = 1f
    private var offsetX = 0f
    private var offsetY = 0f
    private var initialised = false

    /** The square the crop is taken from, in view coordinates. */
    private val viewport = RectF()

    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val factor = detector.scaleFactor
                val next = (scale * factor).coerceIn(minScale(), minScale() * 8f)
                // Zoom towards the fingers rather than the top-left corner,
                // or the picture slides away from wherever you are looking.
                val fx = detector.focusX
                val fy = detector.focusY
                offsetX = fx - (fx - offsetX) * (next / scale)
                offsetY = fy - (fy - offsetY) * (next / scale)
                scale = next
                clamp()
                invalidate()
                return true
            }
        })

    private fun minScale(): Float {
        if (viewport.width() <= 0f) return 1f
        // Never let the picture be smaller than the square, or the crop would
        // include empty space.
        return maxOf(viewport.width() / source.width, viewport.height() / source.height)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val side = minOf(w, h) * 0.82f
        viewport.set((w - side) / 2f, (h - side) / 2f, (w + side) / 2f, (h + side) / 2f)
        if (!initialised) {
            scale = minScale()
            offsetX = viewport.centerX() - source.width * scale / 2f
            offsetY = viewport.centerY() - source.height * scale / 2f
            initialised = true
        }
        clamp()
    }

    /** Keep the picture covering the square at all times. */
    private fun clamp() {
        val w = source.width * scale
        val h = source.height * scale
        offsetX = offsetX.coerceIn(viewport.right - w, viewport.left)
        offsetY = offsetY.coerceIn(viewport.bottom - h, viewport.top)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x; lastY = event.y; dragging = true
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging && !scaleDetector.isInProgress) {
                    offsetX += event.x - lastX
                    offsetY += event.y - lastY
                    lastX = event.x; lastY = event.y
                    clamp()
                    invalidate()
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // A second finger means a pinch is starting; drop the drag
                // anchor so the image does not jump when it ends.
                dragging = false
            }
            MotionEvent.ACTION_POINTER_UP -> {
                lastX = event.x; lastY = event.y; dragging = true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        matrix.reset()
        matrix.postScale(scale, scale)
        matrix.postTranslate(offsetX, offsetY)
        canvas.drawBitmap(source, matrix, null)

        // Darken everything outside the square so the crop reads at a glance.
        canvas.drawRect(0f, 0f, width.toFloat(), viewport.top, dim)
        canvas.drawRect(0f, viewport.bottom, width.toFloat(), height.toFloat(), dim)
        canvas.drawRect(0f, viewport.top, viewport.left, viewport.bottom, dim)
        canvas.drawRect(viewport.right, viewport.top, width.toFloat(), viewport.bottom, dim)

        canvas.drawRect(viewport, frame)

        // Thirds, and the circle a launcher will mask to.
        val third = viewport.width() / 3f
        for (i in 1..2) {
            canvas.drawLine(viewport.left + third * i, viewport.top,
                viewport.left + third * i, viewport.bottom, guide)
            canvas.drawLine(viewport.left, viewport.top + third * i,
                viewport.right, viewport.top + third * i, guide)
        }
        canvas.drawCircle(viewport.centerX(), viewport.centerY(), viewport.width() / 2f, guide)
    }

    /** The chosen square, in the source image's own pixels. */
    fun cropRect(): Rect {
        val left = ((viewport.left - offsetX) / scale).toInt()
        val top = ((viewport.top - offsetY) / scale).toInt()
        val side = (viewport.width() / scale).toInt()
        val safeSide = side.coerceAtMost(minOf(source.width, source.height)).coerceAtLeast(16)
        val safeLeft = left.coerceIn(0, source.width - safeSide)
        val safeTop = top.coerceIn(0, source.height - safeSide)
        return Rect(safeLeft, safeTop, safeLeft + safeSide, safeTop + safeSide)
    }

    fun result(): Bitmap = IconMaker.crop(source, cropRect())
}
