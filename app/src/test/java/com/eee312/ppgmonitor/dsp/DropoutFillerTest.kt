package com.eee312.ppgmonitor.dsp

import com.eee312.ppgmonitor.model.SensorSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

class DropoutFillerTest {

    private val fs = 25.0

    private fun ppg(i: Int) = (115_000 + 300 * sin(2 * PI * 1.2 * i / fs)).roundToInt()

    private fun sample(i: Int, ir: Int = ppg(i)) =
        SensorSample(0L, ir, ir - 31_000, -8.1f + 0.01f * (i % 7), -1.8f, 5.8f, sourceTimeSec = i / fs)

    @Test
    fun `clean data passes through untouched`() {
        val f = DropoutFiller(fs)
        for (i in 0 until 500) {
            val s = sample(i)
            val outs = f.process(s)
            assertEquals(1, outs.size)
            assertSame(s, outs[0].dsp)
            assertEquals(0, outs[0].flags)
        }
        assertEquals(0L, f.badSlots)
    }

    @Test
    fun `0_3 s of zeros in IR are filled with the previous 1 s average`() {
        val f = DropoutFiller(fs)
        for (i in 0 until 100) f.process(sample(i))
        val expectedIr = (75 until 100).map { ppg(it) }.average().roundToInt()
        val expectedRed = (75 until 100).map { ppg(it) - 31_000 }.average().roundToInt()
        val outs = (100 until 108).flatMap { f.process(sample(it, ir = 0)) } // 8 samples = 0.32 s
        assertEquals(8, outs.size)
        for (o in outs) {
            assertTrue(o.isFilled && !o.isLost)
            assertEquals(expectedIr, o.dsp.ir)
            assertEquals(expectedRed, o.dsp.red)
            assertEquals(0, o.raw!!.ir) // what arrived is kept for Export
        }
        // the first 0.2 s after recovery is still treated as bad (settling)
        val after = (108 until 120).flatMap { f.process(sample(it)) }
        assertTrue(after.take(5).all { it.isFilled && it.flags and SampleFlags.SETTLING != 0 })
        assertTrue(after.drop(5).all { it.flags == 0 })
    }

    @Test
    fun `a 1_5 s gap is marked lost, not filled`() {
        val f = DropoutFiller(fs)
        for (i in 0 until 100) f.process(sample(i))
        val gap = (100 until 138).flatMap { f.process(sample(it, ir = 500)) } // 38 samples = 1.52 s
        val back = (138 until 160).flatMap { f.process(sample(it)) }
        val all = gap + back
        val flags = all.map { it.flags }.toIntArray()
        // live: the first second was filled, then it turned into "signal lost"
        assertTrue(gap.first().isFilled)
        assertEquals(1, all.count { it.lostStarted })
        assertEquals(1, all.count { it.recovered })
        // afterwards the whole gap (and the settling after it) is LOST, nothing filled
        DropoutFiller.relabelLostRuns(flags)
        val badRun = flags.takeWhile { it and (SampleFlags.FILLED or SampleFlags.LOST) != 0 }
        assertTrue(badRun.size >= 38)
        assertTrue(badRun.all { it and SampleFlags.LOST != 0 && it and SampleFlags.FILLED == 0 })
    }

    @Test
    fun `missing samples (time gap) are inserted and filled`() {
        val f = DropoutFiller(fs)
        for (i in 0..60) f.process(sample(i))
        val outs = f.process(sample(64)) // 61, 62, 63 never arrived
        assertEquals(4, outs.size)
        assertTrue(outs.take(3).all { it.raw == null && it.flags and SampleFlags.INSERTED != 0 && it.isFilled })
        assertEquals(3, outs.count { it.raw == null })
    }

    @Test
    fun `seq numbers find missing lines even with bursty arrival times`() {
        val f = DropoutFiller(fs, gapFromTime = false)
        for (i in 0 until 60) f.process(sample(i).copy(seq = i.toLong(), sourceTimeSec = (i / 4) * 0.16))
        val outs = f.process(sample(62).copy(seq = 62L, sourceTimeSec = 2.4))
        assertEquals(3, outs.size)
    }

    @Test
    fun `bad accelerometer is replaced by the last good axes and flagged`() {
        val f = DropoutFiller(fs)
        for (i in 0 until 30) f.process(sample(i))
        val nan = f.process(sample(30).copy(accX = Float.NaN, accY = Float.NaN, accZ = Float.NaN))[0]
        assertTrue(nan.flags and SampleFlags.ACC_BAD != 0)
        assertEquals(sample(29).accX, nan.dsp.accX, 0f)
        val zero = f.process(sample(31).copy(accX = 0f, accY = 0f, accZ = 0f))[0]
        assertTrue(zero.flags and SampleFlags.ACC_BAD != 0)
        assertEquals(0, nan.flags and SampleFlags.FILLED) // the PPG sample itself is fine
    }
}
