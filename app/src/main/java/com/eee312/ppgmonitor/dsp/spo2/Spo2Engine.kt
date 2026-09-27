package com.eee312.ppgmonitor.dsp.spo2

import kotlin.math.sqrt

/**
 * Windowed SpO2 from dual-wavelength PPG (ratio-of-ratios) - Kotlin port
 * of estimate_spo2.m. See that file's header (and this project's Phase 0
 * report) for the full principle; the short version:
 *
 *       (AC_red / DC_red)
 *   R = -----------------          SpO2 = curve(R)
 *       (AC_ir  / DC_ir )
 *
 * Runs on the RAW ir/red arrays (DC intact - NOT the LMS-cleaned/
 * normalized signal PpgPipeline produces for heart rate; see
 * estimate_spo2_from_table.m's header for why those are different
 * signals with different jobs). This engine does not touch PpgPipeline,
 * HeartRateEstimator, or any other Rule-Zero-protected class - it is
 * called separately, on stored/batched samples, never inline with the
 * live HR path.
 *
 * NOT FOR MEDICAL USE. These are uncalibrated engineering estimates.
 */
object Spo2Engine {

    fun estimate(
        irRaw: DoubleArray,
        redRaw: DoubleArray?,
        fsHz: Double,
        cfg: Spo2Config = Spo2Config()
    ): Spo2Result {
        if (redRaw == null || redRaw.isEmpty() || irRaw.isEmpty()) {
            return Spo2Result.NotAvailable(
                "SpO2 needs both an infrared and a red channel from the same instant - " +
                    "only one wavelength is available for this recording."
            )
        }

        val n0 = minOf(irRaw.size, redRaw.size)
        var ir = irRaw.copyOf(n0)
        var red = redRaw.copyOf(n0)
        var n = n0

        val winSamples = Math.round(cfg.windowSec * fsHz).toInt()
        val stepSamples = Math.round(cfg.stepSec * fsHz).toInt().coerceAtLeast(1)

        // --- Warm-up skip (shrunk to fit if it would eat the only window) ---
        var nSkip = maxOf(0, Math.round(cfg.warmupSec * fsHz).toInt())
        if (nSkip > 0 && (n - nSkip) < winSamples) {
            nSkip = maxOf(0, n - winSamples)
        }
        val timeOffset = nSkip / fsHz
        if (nSkip > 0) {
            ir = ir.copyOfRange(nSkip, n)
            red = red.copyOfRange(nSkip, n)
            n = ir.size
        }

        if (n < winSamples) {
            return Spo2Result.NotAvailable(
                "Recording is shorter than one $winSamples-sample analysis window after the " +
                    "${cfg.warmupSec}s warm-up skip."
            )
        }

        // --- Pulsatile components (DC always comes from the RAW signal) ---
        val sos = Spo2FilterCoefficients.bandpassFor(fsHz, cfg.effectiveBandHz(fsHz))
        val irAc = Spo2Filtering.sosFiltFilt(sos, Spo2Filtering.linearDetrend(ir))
        val redAc = Spo2Filtering.sosFiltFilt(sos, Spo2Filtering.linearDetrend(red))

        val nRows = (n - winSamples) / stepSamples + 1
        val rawWindows = ArrayList<Spo2Window>(nRows)

        for (row in 0 until nRows) {
            val start = row * stepSamples
            val end = start + winSamples
            val tSec = timeOffset + (start + (winSamples - 1) / 2.0) / fsHz

            val dcIr = mean(ir, start, end)
            val dcRed = mean(red, start, end)
            if (dcIr <= 0.0 || dcRed <= 0.0) {
                rawWindows += Spo2Window(tSec, Double.NaN, Double.NaN, Double.NaN, Double.NaN, false, Spo2RejectReason.NON_POSITIVE_DC)
                continue
            }

            val piIr = stdDev(irAc, start, end) / dcIr
            val piRed = stdDev(redAc, start, end) / dcRed
            if (piIr < cfg.minPerfusion || piRed < cfg.minPerfusion) {
                rawWindows += Spo2Window(tSec, Double.NaN, Double.NaN, piIr, piRed, false, Spo2RejectReason.LOW_PERFUSION)
                continue
            }

            val r = piRed / piIr
            if (r < cfg.validRRange.first || r > cfg.validRRange.second) {
                rawWindows += Spo2Window(tSec, r, Double.NaN, piIr, piRed, false, Spo2RejectReason.R_OUT_OF_RANGE)
                continue
            }

            val spo2 = cfg.curve.apply(r)
            rawWindows += Spo2Window(tSec, r, spo2, piIr, piRed, false, Spo2RejectReason.NONE)
        }

        // --- Optional one-point offset calibration (shifts SpO2, never R) ---
        var calOffset = 0.0
        var calInfo = "none (generic published curve, uncalibrated)"
        var windows: List<Spo2Window> = rawWindows

        val sp = cfg.singlePoint
        if (sp.enable) {
            if (sp.refSpo2 == null) {
                calInfo = "single-point calibration enabled but refSpo2 is null - skipped"
            } else {
                val selectable = rawWindows.filter { w ->
                    !w.r.isNaN() &&
                        (sp.timeRangeSec == null || (w.tSec >= sp.timeRangeSec.first && w.tSec <= sp.timeRangeSec.second))
                }
                if (selectable.isEmpty()) {
                    calInfo = "no windows with a usable R in the calibration time range - skipped"
                } else {
                    val rCal = median(selectable.map { it.r })
                    val spo2AtCal = cfg.curve.apply(rCal)
                    calOffset = sp.refSpo2 - spo2AtCal
                    calInfo = "one-point offset: R_cal = %.4f over %d window(s) -> curve gives %.1f%%, reference %.1f%%, offset %+.2f%%"
                        .format(rCal, selectable.size, spo2AtCal, sp.refSpo2, calOffset)
                    windows = rawWindows.map { w -> if (w.spo2.isNaN()) w else w.copy(spo2 = w.spo2 + calOffset) }
                }
            }
        }

        // --- Validity after calibration ---
        val finalWindows = windows.map { w ->
            if (w.rejectReason != Spo2RejectReason.NONE || w.spo2.isNaN()) return@map w
            when {
                w.spo2 > cfg.validSpo2Range.second -> w.copy(valid = false, rejectReason = Spo2RejectReason.ABOVE_100)
                w.spo2 < cfg.validSpo2Range.first -> w.copy(valid = false, rejectReason = Spo2RejectReason.BELOW_70)
                else -> w.copy(valid = true, rejectReason = Spo2RejectReason.NONE)
            }
        }

        return Spo2Result.Available(finalWindows, buildSummary(finalWindows, cfg, calInfo, timeOffset))
    }

