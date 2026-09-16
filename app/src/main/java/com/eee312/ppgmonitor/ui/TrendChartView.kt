package com.eee312.ppgmonitor.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * One plotted line/scatter series. [xs]/[ys] must be the same length.
 * [independentScale]: when true, this series is normalized to its OWN
 * min/max within the chart height, independent of every other series -
 * used to overlay two differently-scaled quantities (e.g. BPM and a
 * 0..1 SQI) on one chart without a real second axis being drawn.
 * [markerFilled]: same length as [xs]/[ys] when non-null - draws a
 * filled or hollow dot per point (e.g. accepted vs. rejected SpO2
 * windows) in addition to (or instead of, if [drawLine] is false) the
 * connecting line.
 */
data class ChartSeries(
    val xs: FloatArray,
    val ys: FloatArray,
    val color: Int,
    val style: LineStyle = LineStyle.SOLID,
    val markerFilled: BooleanArray? = null,
    val independentScale: Boolean = false,
    val drawLine: Boolean = true
)

data class ChartBand(val yMin: Float, val yMax: Float, val color: Int = ChannelColors.BAND_FILL)
data class ChartVBand(val xMin: Float, val xMax: Float, val color: Int = ChannelColors.WARMUP_FILL)
data class ChartRefLine(val y: Float, val color: Int = ChannelColors.REFERENCE_LINE)

/**
 * Reusable Canvas-based multi-series trend chart - one implementation
 * shared by every dashboard chart (BPM+SQI, SpO2, R, perfusion index),
 * so none of them need a charting library. Same fixed-width, no-gesture
 * rendering approach as WaveformView's static mode (decimate/scale to
 * fit, redraw whole on setData) - WaveformView itself has no pinch/
 * scroll despite being the thing to model this on, so there is nothing
 * gestural to match; this view doesn't add any either.
 *
 * Series without [ChartSeries.independentScale] share one y-axis, either
 * auto-ranged from their data (+ any bands/reference lines) or pinned
 * via [setData]'s yMin/yMax. Each independentScale series is normalized
 * to its own min/max separately.
 */
class TrendChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var series: List<ChartSeries> = emptyList()
    private var fixedYMin: Float? = null
    private var fixedYMax: Float? = null
    private var hBands: List<ChartBand> = emptyList()
    private var vBands: List<ChartVBand> = emptyList()
    private var refLines: List<ChartRefLine> = emptyList()

    private val bgPaint = Paint().apply { color = Color.parseColor("#F5F5F5") }
    private val bandPaint = Paint().apply { style = Paint.Style.FILL }
    private val linePaint = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 4f; isAntiAlias = true }
    private val markerPaint = Paint().apply { isAntiAlias = true }
    private val axisPaint = Paint().apply { color = Color.parseColor("#616161"); textSize = 24f; isAntiAlias = true }
    private val refLinePaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        isAntiAlias = true
        pathEffect = DashPathEffect(floatArrayOf(14f, 10f), 0f)
    }

    fun setData(
        series: List<ChartSeries>,
        yMin: Float? = null,
        yMax: Float? = null,
        hBands: List<ChartBand> = emptyList(),
        vBands: List<ChartVBand> = emptyList(),
        refLines: List<ChartRefLine> = emptyList()
    ) {
        this.series = series
        this.fixedYMin = yMin
        this.fixedYMax = yMax
        this.hBands = hBands
        this.vBands = vBands
        this.refLines = refLines
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        if (width <= 0 || height <= 0 || series.isEmpty()) return

        val plotLeft = 64f
        val plotRight = width.toFloat() - 8f
        val plotTop = 8f
        val plotBottom = height.toFloat() - 28f
        if (plotRight <= plotLeft || plotBottom <= plotTop) return

        var xMinV = Float.MAX_VALUE
        var xMaxV = -Float.MAX_VALUE
        for (s in series) for (x in s.xs) { if (x < xMinV) xMinV = x; if (x > xMaxV) xMaxV = x }
        if (xMinV > xMaxV) return
        val xRange = (xMaxV - xMinV).let { if (it < 1e-6f) 1f else it }
        fun xPix(x: Float) = plotLeft + (x - xMinV) / xRange * (plotRight - plotLeft)

        val sharedSeries = series.filter { !it.independentScale }
        var sMin = fixedYMin ?: Float.MAX_VALUE
        var sMax = fixedYMax ?: -Float.MAX_VALUE
        if (fixedYMin == null || fixedYMax == null) {
            for (s in sharedSeries) for (y in s.ys) { if (y < sMin) sMin = y; if (y > sMax) sMax = y }
            for (b in hBands) { if (b.yMin < sMin) sMin = b.yMin; if (b.yMax > sMax) sMax = b.yMax }
            for (r in refLines) { if (r.y < sMin) sMin = r.y; if (r.y > sMax) sMax = r.y }
        }
        if (sMin > sMax || !sMin.isFinite() || !sMax.isFinite()) { sMin = 0f; sMax = 1f }
        val sRange = (sMax - sMin).let { if (it < 1e-6f) 1f else it }
        fun yPixShared(y: Float) = plotBottom - (y - sMin) / sRange * (plotBottom - plotTop)

        for (b in vBands) {
            bandPaint.color = b.color
            canvas.drawRect(xPix(b.xMin), plotTop, xPix(b.xMax), plotBottom, bandPaint)
        }
        for (b in hBands) {
            bandPaint.color = b.color
            canvas.drawRect(plotLeft, yPixShared(b.yMax), plotRight, yPixShared(b.yMin), bandPaint)
        }
        for (r in refLines) {
            refLinePaint.color = r.color
            val y = yPixShared(r.y)
            canvas.drawLine(plotLeft, y, plotRight, y, refLinePaint)
        }

        for (s in series) {
            if (s.xs.isEmpty()) continue
            val localMin: Float
            val localMax: Float
            if (s.independentScale) {
                var mn = Float.MAX_VALUE
                var mx = -Float.MAX_VALUE
                for (y in s.ys) { if (y < mn) mn = y; if (y > mx) mx = y }
                if (mn > mx) { mn = 0f; mx = 1f }
                localMin = mn; localMax = mx
            } else {
                localMin = sMin; localMax = sMax
            }
            val localRange = (localMax - localMin).let { if (it < 1e-6f) 1f else it }
            fun yPix(y: Float) = plotBottom - (y - localMin) / localRange * (plotBottom - plotTop)

            if (s.drawLine && s.xs.size >= 2) {
                linePaint.color = s.color
                linePaint.pathEffect = when (s.style) {
                    LineStyle.SOLID -> null
                    LineStyle.DASHED -> DashPathEffect(floatArrayOf(18f, 10f), 0f)
                    LineStyle.DOTTED -> DashPathEffect(floatArrayOf(3f, 7f), 0f)
                }
                var prevX = Float.NaN
                var prevY = Float.NaN
                for (i in s.xs.indices) {
                    val px = xPix(s.xs[i])
                    val py = yPix(s.ys[i])
                    if (!prevX.isNaN()) canvas.drawLine(prevX, prevY, px, py, linePaint)
                    prevX = px; prevY = py
                }
            }

            s.markerFilled?.let { flags ->
                markerPaint.color = s.color
                for (i in s.xs.indices) {
                    val px = xPix(s.xs[i])
                    val py = yPix(s.ys[i])
                    if (i < flags.size && flags[i]) {
                        markerPaint.style = Paint.Style.FILL
                        canvas.drawCircle(px, py, 7f, markerPaint)
                    } else {
                        markerPaint.style = Paint.Style.STROKE
                        markerPaint.strokeWidth = 3f
                        canvas.drawCircle(px, py, 6f, markerPaint)
                    }
                }
            }
        }

        canvas.drawText("%.0f".format(sMax), 4f, plotTop + 22f, axisPaint)
        canvas.drawText("%.0f".format(sMin), 4f, plotBottom, axisPaint)
    }
}
