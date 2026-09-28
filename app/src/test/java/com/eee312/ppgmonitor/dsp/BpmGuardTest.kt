package com.eee312.ppgmonitor.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Estimates 1 s apart unless stated otherwise. */
class BpmGuardTest {

    private fun offline(vararg bpm: Double): List<Double> =
        BpmGuard.Offline.run(bpm.mapIndexed { i, b -> BpmGuard.Offline.Estimate(i.toDouble(), b) }).map { it.bpm }

    @Test
    fun `the user's example - a one-off 100 between 80 and 82 becomes 81`() {
        assertEquals(listOf(80.0, 80.0, 80.0, 81.0, 82.0), offline(80.0, 80.0, 80.0, 100.0, 82.0))
    }

    @Test
    fun `a one-off dip is removed too`() {
        assertEquals(listOf(80.0, 80.0, 80.0, 80.0, 80.0), offline(80.0, 80.0, 80.0, 60.0, 80.0))
    }

    @Test
    fun `a real change is accepted and ramped at no more than 3 BPM per second`() {
        val out = offline(80.0, 80.0, 100.0, 101.0, 102.0, 102.0, 102.0, 102.0, 102.0, 102.0, 102.0, 102.0)
        for (i in 1 until out.size) assertTrue("step ${out[i] - out[i - 1]}", out[i] - out[i - 1] <= 3.0 + 1e-9)
        assertEquals(83.0, out[2], 1e-9) // the change was accepted (not held at 80) ...
        assertEquals(102.0, out.last(), 1e-9) // ... and reached
    }

    @Test
    fun `a 75 to 120 rise over 60 s is followed with less than 5 s lag`() {
        val truth = { t: Double -> 75.0 + 45.0 * (t / 60.0).coerceIn(0.0, 1.0) }
        val est = (0..80).map { BpmGuard.Offline.Estimate(it.toDouble(), truth(it.toDouble())) }
        val out = BpmGuard.Offline.run(est)
        for (r in out) {
            // output at t is at least the truth 5 s earlier
            assertTrue("t=${r.tSec} out=${r.bpm}", r.bpm >= truth(r.tSec - 5.0) - 1e-9)
        }
        assertEquals(120.0, out.last().bpm, 1e-9)
    }

    @Test
    fun `live - the same example, display never shows the spike`() {
        val g = BpmGuard()
        val records = ArrayList<BpmGuard.Record>()
        val shown = ArrayList<Double?>()
        listOf(80.0, 80.0, 80.0, 100.0, 82.0).forEachIndexed { i, b ->
            records += g.onEstimate(i.toDouble(), b)
            shown += g.displayAt(i.toDouble())
        }
        assertEquals(listOf(80.0, 80.0, 80.0, 81.0, 82.0), records.sortedBy { it.tSec }.map { it.bpm })
        assertNull(shown[0]) // start: first estimate not believed until the next agrees
        assertTrue(shown.drop(1).all { it != null && it <= 82.0 })
    }

    @Test
    fun `live - signal loss holds the last value 3 s, then dashes, then needs agreement`() {
        val g = BpmGuard()
        for (i in 0..5) { g.onEstimate(i.toDouble(), 72.0); g.displayAt(i.toDouble()) }
        g.signalLost(6.0)
        assertEquals(72.0, g.displayAt(7.0)!!, 1e-9)
        assertEquals(72.0, g.displayAt(9.0)!!, 1e-9)
        assertNull(g.displayAt(9.5))
        // back: one estimate is not enough ...
        g.onEstimate(20.0, 90.0)
        assertNull(g.displayAt(20.0))
        // ... the agreeing second one is, with no ramp from the stale 72
        g.onEstimate(22.0, 92.0)
        assertEquals(92.0, g.displayAt(22.0)!!, 1e-9)
    }

    @Test
    fun `invalid estimates are ignored - out of range, too filled, window with loss`() {
        val g = BpmGuard()
        g.onEstimate(0.0, 70.0); g.onEstimate(1.0, 70.0)
        assertTrue(g.onEstimate(2.0, 230.0).isEmpty())
        assertTrue(g.onEstimate(3.0, 35.0).isEmpty())
        assertTrue(g.onEstimate(4.0, 90.0, filledFraction = 0.3).isEmpty())
        assertTrue(g.onEstimate(5.0, 90.0, windowHasLoss = true).isEmpty())
        assertEquals(70.0, g.displayAt(5.0)!!, 1e-9)
    }

    @Test
    fun `pending expires after 5 s without confirmation`() {
        val g = BpmGuard()
        g.onEstimate(0.0, 70.0); g.onEstimate(1.0, 70.0); g.onEstimate(2.0, 70.0)
        assertTrue(g.onEstimate(3.0, 100.0).isEmpty())          // pending
        val r = g.onEstimate(9.0, 71.0)                         // 6 s later: expired first
        assertEquals(listOf(70.0, 71.0), r.map { it.bpm })
        assertNotNull(g.trend)
        assertTrue(abs(g.trend!! - 70.0) < 1.01)
    }
}
