package com.eee312.ppgmonitor.dsp

/** Per-sample bitmask stored in SampleEntity.flags (schema v3). */
object SampleFlags {
    /** The PPG value fed to the DSP was replaced by the average of the
     * previous 1 s of good samples (short dropout). */
    const val FILLED = 1
    /** Part of a dropout longer than the fill limit: left out of BPM, SpO2
     * and the History stats. */
    const val LOST = 2
    /** No sample was received for this slot at all (a gap in the stream);
     * the row exists only to keep the time base. Not exported. */
    const val INSERTED = 4
    /** The accelerometer values were unusable (all exactly 0, stuck, or the
     * firmware's "nan"); LMS adaptation was frozen for this sample. */
    const val ACC_BAD = 8
    /** A PPG channel was at the ADC rail. */
    const val SATURATED = 16
    /** IR below the finger-present level, or far below its recent baseline. */
    const val LOW_SIGNAL = 32
    /** Within the settling time after the signal came back. */
    const val SETTLING = 64

    /** Any of these in an SpO2 window drops the window. */
    const val SPO2_EXCLUDE = FILLED or LOST or INSERTED or SATURATED or LOW_SIGNAL or SETTLING

    fun has(flags: Int?, bit: Int): Boolean = flags != null && (flags and bit) != 0
}