    private fun buildSummary(
        windows: List<Spo2Window>,
        cfg: Spo2Config,
        calInfo: String,
        warmupUsed: Double
    ): Spo2Summary {
        val nRows = windows.size
        val accepted = windows.filter { it.valid }
        val finiteR = windows.map { it.r }.filter { !it.isNaN() }
        val finitePiIr = windows.map { it.piIr }.filter { !it.isNaN() }
        val finitePiRed = windows.map { it.piRed }.filter { !it.isNaN() }

        val medianR = if (finiteR.isNotEmpty()) median(finiteR) else Double.NaN
        val sdR = if (finiteR.size >= 2) stdDevList(finiteR) else Double.NaN
        val medianPiIr = if (finitePiIr.isNotEmpty()) median(finitePiIr) else Double.NaN
        val medianPiRed = if (finitePiRed.isNotEmpty()) median(finitePiRed) else Double.NaN

        return Spo2Summary(
            windows = nRows,
            accepted = accepted.size,
            acceptedPct = if (nRows > 0) 100.0 * accepted.size / nRows else 0.0,
            medianAcceptedSpo2 = if (accepted.isNotEmpty()) median(accepted.map { it.spo2 }) else null,
            minAcceptedSpo2 = accepted.minOfOrNull { it.spo2 },
            maxAcceptedSpo2 = accepted.maxOfOrNull { it.spo2 },
            medianR = medianR,
            sdR = sdR,
            medianPiIr = medianPiIr,
            medianPiRed = medianPiRed,
            curve = cfg.curve,
            calibrationInfo = calInfo,
            warmupSecUsed = warmupUsed,
            diagnosis = buildDiagnosis(windows, cfg, medianR, medianPiIr, medianPiRed)
        )
    }

