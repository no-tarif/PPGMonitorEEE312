package com.eee312.ppgmonitor.dsp.spo2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveSpo2TrackerTest {

    @Test
    fun `no snapshot before warmup plus window elapses`() {
        val fs = 25.0
        val tracker = LiveSpo2Tracker(fs) // default cfg: warmup 30s + window 12s = 42s
        val n = (41 * fs).toInt() // 41s - just under the 42s threshold
        var gotSnapshot = false
        repeat(n) { i -> if (tracker.addSample(90000 + i, 50000 + i) != null) gotSnapshot = true }
        assertTrue("expected no snapshot before 42s", !gotSnapshot)
    }

    @Test
    fun `first snapshot arrives once warmup plus window elapses, then every step`() {
        val fs = 25.0
        val tracker = LiveSpo2Tracker(fs)
        var snapshotCount = 0
        var firstAtSample = -1
        val n = (50 * fs).toInt() // 50s total
        for (i in 0 until n) {
            val snap = tracker.addSample(90000 + (i % 100), 50000 + (i % 50))
            if (snap != null) {
                snapshotCount++
                if (firstAtSample < 0) firstAtSample = i
                assertEquals(300, snap.ir.size) // windowSec(12) * fs(25)
                assertEquals(300, snap.red.size)
            }
        }
        // First snapshot no earlier than 42s worth of samples (index 1050).
        assertTrue("firstAtSample=$firstAtSample", firstAtSample >= (42 * fs).toInt() - 1)
        // From 42s to 50s is 8s of steps every 2s -> ~4 snapshots.
        assertTrue("snapshotCount=$snapshotCount", snapshotCount in 3..5)
    }

    @Test
    fun `null red never produces a snapshot`() {
        val tracker = LiveSpo2Tracker(25.0)
        val n = (60 * 25).toInt()
        for (i in 0 until n) {
            assertNull(tracker.addSample(90000, null))
        }
    }

    @Test
    fun `reset clears accumulated state`() {
        val fs = 25.0
        val tracker = LiveSpo2Tracker(fs)
        repeat((50 * fs).toInt()) { i -> tracker.addSample(90000 + i, 50000 + i) }
        tracker.reset()
        var gotSnapshot = false
        repeat((41 * fs).toInt()) { i -> if (tracker.addSample(90000 + i, 50000 + i) != null) gotSnapshot = true }
        assertTrue(!gotSnapshot)
    }
}
