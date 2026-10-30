package com.therapylocal.app.ui

import com.therapylocal.app.jobs.JobPhase
import com.therapylocal.app.jobs.SessionProgress
import com.therapylocal.app.jobs.StepProgress
import com.therapylocal.app.perf.LiveSpeed
import com.therapylocal.app.perf.PerMinute
import com.therapylocal.app.ui.logic.Evidence
import com.therapylocal.app.ui.logic.Progress
import com.therapylocal.app.ui.logic.RateMeter
import com.therapylocal.app.ui.logic.SessionLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressTest {
    @Test
    fun timeLeftIsRoundedUpNeverDown() {
        assertEquals("about 5 s left", Progress.left(0))
        assertEquals("about 5 s left", Progress.left(1_000))
        assertEquals("about 45 s left", Progress.left(41_000))
        assertEquals("about 1 min left", Progress.left(60_000))
        assertEquals("about 2 min left", Progress.left(61_000))
        assertEquals("about 1 h 1 min left", Progress.left(3_660_000))
    }

    @Test
    fun countsReadAtAGlance() {
        assertEquals("0", Progress.count(0.0))
        assertEquals("4.5", Progress.count(4.46))
        assertEquals("14", Progress.count(14.2))
        assertEquals("1,240", Progress.count(1_240.4))
        assertEquals("12k", Progress.count(12_300.0))
    }

    @Test
    fun theDetailSaysMeasuringUntilThereIsARate() {
        assertEquals("Step 2/5 · 3/12 chunks · measuring…", Progress.detail(2, 5, JobPhase.Transcribing, 3, 12, null))
        assertEquals("Step 3/5 · 1/1 passage · about 10 s left", Progress.detail(3, 5, JobPhase.Reading, 1, 1, 9_000))
    }

    @Test
    fun aRateNeedsTwoDifferentCountsAndIsPadded() {
        val m = RateMeter()
        m.see("a", 0, 10, now = 0)
        assertNull("one point is not a rate", m.leftMs())
        m.see("a", 0, 10, now = 5_000)
        assertNull("no step done yet", m.leftMs())
        m.see("a", 2, 10, now = 20_000) // 10 s a step, 8 to go: 80 s, padded by 1.2
        assertEquals(96_000L, m.leftMs())
        m.see("b", 0, 4, now = 21_000)
        assertNull("another job starts over", m.leftMs())
    }

    @Test
    fun aRunningSessionShowsWhatWasMeasuredAndSaysWhatIsMissing() {
        val s = SessionProgress("s1", "ana", 0, total = 5, done = 2, failed = 0, blocked = 0, running = true)
        val lines = SessionLogic.evidenceLines(
            s, 0, StepProgress(JobPhase.Reading, 3, 10),
            Evidence(live = LiveSpeed(1_240.0, 14.2, 1_000, 0), perPassage = 20_000L to 1_100L, measured = 1),
        )
        // 7 passages at 20 s each, padded by 1.2: 168 s.
        assertEquals("Step 3/5 · 3/10 passages · about 3 min left", lines[0])
        assertEquals("Reading 1,240 tokens/s · writing 14 tokens/s", lines[1])
        assertEquals("About 7,700 tokens left to read", lines[2])
        assertEquals("Time left: after 2 sessions are measured (1 so far)", lines[3])
    }

    @Test
    fun aWaitingSessionGetsItsTimeFromItsInterviewLengthOnlyOnceThereIsHistory() {
        val s = SessionProgress("s2", "ben", 0, total = 5, done = 0, failed = 0, blocked = 0, running = false)
        val none = SessionLogic.evidenceLines(s, 1, null, Evidence(measured = 0, audioMs = mapOf("s2" to 600_000)))
        assertEquals(listOf("Step 1/5 · starts after 1 session", "Time left: after 2 sessions are measured (0 so far)"), none)
        // 10 min of interview at 30 s of processing a minute, padded: 6 min.
        val known = SessionLogic.evidenceLines(s, 0, null, Evidence(perMinute = PerMinute(3, 30_000.0, 900.0, 60.0), measured = 3, audioMs = mapOf("s2" to 600_000)))
        assertEquals("Step 1/5 · starts next", known[0])
        assertEquals("10 min of interview · about 6 min to process once started", known[1])
        assertTrue(known.size == 2)
    }
}