    /** Plain-language read of the numbers above. Covers the same ground as
     * estimate_spo2.m's printed warnings (rejection rate vs. R range, low
     * perfusion), plus one case MATLAB's own fprintf logic doesn't call
     * out explicitly but sep16 makes obvious: most windows rejected
     * because they read ABOVE 100%, which points specifically at a weak
     * red channel rather than generic "check sensor contact". */
    private fun buildDiagnosis(
        windows: List<Spo2Window>,
        cfg: Spo2Config,
        medianR: Double,
        medianPiIr: Double,
        medianPiRed: Double
    ): String {
        val nRows = windows.size
        if (nRows == 0) return "No windows analysed."

        val nAccepted = windows.count { it.valid }
        val aboveCount = windows.count { it.rejectReason == Spo2RejectReason.ABOVE_100 }
        val parts = mutableListOf<String>()

        if (nAccepted < nRows / 2) {
            when {
                aboveCount >= nRows / 2 -> {
                    val ratio = if (medianPiRed > 0.0) medianPiIr / medianPiRed else Double.NaN
                    val ratioText = if (!ratio.isNaN()) "~${"%.0f".format(ratio)}x weaker" else "weaker"
                    parts += "Most windows ($aboveCount/$nRows) read above 100%% and were rejected - the red " +
                        "channel's perfusion index is $ratioText than IR (median PI_IR %.4f%% vs PI_RED %.4f%%). "
                            .format(100 * medianPiIr, 100 * medianPiRed) +
                        "This is a hardware/calibration issue, not something LED brightness alone fixes, " +
                        "because R is a ratio of ratios."
                }
                !medianR.isNaN() && medianR < cfg.validRRange.first -> {
                    parts += ("Median R = %.3f is below the plausible range (%.1f-%.1f). With PI_IR %.4f%% and " +
                        "PI_RED %.4f%%, the red channel is carrying proportionally too little pulsatile signal. " +
                        "Raising LED current will NOT change this (R is a ratio of ratios) - check ambient " +
                        "light leaking into DC, sensor contact pressure, and perfusion at the site; then " +
                        "consider single-point calibration.")
                        .format(medianR, cfg.validRRange.first, cfg.validRRange.second, 100 * medianPiIr, 100 * medianPiRed)
                }
                else -> {
                    parts += "Most windows were rejected ($nAccepted/$nRows accepted). Check sensor contact and " +
                        "confirm both channels show a clear pulsatile waveform before trusting any SpO2 value."
                }
            }
        }

        if (!medianPiIr.isNaN() && medianPiIr < 0.005) {
            parts += ("PI_IR = %.4f%% is low (a well-perfused fingertip typically gives 0.5-2%%). Low perfusion " +
                "degrades both channels and makes R noisier - common causes: sensor too tight (occluding " +
                "flow), too loose (ambient light inflating DC), cold hands, or a poorly perfused site.")
                .format(100 * medianPiIr)
        }

        if (parts.isEmpty()) {
            parts += "Perfusion and window-acceptance rate are within expected bounds."
        }
        return parts.joinToString(" ")
    }

    private fun mean(x: DoubleArray, start: Int, end: Int): Double {
        var sum = 0.0
        for (i in start until end) sum += x[i]
        return sum / (end - start)
    }

    /** Sample standard deviation (N-1 denominator), matching MATLAB's
     * std() and the Python reference's numpy .std(ddof=1). */
    private fun stdDev(x: DoubleArray, start: Int, end: Int): Double {
        val n = end - start
        if (n < 2) return 0.0
        val m = mean(x, start, end)
        var sq = 0.0
        for (i in start until end) {
            val d = x[i] - m
            sq += d * d
        }
        return sqrt(sq / (n - 1))
    }

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return Double.NaN
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2.0 else sorted[mid]
    }

    private fun stdDevList(values: List<Double>): Double {
        val n = values.size
        if (n < 2) return Double.NaN
        val m = values.average()
        var sq = 0.0
        for (v in values) { val d = v - m; sq += d * d }
        return sqrt(sq / (n - 1))
    }
}
