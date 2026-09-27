package com.eee312.ppgmonitor.history

/**
 * What the History screens SHOW. Nothing here changes what is computed or
 * stored: ReadingPostProcessor still runs the SpO2 pass and the six-check
 * quality report, Spo2WindowEntity still keeps R and PI, and every DB
 * column/DAO query stays. Only the views are hidden.
 *
 * SHOW_ADVANCED_METRICS = true brings back, in one switch:
 *  - detail: the R (ratio-of-ratios) and perfusion-index graphs, the
 *    "Recording quality" verdict and its six-check table, the per-window
 *    SQI trace on the BPM graph, the raw IR/Red overlay on the PPG graph
 *    and the extra SpO2 details (range, % accepted, median R, PI)
 *  - list: the GOOD / MARGINAL / NOT USABLE quality chip
 * When false, those views are GONE and their chart data is never built.
 */
object HistoryDisplayConfig {
    const val SHOW_ADVANCED_METRICS = false
}
