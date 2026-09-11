package com.eee312.ppgmonitor.dsp

/**
 * Causal (running) z-score normalization - Kotlin port of
 * standardize_signal_online.m from the MATLAB project.
 *
 * The offline MATLAB pipeline's standardize_signal() uses mean(x)/std(x)
 * over the WHOLE recording, which only makes sense once the whole
 * recording exists. A live app never has "the whole recording" - only
 * samples up to now - so this keeps a running mean/variance (EMA)
 * instead, updated causally sample by sample.
 */
class RunningNormalizer(private val alpha: Double = 0.01) {

    private var mean = 0.0
    private var variance = 0.0
    private var initialized = false

    fun process(x: Double): Double {
        if (!initialized) {
            mean = x
            variance = 0.0
            initialized = true
        }

        val delta = x - mean
        mean += alpha * delta
        variance = (1 - alpha) * (variance + alpha * delta * delta)

        val sd = Math.sqrt(variance)
        return if (sd < 1e-12) x - mean else (x - mean) / sd
    }

    fun reset() {
        initialized = false
        mean = 0.0
        variance = 0.0
    }
}
