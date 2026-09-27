package com.eee312.ppgmonitor.sensor

import com.eee312.ppgmonitor.dsp.PpgPipeline
import com.eee312.ppgmonitor.dsp.SampleRateEstimator
import com.eee312.ppgmonitor.model.SensorSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * No-hardware test of the ESP32 link: lines formatted byte-for-byte like
 * PpgStream.ino v1.1's snprintf ("%lu,%lu,%.3f,%.3f,%.3f,,%lu\n", or
 * "nan,nan,nan" axes), delivered in USB-sized bursts with jittery arrival
 * times, go through SerialLineParser -> SampleRateEstimator -> PpgPipeline
 * built at the measured rate, exactly the path MainActivity takes.
 */
class FirmwareStreamTest {

    /** One firmware line, formatted like the sketch (C locale: '.' decimals). */
    private fun firmwareLine(ir: Long, red: Long, ax: Float, ay: Float, az: Float, seq: Long, green: Long? = null): String {
        val acc = if (ax.isNaN()) "nan,nan,nan" else String.format(Locale.ROOT, "%.3f,%.3f,%.3f", ax, ay, az)
        return if (green == null) "$ir,$red,$acc,,$seq\n" else "$ir,$red,$acc,$green,$seq\n"
    }

    private data class Synth(val lines: List<String>, val arrivalSec: List<Double>)

    /** [trueRateHz] sensor rate, [bpm] pulse rate. Arrival: lines are
     * grouped into USB packets of 1-4 lines, each packet delayed 2-15 ms. */
    private fun synthesize(trueRateHz: Double, bpm: Double, seconds: Double, seed: Int = 1): Synth {
        val rnd = Random(seed)
        val n = (trueRateHz * seconds).toInt()
        val lines = ArrayList<String>(n)
        val arrivals = ArrayList<Double>(n)
        var i = 0
        while (i < n) {
            val packet = 1 + rnd.nextInt(4)
            val last = minOf(n - 1, i + packet - 1)
            val arrival = last / trueRateHz + 0.002 + rnd.nextDouble() * 0.013
            for (k in i..last) {
                val t = k / trueRateHz
                val pulse = sin(2 * PI * bpm / 60 * t) + 0.3 * sin(2 * PI * 2 * bpm / 60 * t + 0.5)
                val ir = (115_000 + 250 * pulse + rnd.nextDouble(-8.0, 8.0)).toLong()
                val red = (84_000 + 120 * pulse + rnd.nextDouble(-8.0, 8.0)).toLong()
                val ax = (-8.138 + rnd.nextDouble(-0.02, 0.02)).toFloat()
                val ay = (-1.815 + rnd.nextDouble(-0.02, 0.02)).toFloat()
                val az = (5.818 + rnd.nextDouble(-0.02, 0.02)).toFloat()
                lines += firmwareLine(ir, red, ax, ay, az, k.toLong())
                arrivals += arrival
            }
            i = last + 1
        }
        return Synth(lines, arrivals)
    }

    private fun parseAll(s: Synth): List<SensorSample> = s.lines.indices.map { k ->
        val r = SerialLineParser.parse(s.lines[k].trimEnd('\n'), 0L, s.arrivalSec[k])
        (r as SerialLineParser.Result.Sample).sample
    }

    private fun medianBpm(samples: List<SensorSample>, fs: Double): Double {
        val p = PpgPipeline(fs)
        val est = samples.mapNotNull { p.process(it).newHrBpm }
        assertTrue("expected BPM estimates", est.size >= 5)
        return est.sorted()[est.size / 2]
    }

