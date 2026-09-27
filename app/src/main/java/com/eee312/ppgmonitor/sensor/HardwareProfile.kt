package com.eee312.ppgmonitor.sensor

/**
 * Numbers that describe the rig built by HARDWARE_SETUP_GUIDE.md
 * (ESP32 DevKit V1 + MAX30105 + MPU6050, firmware PpgStream.ino).
 * Everything the app assumes about that hardware lives here.
 */
object HardwareProfile {
    /** 100 samples/s averaged x4 (PpgStream.ino setup()). The app measures
     * the real rate and follows it; this is only the starting point. */
    const val NOMINAL_RATE_HZ = 25.0

    /** A live rig more than this fraction off nominal gets its DSP rebuilt
     * at the measured rate (1 % = about 1 BPM at 80 BPM). */
    const val RATE_SWITCH_TOLERANCE = 0.01

    /** Samples needed before the measured rate is trusted (10 s at 25 Hz). */
    const val RATE_MIN_SAMPLES = 250

    /** 18-bit ADC (pulse width 411 us), full scale 262143 counts. */
    const val ADC_FULL_SCALE = 262_143

    /** At or above this the channel is on the rail (same value PiBalance.ino
     * uses for "railed"). */
    const val SATURATION_COUNTS = 262_000

    /** Below this IR level no finger is on the sensor. SparkFun's MAX3010x
     * examples use 50000 at the same LED current (0x1F = 6.4 mA) and ADC
     * range (4096 nA) as the guide; a covered fingertip on the rig reads
     * ~80k-115k (hw_walk, hw_class1, sep16). Applied to the live ESP32
     * sources only - recorded datasets (e.g. PhysioNet) use other units. */
    const val FINGER_PRESENT_IR = 50_000
}
