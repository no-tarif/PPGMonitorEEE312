package com.eee312.ppgmonitor.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import java.util.ArrayDeque

/**
 * Minimal scrolling line-chart, no external charting library - keeps
 * the project dependency-free. Good enough to visually confirm "yes,
 * there's a repeating pulse" and to see the LMS filter visibly calm
 * down during a motion burst; not meant to be a polished chart.
 */
class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val maxPoints = 250 // ~10 s at 25 Hz
    private val points = ArrayDeque<Float>()

    private val linePaint = Paint().apply {
        color = Color.parseColor("#2E7D32")
        strokeWidth = 4f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }
    private val bgPaint = Paint().apply { color = Color.parseColor("#F5F5F5") }

    fun addSample(value: Float) {
        points.addLast(value)
        while (points.size > maxPoints) points.removeFirst()
        postInvalidate()
    }

    fun clear() {
        points.clear()
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        if (points.size < 2) return

        var min = Float.MAX_VALUE
        var max = -Float.MAX_VALUE
        for (p in points) {
            if (p < min) min = p
            if (p > max) max = p
        }
        val range = (max - min).let { if (it < 1e-6f) 1f else it }

        val dx = width.toFloat() / (maxPoints - 1)
        var x = 0f
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
}
