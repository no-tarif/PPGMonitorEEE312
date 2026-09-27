package com.eee312.ppgmonitor.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * Whole-recording PPG chart for the History detail screen: every sample
 * from start to end, drawn with min/max decimation (one vertical min-max
 * stroke per pixel column once there are more samples than pixels, so no
 * peak is ever skipped the way stride sampling skips them).
 *
 * Pinch horizontally to zoom, drag to scroll, double-tap to show the whole
 * recording again. The y-axis auto-scales to the visible range.
 *
 * NaN samples are drawn as gaps (e.g. "signal lost" segments).
 *
 * For long readings (a looped dataset can be 12 h = 1.1 M samples) a
 * block min/max table ([BLOCK] samples per block) is built once in
 * [setData], so a zoomed-out frame reads ~n/BLOCK values instead of n.
 */
class ZoomableWaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        private const val BLOCK = 64
        private const val MIN_VISIBLE_SAMPLES = 50.0
    }

    private var data = FloatArray(0)
    private var blockMin = FloatArray(0)
    private var blockMax = FloatArray(0)
    private var fsHz = 25.0

    // Visible window in sample-index units.
    private var viewStart = 0.0
    private var viewLen = 1.0

    private val bgPaint = Paint().apply { color = Color.parseColor("#F5F5F5") }
    private val linePaint = Paint().apply {
        color = ChannelColors.GREEN
        strokeWidth = 3f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }
    private val axisPaint = Paint().apply { color = Color.parseColor("#616161"); textSize = 24f; isAntiAlias = true }
    private val emptyPaint = Paint().apply { color = Color.parseColor("#9E9E9E"); textSize = 28f; isAntiAlias = true }

    var emptyText: String = "Loading…"
        set(value) { field = value; postInvalidate() }

    fun setData(samples: FloatArray, sampleRateHz: Double) {
        data = samples
        fsHz = if (sampleRateHz > 0) sampleRateHz else 25.0
        val nBlocks = (samples.size + BLOCK - 1) / BLOCK
        blockMin = FloatArray(nBlocks) { Float.NaN }
        blockMax = FloatArray(nBlocks) { Float.NaN }
        for (b in 0 until nBlocks) {
            var mn = Float.POSITIVE_INFINITY
            var mx = Float.NEGATIVE_INFINITY
            val end = min(samples.size, (b + 1) * BLOCK)
            for (i in b * BLOCK until end) {
                val v = samples[i]
                if (v.isNaN()) continue
                if (v < mn) mn = v
                if (v > mx) mx = v
            }
            if (mn <= mx) { blockMin[b] = mn; blockMax[b] = mx }
        }
        resetZoom()
    }

    fun resetZoom() {
        viewStart = 0.0
        viewLen = max(1.0, data.size.toDouble())
        postInvalidate()
    }

    private fun clampView() {
        val n = max(1.0, data.size.toDouble())
        viewLen = viewLen.coerceIn(min(MIN_VISIBLE_SAMPLES, n), n)
        viewStart = viewStart.coerceIn(0.0, n - viewLen)
    }

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val span = detector.currentSpanX
            val prev = detector.previousSpanX
            if (prev <= 0f || span <= 0f) return true
            val factor = prev / span
            val focusIdx = viewStart + detector.focusX / width.coerceAtLeast(1) * viewLen
            viewLen *= factor
            clampView()
            viewStart = focusIdx - detector.focusX / width.coerceAtLeast(1) * viewLen
            clampView()
            invalidate()
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            viewStart += dx / width.coerceAtLeast(1) * viewLen
            clampView()
            invalidate()
            return true
        }
        override fun onDoubleTap(e: MotionEvent): Boolean {
            resetZoom()
            return true
        }
    })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Keep the surrounding ScrollView from stealing horizontal gestures.
        if (event.actionMasked == MotionEvent.ACTION_DOWN || event.pointerCount > 1) {
            parent?.requestDisallowInterceptTouchEvent(true)
        }
        scaleDetector.onTouchEvent(event)
        if (!scaleDetector.isInProgress) gestureDetector.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        if (data.size < 2 || width <= 0) {
            canvas.drawText(emptyText, 16f, height / 2f, emptyPaint)
            return
        }
        clampView()

        val plotTop = 8f
        val plotBottom = height - 30f
        val cols = width
        val perCol = viewLen / cols

        // Pass 1: per-column min/max over the visible window.
        val colMin = FloatArray(cols) { Float.NaN }
        val colMax = FloatArray(cols) { Float.NaN }
        var yMin = Float.POSITIVE_INFINITY
        var yMax = Float.NEGATIVE_INFINITY
        for (c in 0 until cols) {
            val a = (viewStart + c * perCol).toInt().coerceIn(0, data.size - 1)
            val b = (viewStart + (c + 1) * perCol).toInt().coerceIn(a + 1, data.size)
            var mn = Float.POSITIVE_INFINITY
            var mx = Float.NEGATIVE_INFINITY
            var i = a
            while (i < b) {
                if (i % BLOCK == 0 && i + BLOCK <= b) {
                    val blk = i / BLOCK
                    val bm = blockMin[blk]
                    if (!bm.isNaN()) { if (bm < mn) mn = bm; if (blockMax[blk] > mx) mx = blockMax[blk] }
                    i += BLOCK
                } else {
                    val v = data[i]
                    if (!v.isNaN()) { if (v < mn) mn = v; if (v > mx) mx = v }
                    i++
                }
            }
            if (mn <= mx) {
                colMin[c] = mn; colMax[c] = mx
                if (mn < yMin) yMin = mn
                if (mx > yMax) yMax = mx
            }
        }
        if (yMin > yMax) { canvas.drawText("No data in view", 16f, height / 2f, emptyPaint); return }
        val range = (yMax - yMin).let { if (it < 1e-6f) 1f else it }
        fun yPix(v: Float) = plotBottom - ((v - yMin) / range * 0.92f + 0.04f) * (plotBottom - plotTop)

        // Pass 2: draw. Sparse view (fewer samples than columns) -> a line
        // through each sample; dense view -> one min-max stroke per column,
        // joined to the next so the trace stays continuous.
        if (perCol < 1.0) {
            var prevX = Float.NaN
            var prevY = Float.NaN
            val first = viewStart.toInt()
            val last = min(data.size - 1, (viewStart + viewLen).toInt() + 1)
            for (i in first..last) {
                val v = data[i]
                if (v.isNaN()) { prevX = Float.NaN; continue }
                val x = ((i - viewStart) / viewLen * cols).toFloat()
                val y = yPix(v)
                if (!prevX.isNaN()) canvas.drawLine(prevX, prevY, x, y, linePaint)
                prevX = x; prevY = y
            }
        } else {
            var prevMid = Float.NaN
            for (c in 0 until cols) {
                if (colMin[c].isNaN()) { prevMid = Float.NaN; continue }
                val x = c.toFloat()
                val yTop = yPix(colMax[c])
                val yBot = yPix(colMin[c])
                canvas.drawLine(x, yTop, x, yBot + 1f, linePaint)
                if (!prevMid.isNaN()) canvas.drawLine(x - 1f, prevMid, x, (yTop + yBot) / 2f, linePaint)
                prevMid = (yTop + yBot) / 2f
            }
        }

        val t0 = viewStart / fsHz
        val t1 = (viewStart + viewLen) / fsHz
        canvas.drawText(formatTime(t0), 4f, height - 6f, axisPaint)
        val endLabel = formatTime(t1)
        canvas.drawText(endLabel, width - axisPaint.measureText(endLabel) - 4f, height - 6f, axisPaint)
        if (viewLen < data.size) {
            val hint = "double-tap: whole recording"
            canvas.drawText(hint, (width - axisPaint.measureText(hint)) / 2f, height - 6f, axisPaint)
        }
    }

    private fun formatTime(sec: Double): String {
        val s = sec.coerceAtLeast(0.0)
        val total = s.toLong()
        return if (total >= 3600) "%d:%02d:%02d".format(total / 3600, (total / 60) % 60, total % 60)
        else if (viewLen / fsHz < 60) "%d:%04.1f".format(total / 60, s % 60)
        else "%d:%02d".format(total / 60, total % 60)
    }
}
