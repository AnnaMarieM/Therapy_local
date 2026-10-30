package com.therapylocal.app.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionModelTest {

    // ---- clock ----

    @Test
    fun clockCountsOnlyWhileRunning() {
        var c = SessionClock.started(1_000)
        assertEquals(0, c.elapsedMs(1_000))
        assertEquals(4_000, c.elapsedMs(5_000))
        c = c.pause(5_000)
        assertEquals(4_000, c.elapsedMs(60_000)) // paused time does not count
        c = c.resume(60_000)
        assertEquals(6_500, c.elapsedMs(62_500))
    }

    @Test
    fun clockPauseAndResumeAreIdempotent() {
        val c = SessionClock.started(0).pause(2_000)
        assertEquals(c, c.pause(9_000))
        val r = c.resume(3_000)
        assertEquals(r, r.resume(8_000))
        assertEquals(2_000 + 5_000, r.elapsedMs(8_000))
    }

    @Test
    fun clockNeverGoesBackwards() {
        // A "now" before the start (clock read on another thread) counts as no time rather than negative time.
        assertEquals(0, SessionClock.started(10_000).elapsedMs(9_000))
        assertEquals(0, SessionClock().elapsedMs(123))
    }

    // ---- state ----

    @Test
    fun onlyIdleCanStart() {
        assertTrue(SessionState().canStart)
        for (p in listOf(SessionPhase.Recording, SessionPhase.Paused, SessionPhase.Finishing)) assertFalse(p.name, SessionState(p).canStart)
    }

    @Test
    fun activeMeansRecordingOrPaused() {
        assertTrue(SessionState(SessionPhase.Recording).active)
        assertTrue(SessionState(SessionPhase.Paused).active)
        assertFalse(SessionState(SessionPhase.Finishing).active)
        assertFalse(SessionState().active)
    }

    // ---- level ----

    @Test
    fun levelSilenceAndFullScale() {
        assertEquals(0f, AudioLevel.of(ShortArray(320), 320), 0f)
        assertEquals(0f, AudioLevel.of(ShortArray(0), 0), 0f)
        assertEquals(1f, AudioLevel.of(ShortArray(320) { Short.MAX_VALUE }, 320), 0.001f)
    }

    @Test
    fun levelIsPerceptualAndClamped() {
        // -30 dBFS sits halfway between the -60 dB floor and full scale.
        assertEquals(0.5f, AudioLevel.fromRms(Math.pow(10.0, -30.0 / 20)), 0.001f)
        assertEquals(0f, AudioLevel.fromRms(1e-9), 0f)
        assertEquals(0f, AudioLevel.fromRms(Double.NaN), 0f)
        assertEquals(1f, AudioLevel.fromRms(4.0), 0f)
    }

    @Test
    fun levelReadsOnlyTheFirstNSamples() {
        val buf = ShortArray(640).also { b -> for (i in 320 until 640) b[i] = Short.MAX_VALUE }
        assertEquals(0f, AudioLevel.of(buf, 320), 0f)
        // n larger than the buffer never reads past it
        assertTrue(AudioLevel.of(ShortArray(10) { 1000 }, 50) > 0f)
    }
}
