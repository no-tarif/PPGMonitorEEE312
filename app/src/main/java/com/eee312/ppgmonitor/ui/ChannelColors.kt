package com.eee312.ppgmonitor.ui

import android.graphics.Color

/**
 * Fixed colours for every channel/series this app plots, used consistently
 * across the live screen, history list, and reading detail dashboard.
 *
 * NOTE: this app's theme (res/values/themes.xml) is hardcoded to
 * Theme.AppCompat.Light.NoActionBar - there is no values-night variant, so
 * "dark theme" is not actually reachable today regardless of the system
 * setting. These colours are chosen to read clearly on the light
 * background that's the only one the app ever shows; making the THEME
 * itself dark-mode-aware would be a separate, bigger change than this
 * phase's chart work and wasn't asked for elsewhere, so it's out of scope
 * here - flagging rather than quietly half-doing it.
 *
 * Distinguishable by line STYLE as well as colour (see [LineStyle]), so a
 * legend still works for anyone who can't rely on colour alone.
 */
object ChannelColors {
    val IR = Color.parseColor("#E65100")     // deep orange
    val RED = Color.parseColor("#C62828")    // red
    val GREEN = Color.parseColor("#2E7D32")  // green (matches WaveformView's existing line colour)
    val BPM = Color.parseColor("#1565C0")    // blue
    val SQI = Color.parseColor("#6A1B9A")    // purple
    val SPO2 = Color.parseColor("#00838F")   // teal

    val BAND_FILL = Color.parseColor("#332E7D32")   // translucent green, generic shaded band
    val WARMUP_FILL = Color.parseColor("#339E9E9E") // translucent grey, warm-up region
    val REFERENCE_LINE = Color.parseColor("#757575") // dashed reference lines (e.g. 100% cap)
}

/** Line styles a [com.eee312.ppgmonitor.ui.TrendChartView] series can use -
 * paired with [ChannelColors] so two series are never colour-only. */
enum class LineStyle { SOLID, DASHED, DOTTED }

/** Per-point marker style for a series (e.g. accepted vs rejected SpO2 windows). */
enum class MarkerStyle { NONE, FILLED_DOT, HOLLOW_DOT }
