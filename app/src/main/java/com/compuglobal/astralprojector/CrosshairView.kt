package com.compuglobal.astralprojector

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Full-screen, non-interactive overlay that draws the head-tracked reticle and its dwell-progress
 * ring. The first custom-drawn View in the app.
 *
 * Position comes in as a normalized (u, v) on the panel surface (set from [HeadCursorBridge] by the
 * fragment's ticker), not pixels, so this view converts using its own measured size and needs no
 * knowledge of the panel resolution.
 *
 * In [stereoSplit] mode the surface is a LeftRight stereo pair: each eye sees one half stretched to
 * the full quad, so the reticle is drawn twice — once centred in each half at the same u within
 * that half — and the eyes fuse the copies into a single crosshair at gaze centre (mirroring how
 * the stereo settings column is duplicated per eye).
 *
 * It is never clickable/focusable, so it sits on top of the whole hierarchy without stealing the
 * pointer or controller focus from the buttons underneath.
 */
class CrosshairView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private var u = 0.5f
    private var v = 0.5f
    private var progress = 0f
    private var stereoSplit = false

    private val density = resources.displayMetrics.density
    private val ringRadius = 22f * density
    private val dotRadius = 3f * density
    private val tick = 10f * density

    // Dark halo drawn under the white marks so the reticle reads over bright video or passthrough.
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(160, 0, 0, 0)
        strokeWidth = 6f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = 2.5f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.rgb(120, 210, 255) // cyan accent, matches nothing else so it reads as "loading"
        strokeWidth = 4f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val arcBox = RectF()

    /** Normalized panel position and dwell progress (0..1). Triggers a redraw. */
    fun setCursor(u: Float, v: Float, progress: Float) {
        this.u = u
        this.v = v
        this.progress = progress
        invalidate()
    }

    fun setStereoSplit(split: Boolean) {
        if (stereoSplit == split) return
        stereoSplit = split
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val cy = v * h
        if (stereoSplit) {
            val half = w / 2f
            drawReticle(canvas, u * half, cy)
            drawReticle(canvas, half + u * half, cy)
        } else {
            drawReticle(canvas, u * w, cy)
        }
    }

    private fun drawReticle(canvas: Canvas, cx: Float, cy: Float) {
        // Four ticks around a centre dot: halo first (under), then white marks.
        for (paint in arrayOf(halo, mark)) {
            canvas.drawLine(cx - ringRadius - tick, cy, cx - ringRadius, cy, paint)
            canvas.drawLine(cx + ringRadius, cy, cx + ringRadius + tick, cy, paint)
            canvas.drawLine(cx, cy - ringRadius - tick, cx, cy - ringRadius, paint)
            canvas.drawLine(cx, cy + ringRadius, cx, cy + ringRadius + tick, paint)
        }
        canvas.drawCircle(cx, cy, dotRadius + halo.strokeWidth / 2f, halo)
        canvas.drawCircle(cx, cy, dotRadius, dot)

        if (progress > 0f) {
            arcBox.set(cx - ringRadius, cy - ringRadius, cx + ringRadius, cy + ringRadius)
            // Faint full ring track, then the sweeping progress arc from the top clockwise.
            canvas.drawCircle(cx, cy, ringRadius, halo)
            canvas.drawArc(arcBox, -90f, progress.coerceIn(0f, 1f) * 360f, false, progressPaint)
        }
    }
}
