package com.eee312.ppgmonitor.dsp

import com.eee312.ppgmonitor.model.SensorSample

/**
 * Ties the causal DSP building blocks together into the same pipeline
 * shape as process_live_chunk.m in the MATLAB project - band-pass ->
 * running normalization -> accelerometer-referenced LMS -> windowed
 * FFT heart-rate estimation - except here it runs ONE SAMPLE AT A TIME
 * as it arrives from [SensorSource], which is the natural shape for a
 * live app (the MATLAB version processes small chunks because it was
 * validated by replaying a whole recorded file in pieces - see
 * test_live_vs_batch.m).
 *
 * EXTENSION POINT - SpO2: [SensorSample] already carries BOTH ir and
 * red; this pipeline currently only feeds ONE of them (see
 * [ppgChannelSelector]) into the HR path. Adding SpO2 later means
 * running a SECOND instance of the band-pass+normalize step on the
 * other channel (both instances can share the SAME LmsMultichannelFilter
 * accelerometer reference) and computing AC/DC per window from the
 * pair - it does not require touching the sensor layer or reworking
 * this class's shape.
 *
 * EXTENSION POINT - Hemoglobin: needs additional wavelengths the
 * current hardware doesn't provide (see the chat writeup on hardware
 * options) - out of scope until that hardware exists, but this
 * pipeline's per-channel structure (filter -> normalize -> [LMS] per
 * channel) is meant to generalize to more than two channels when it does.
 */
class PpgPipeline(
    private val fsHz: Double = FS_HZ,
    lmsTapsPerChannel: Int = 32,
    lmsStepSize: Double = 1e-3
) {
    companion object {
        /** Must match SensorSource.sampleRateHz for whichever source is
         * active (SimulatedSensorSource / BleSensorSource) - see
         * BLE_CONTRACT.md if you change the hardware's actual rate. */
        const val FS_HZ = 25.0
    }

    data class Output(
        val rawPpg: Double,
        val cleanedPpg: Double,
        /** Non-null only on the sample where a new estimate was computed
         * (every ~2 s once warmed up) - see HeartRateEstimator. */
        val newHrBpm: Double?,
        /** Always reflects the most recent known estimate, so the UI
         * always has something to show even between updates. */
        val latestHrBpm: Double?,
        val sqi: Double
    )

    private val sos = FilterCoefficients.bandpassFor(fsHz)
    private val ppgFilter = BiquadCascadeFilter(sos)
    private val accXFilter = BiquadCascadeFilter(sos)
    private val accYFilter = BiquadCascadeFilter(sos)
    private val accZFilter = BiquadCascadeFilter(sos)

    private val ppgNorm = RunningNormalizer()
    private val accXNorm = RunningNormalizer()
    private val accYNorm = RunningNormalizer()
    private val accZNorm = RunningNormalizer()

    private val lms = LmsMultichannelFilter(lmsTapsPerChannel, lmsStepSize)
    private val hrEstimator = HeartRateEstimator(fsHz)

    private var latestHrBpm: Double? = null

    /** Which raw field of [SensorSample] feeds the HR path. IR is the
     * default because it typically penetrates tissue better than red on
     * MAX3010x-family sensors, but this is a simple fixed choice, not a
     * per-recording quality comparison like select_best_ppg_channel.m in
     * the MATLAB project - a reasonable v2 improvement once you have
     * enough live data to compare the two. */
    private val ppgChannelSelector: (SensorSample) -> Double = { it.ir.toDouble() }

    fun process(sample: SensorSample): Output {
        val rawPpg = ppgChannelSelector(sample)

        val ppgBp = ppgFilter.process(rawPpg)
        val accXBp = accXFilter.process(sample.accX.toDouble())
        val accYBp = accYFilter.process(sample.accY.toDouble())
        val accZBp = accZFilter.process(sample.accZ.toDouble())

        val ppgN = ppgNorm.process(ppgBp)
        val accXN = accXNorm.process(accXBp)
        val accYN = accYNorm.process(accYBp)
        val accZN = accZNorm.process(accZBp)

        val cleaned = lms.process(ppgN, doubleArrayOf(accXN, accYN, accZN))

        val newHr = hrEstimator.addSample(cleaned)
        if (newHr != null) latestHrBpm = newHr

        return Output(
            rawPpg = rawPpg,
            cleanedPpg = cleaned,
            newHrBpm = newHr,
            latestHrBpm = latestHrBpm,
            sqi = hrEstimator.lastSqi
        )
    }

    fun reset() {
        ppgFilter.reset(); accXFilter.reset(); accYFilter.reset(); accZFilter.reset()
        ppgNorm.reset(); accXNorm.reset(); accYNorm.reset(); accZNorm.reset()
        lms.reset()
        hrEstimator.reset()
        latestHrBpm = null
    }
}