    @Test
    fun `parses the firmware frame into the right channels`() {
        val line = firmwareLine(115684, 84143, -8.138f, -1.815f, 5.818f, 42).trimEnd('\n')
        assertEquals("115684,84143,-8.138,-1.815,5.818,,42", line)
        val s = (SerialLineParser.parse(line, 0L, 1.5) as SerialLineParser.Result.Sample).sample
        assertEquals(115684, s.ir)
        assertEquals(84143, s.red)
        assertEquals(-8.138f, s.accX, 0f)
        assertEquals(-1.815f, s.accY, 0f)
        assertEquals(5.818f, s.accZ, 0f)
        assertNull(s.green)
        assertEquals(42L, s.seq)
        assertEquals(1.5, s.sourceTimeSec!!, 0.0)

        val withGreen = (SerialLineParser.parse("115684,84143,-8.138,-1.815,5.818,120,43", 0L, null)
            as SerialLineParser.Result.Sample).sample
        assertEquals(120, withGreen.green)
    }

    @Test
    fun `nan axes keep the PPG sample, comments and bad lines are not samples`() {
        val nanLine = firmwareLine(115000, 84000, Float.NaN, 0f, 0f, 7).trimEnd('\n')
        assertEquals("115000,84000,nan,nan,nan,,7", nanLine)
        val s = (SerialLineParser.parse(nanLine, 0L, 0.0) as SerialLineParser.Result.Sample).sample
        assertEquals(115000, s.ir)
        assertTrue(s.accX.isNaN() && s.accY.isNaN() && s.accZ.isNaN())

        assertTrue(SerialLineParser.parse("# INIT FAILED: MPU6050 still asleep", 0L, 0.0) is SerialLineParser.Result.Comment)
        assertTrue(SerialLineParser.parse("115684.0,84143,1,2,3", 0L, 0.0) is SerialLineParser.Result.Malformed)
        assertTrue(SerialLineParser.parse("0.636,115684,84143,0,-8.138,-1.815,5.818,9", 0L, 0.0) is SerialLineParser.Result.Malformed)
        // Old 5-field firmware still works.
        assertTrue(SerialLineParser.parse("115684,84143,-8.138,-1.815,5.818", 0L, 0.0) is SerialLineParser.Result.Sample)
    }

    @Test
    fun `nominal 25 Hz rig - measured rate and a sane BPM`() {
        val synth = synthesize(25.0, 72.0, 60.0)
        val samples = parseAll(synth)
        val est = SampleRateEstimator()
        for (s in samples) est.addLive(s.seq!!, s.sourceTimeSec!!)
        val rate = est.liveRateHz()!!
        assertEquals(25.0, rate, 0.05)
        assertEquals(72.0, medianBpm(samples, rate), 2.0)
    }

    @Test
    fun `off-nominal rig (sep16's 16_67 Hz) is measured and followed`() {
        val synth = synthesize(50.0 / 3.0, 72.0, 90.0, seed = 3)
        val samples = parseAll(synth)
        val est = SampleRateEstimator()
        for (s in samples) est.addLive(s.seq!!, s.sourceTimeSec!!)
        val rate = est.liveRateHz()!!
        assertEquals(50.0 / 3.0, rate, 0.05)
        // At the measured rate: right answer. At the old hard-coded 25 Hz: 1.5x too high.
        assertEquals(72.0, medianBpm(samples, rate), 2.0)
        assertEquals(108.0, medianBpm(samples, 25.0), 4.0)
    }

    @Test
    fun `BLE packet layout per docs parses into the right channels`() {
        val buf = ByteBuffer.allocate(18).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(123_456).putInt(115_684).putInt(84_143)
            .putShort(-8138).putShort(-1815).putShort(5818)
        val s = BlePacketParser.parse(buf.array(), 0L)!!
        assertEquals(115684, s.ir)
        assertEquals(84143, s.red)
        assertEquals(-8.138f, s.accX, 1e-6f)
        assertEquals(-1.815f, s.accY, 1e-6f)
        assertEquals(5.818f, s.accZ, 1e-6f)
        assertEquals(123.456, s.sourceTimeSec!!, 1e-9)
        assertNull(BlePacketParser.parse(ByteArray(17), 0L))
    }
}
