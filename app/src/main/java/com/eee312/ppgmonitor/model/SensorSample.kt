package com.eee312.ppgmonitor.model

/**
 * One raw reading from the sensor rig (real hardware or simulated).
 *
 * IMPORTANT (extensibility): both [ir] and [red] are carried here from
 * day one, even though the v1 pipeline (heart rate only) only uses ONE
 * of them. This mirrors a real gap found while reviewing the MATLAB
 * side of this project: the offline pipeline's channel-selection step
 * kept only the "best" of red/ir and discarded the other, which works
 * fine for heart rate but makes it hard to add SpO2 later (SpO2 needs
 * red AND ir from the SAME instant). Keeping both here means adding
 * SpO2 later is "read a field that was already being recorded", not
 * "go back and change what the sensor layer captures".
 *
 * Units:
 *   timestampMs - milliseconds, monotonic (device uptime is fine - only
 *                 differences between samples matter for sampling-rate
 *                 estimation)
 *   ir, red     - raw ADC counts from the optical sensor (unscaled -
 *                 matches what a MAX30102-family sensor reports)
 *   accX/Y/Z    - accelerometer, any consistent unit (g or m/s^2) - the
 *                 pipeline only cares about relative motion, not the
 *                 absolute unit, since everything is band-passed and
 *                 normalized before use
 *
 * SpO2 addition: [red] is nullable (default null) rather than the old
 * always-present Int, so a genuinely single-wavelength source (e.g. the
 * "Hardware - Running 2" dataset, exported before this app captured a
 * second channel) can say so precisely instead of overloading 0 as both
 * "no red channel" and "red read exactly zero counts". [green] is new -
 * nullable, default null, and NOT read by PpgPipeline (which only ever
 * used [ir] - see its ppgChannelSelector) or by anything else yet; it
 * exists purely so sensor sources that carry a green channel (sep16-style
 * hardware) have somewhere to put it. Both defaults mean every existing
 * SensorSample(...) call site compiles unchanged - see PpgPipeline's and
 * MainActivity's header comments for why this is safe under Rule Zero.
 */
data class SensorSample(
    val timestampMs: Long,
    val ir: Int,
    val red: Int? = null,
    val accX: Float,
    val accY: Float,
    val accZ: Float,
    val green: Int? = null
)
