package com.eee312.ppgmonitor.dsp.spo2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveSpo2GuardTest {

    @Test
    fun `needs two agreeing windows, then smooths`() {
        val g = LiveSpo2Guard()
        assertNull(g.onValid(97.0))
        assertEquals(97.5, g.onValid(98.0)!!, 1e-9)
        assertEquals(97.33, g.onValid(97.0)!!, 0.01)
    }

    @Test
    fun `a one-window jump of more than 3 points is held, a confirmed one accepted`() {
        val g = LiveSpo2Guard()
        g.onValid(97.0); g.onValid(97.0)
        assertEquals(97.0, g.onValid(90.0)!!, 1e-9)   // held
        assertEquals(97.0, g.onValid(97.0)!!, 1e-9)   // it was a one-off: dropped
        g.onValid(91.0)                                // new level ...
        assertEquals(90.5, g.onValid(90.0)!!, 1e-9)   // ... confirmed by the next window
    }

    @Test
    fun `no stale value - cleared after invalid windows or a dropout`() {
        val g = LiveSpo2Guard()
        g.onValid(97.0); g.onValid(97.0)
        assertEquals(97.0, g.onInvalid()!!, 1e-9)
        assertNull(g.onInvalid())
        g.onValid(97.0); g.onValid(97.0)
        g.clear()
        assertNull(g.shown)
    }

    @Test
    fun `never shows above 100`() {
        val g = LiveSpo2Guard()
        g.onValid(100.0); g.onValid(100.0)
        assertEquals(100.0, g.shown!!, 1e-9)
    }
}
