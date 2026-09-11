package com.eee312.ppgmonitor.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import java.util.ArrayDeque

/**
 * Minimal line-chart, no external charting library - keeps the project
 * dependency-free. Good enough to visually confirm "yes, there's a
 * repeating pulse" and to see the LMS filter visibly calm down during a
 * motion burst; not meant to be a polished chart.
 *
 * Two modes, mutually exclusive:
 *  - LIVE (via [addSample]/[clear]): a scrolling ~10s window, used on
 *    the main screen while a source is running.
 *  - STATIC (via [setStaticData]): renders a whole fixed array at once,
 *    used by History's detail screen to show a full stored reading
 *    (waveform or BPM-over-time trend) - reused rather than pulling in a
 *    charting library. Long arrays are decimated to ~2 points per pixel
 *    so a multi-minute reading still draws in one frame.
 */
class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val maxPoints = 250 // ~10 s at 25 Hz
    private val points = ArrayDeque<Float>()

    private var staticData: FloatArray? = null

    private val linePaint = Paint().apply {
        color = Color.parseColor("#2E7D32")
        strokeWidth = 4f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }
    private val bgPaint = Paint().apply { color = Color.parseColor("#F5F5F5") }

    fun addSample(value: Float) {
        staticData = null
        points.addLast(value)
        while (points.size > maxPoints) points.removeFirst()
        postInvalidate()
    }

    fun clear() {
        staticData = null
        points.clear()
        postInvalidate()
    }

    /** Switches to STATIC mode and renders this whole array at once. */
    fun setStaticData(data: FloatArray) {
        points.clear()
        staticData = data
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        val data = staticData
        if (data != null) drawStatic(canvas, data) else drawLive(canvas)
    }

    private fun drawLive(canvas: Canvas) {
        if (points.size < 2) return

        var min = Float.MAX_VALUE
        var max = -Float.MAX_VALUE
        for (p in points) {
            if (p < min) min = p
            if (p > max) max = p
        }
        val range = (max - min).let { if (it < 1e-6f) 1f else it }

        val dx = width.toFloat() / (maxPoints - 1)
        var x: Float
        var prevX = -1f
        var prevY = -1f

        // Right-align: newest sample at the right edge.
        val startIndex = maxPoints - points.size
        x = startIndex * dx

        for (p in points) {
            val norm = (p - min) / range // 0..1
            val y = height - (norm * height * 0.9f + height * 0.05f)
            if (prevX >= 0f) {
                canvas.drawLine(prevX, prevY, x, y, linePaint)
            }
            prevX = x
            prevY = y
            x += dx
        }
    }

    private fun drawStatic(canvas: Canvas, data: FloatArray) {
        if (data.size < 2 || width <= 0) return

        // Decimate to ~2 points per pixel column so drawing stays O(width)
        // regardless of how many samples a multi-minute reading has.
        val targetPoints = (width * 2).coerceAtLeast(2)
        val stride = (data.size / targetPoints).coerceAtLeast(1)

        var min = Float.MAX_VALUE
        var max = -Float.MAX_VALUE
        var i = 0
        while (i < data.size) {
            val v = data[i]
            if (v < min) min = v
            if (v > max) max = v
            i += stride
        }
        val range = (max - min).let { if (it < 1e-6f) 1f else it }

        val plottedCount = (data.size + stride - 1) / stride
        val dx = width.toFloat() / (plottedCount - 1).coerceAtLeast(1)

        var x = 0f
        var prevX = -1f
        var prevY = -1f
        i = 0
        while (i < data.size) {
            val norm = (data[i] - min) / range
            val y = height - (norm * height * 0.9f + height * 0.05f)
            if (prevX >= 0f) {
                canvas.drawLine(prevX, prevY, x, y, linePaint)
            }
            prevX = x
            prevY = y
            x += dx
            i += stride
        }
    }
}
