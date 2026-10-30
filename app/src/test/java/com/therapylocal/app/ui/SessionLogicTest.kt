package com.therapylocal.app.ui

import com.therapylocal.app.data.Job
import com.therapylocal.app.data.JobStatus
import com.therapylocal.app.jobs.JobGraph
import com.therapylocal.app.jobs.JobPhase
import com.therapylocal.app.jobs.QueueSnapshot
import com.therapylocal.app.jobs.StepProgress
import com.therapylocal.app.ui.logic.SessionLogic
import com.therapylocal.app.ui.logic.SessionLogic.PauseAction
import com.therapylocal.app.ui.logic.SessionLogic.SessionTabTarget
import com.therapylocal.app.ui.models.Patient
import com.therapylocal.app.ui.models.FactItem
import com.therapylocal.app.ui.models.SessionNotes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import kotlin.random.Random

class SessionLogicTest {

    // ---- duration formatting ----

    @Test
    fun minSecBoundaries() {
        assertEquals("00:00", SessionLogic.formatMinSec(0))
        assertEquals("00:01", SessionLogic.formatMinSec(1))
        assertEquals("00:59", SessionLogic.formatMinSec(59))
        assertEquals("01:00", SessionLogic.formatMinSec(60))
        assertEquals("59:59", SessionLogic.formatMinSec(3599))
        assertEquals("60:00", SessionLogic.formatMinSec(3600))
        assertEquals("75:00", SessionLogic.formatMinSec(4500))
        assertEquals("100:00", SessionLogic.formatMinSec(6000))
        assertEquals("1000:01", SessionLogic.formatMinSec(60_001))
    }

    @Test
    fun minSecNegativeAndHugeNeverCrash() {
        assertEquals("00:00", SessionLogic.formatMinSec(-1))
        assertEquals("00:00", SessionLogic.formatMinSec(Long.MIN_VALUE))
        // 9223372036854775807 = 60 * 153722867280912930 + 7
        assertEquals("153722867280912930:07", SessionLogic.formatMinSec(Long.MAX_VALUE))
        // 9223372036854775807 = 3600 * 2562047788015215 + 30 * 60 + 7
        assertEquals("2562047788015215:30:07", SessionLogic.formatHourMinSec(Long.MAX_VALUE))
        assertEquals("00:00:00", SessionLogic.formatHourMinSec(Long.MIN_VALUE))
    }

    @Test
    fun hourMinSecBoundaries() {
        assertEquals("00:00:00", SessionLogic.formatHourMinSec(0))
        assertEquals("00:00:59", SessionLogic.formatHourMinSec(59))
        assertEquals("00:01:00", SessionLogic.formatHourMinSec(60))
        assertEquals("00:59:59", SessionLogic.formatHourMinSec(3599))
        assertEquals("01:00:00", SessionLogic.formatHourMinSec(3600))
        assertEquals("01:01:01", SessionLogic.formatHourMinSec(3661))
        assertEquals("27:46:40", SessionLogic.formatHourMinSec(100_000))
        assertEquals("100:00:00", SessionLogic.formatHourMinSec(360_000))
        assertEquals("00:00:00", SessionLogic.formatHourMinSec(-5))
    }

    @Test
    fun digitsAreAlwaysAsciiRegardlessOfLocale() {
        // Policy: durations always use ASCII digits, even when the device locale uses other numerals.
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-EG"))
            assertEquals("01:01:01", SessionLogic.formatHourMinSec(3661))
            assertEquals("61:01", SessionLogic.formatMinSec(3661))
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test
    fun formatsRoundTripForRandomDurations() {
        val rnd = Random(42)
        repeat(500) {
            val secs = rnd.nextLong(0, 1_000_000)
            val (m, s) = SessionLogic.formatMinSec(secs).split(":").map { it.toLong() }
            assertEquals(secs, m * 60 + s)
            assertTrue(s in 0..59)
            val (h, mm, ss) = SessionLogic.formatHourMinSec(secs).split(":").map { it.toLong() }
            assertEquals(secs, h * 3600 + mm * 60 + ss)
            assertTrue(mm in 0..59 && ss in 0..59)
        }
    }

    // ---- processing banner mapping ----

    private var nextId = 1L
    private val day = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).parse("2026-10-25")!!.time

    private fun job(session: String, status: JobStatus, recordingId: Long? = null, dependsOn: List<Long> = emptyList(), kind: String = JobGraph.EXTRACT) =
        Job(nextId++, kind, session, session.substringBefore('_'), day, recordingId, "$session.md", status, 0, 3, null, nextId, dependsOn)

    private fun banner(vararg jobs: Job, progress: StepProgress? = null, pending: Triple<String, String, String>? = null) =
        SessionLogic.processingByClient(QueueSnapshot.of(jobs.toList(), progress, false), pending)

    @Test
    fun processingOnlySessionsWithWorkLeftAndNoFailureAppear() {
        val map = banner(
            job("a_1", JobStatus.Done),
            job("b_1", JobStatus.Failed),
            job("c_1", JobStatus.Cancelled),
            job("d_1", JobStatus.Pending),
            job("e_1", JobStatus.Running),
            progress = StepProgress(JobPhase.Reading, 1, 2),
        )
        assertEquals(setOf("d", "e"), map.keys)
        assertEquals("Queued", map["d"]!!.stage)
        assertEquals("Reading 1/2", map["e"]!!.stage)
        assertEquals("d_1", map["d"]!!.sessionId)
        assertEquals("2026-10-25", map["d"]!!.date)
        assertEquals("2026-10-25", map["e"]!!.date)
        assertFalse(map["d"]!!.isPendingEnqueue)
        assertFalse(map["e"]!!.isPendingEnqueue)
    }

    @Test
    fun processingStageTextRules() {
        fun stage(j: Job, p: StepProgress? = null) = banner(j, progress = p)[j.clientId]!!.stage
        assertEquals("Queued", stage(job("x_1", JobStatus.Pending)))
        assertEquals("Extracting notes", stage(job("x_1", JobStatus.Running)))
        assertEquals("Transcribing audio", stage(job("x_1", JobStatus.Running, recordingId = 5, kind = JobGraph.TRANSCRIBE)))
        // The later jobs of a recording carry its id too; only transcribe is transcribing.
        assertEquals("Extracting notes", stage(job("x_1", JobStatus.Running, recordingId = 5, kind = JobGraph.COMMIT)))
        assertEquals("Transcribing audio", stage(job("x_1", JobStatus.Running), StepProgress(JobPhase.Transcribing, 1, 3)))
        assertEquals("Reading 1/2", stage(job("x_1", JobStatus.Running, recordingId = 5), StepProgress(JobPhase.Reading, 1, 2)))
        assertEquals("Checking 4/9", stage(job("x_1", JobStatus.Running), StepProgress(JobPhase.Checking, 4, 9)))
    }

    @Test
    fun processingSessionWaitingBehindACancelledJobSaysWaiting() {
        val gone = job("x_1", JobStatus.Cancelled)
        val map = banner(gone, job("x_1", JobStatus.Pending, dependsOn = listOf(gone.id)))
        assertEquals("Waiting", map["x"]!!.stage)
    }

    @Test
    fun processingEmptyPendingDateBecomesToday() {
        assertEquals("Today", banner(pending = Triple("y", "y_1", ""))["y"]!!.date)
        assertEquals("2026-01-02", banner(pending = Triple("y", "y_1", "2026-01-02"))["y"]!!.date)
    }

    @Test
    fun processingLaterSessionOfSameClientOverwritesEarlier() {
        val map = banner(job("g_1", JobStatus.Running), job("g_2", JobStatus.Pending), job("h_1", JobStatus.Pending))
        assertEquals(2, map.size)
        assertEquals("g_2", map["g"]!!.sessionId)
        assertEquals("Queued", map["g"]!!.stage)
        // A finished later session does not hide the still-running earlier one.
        assertEquals("g_1", banner(job("g_1", JobStatus.Running), job("g_2", JobStatus.Done))["g"]!!.sessionId)
    }

    @Test
    fun pendingEnqueueShownOnlyWhenClientHasNoActiveSession() {
        val pending = Triple("alice", "alice_20261025-151000", "2026-10-25")
        val alone = banner(pending = pending)["alice"]!!
        assertEquals("alice_20261025-151000", alone.sessionId)
        assertEquals("Finalizing recording...", alone.stage)
        assertTrue(alone.isPendingEnqueue)

        // An active session for the same client wins over the pending placeholder.
        val withJob = banner(job("alice_0", JobStatus.Running), pending = pending)["alice"]!!
        assertEquals("alice_0", withJob.sessionId)
        assertFalse(withJob.isPendingEnqueue)

        // A finished session for the same client does not block the placeholder.
        assertTrue(banner(job("alice_0", JobStatus.Done), pending = pending)["alice"]!!.isPendingEnqueue)

        // Other clients' sessions coexist with the placeholder.
        assertEquals(setOf("alice", "bob"), banner(job("bob_1", JobStatus.Pending), pending = pending).keys)
    }

    @Test
    fun processingEmptyInputs() {
        assertTrue(banner().isEmpty())
    }

    // ---- patient filtering ----

    private val alex = Patient(id = "p-1", name = "Alex Example Jr.", lastSession = "", sessionCount = 0, facts = listOf(FactItem(1, "Anxiety at work", "Panics before meetings.", "d")))
    private val sam = Patient(id = "p-2", name = "Sam Example", lastSession = "", sessionCount = 0, facts = listOf(FactItem(2, "Burnout", "Took sick leave in May.", "d")))
    private val rene = Patient(
        id = "p-3", name = "René Émile", lastSession = "", sessionCount = 1,
        facts = listOf(FactItem(3, "Sister", "Has a sister called Lotte.", "d")),
        sessions = listOf(SessionNotes(id = "s", date = "d", duration = "10:00", observations = "Talked about Grief; work stress; can't sleep"))
    )
    private val all = listOf(alex, sam, rene)

    private fun names(q: String) = SessionLogic.filterPatients(all, q).map { it.id }

    @Test
    fun filterBlankQueryReturnsEveryoneInOrder() {
        assertEquals(listOf("p-1", "p-2", "p-3"), names(""))
        assertEquals(listOf("p-1", "p-2", "p-3"), names("   "))
        assertEquals(listOf("p-1", "p-2", "p-3"), names("\t\n"))
    }

    @Test
    fun filterIsCaseInsensitiveAndTrimmed() {
        assertEquals(listOf("p-2"), names("sam"))
        assertEquals(listOf("p-2"), names("SAM"))
        assertEquals(listOf("p-2"), names("  SaM  "))
        assertEquals(listOf("p-2"), names("m ex"))
    }

    @Test
    fun filterMatchesNameAndFactHeadingSeparately() {
        assertEquals(listOf("p-1"), names("anxi"))      // fact heading only
        assertEquals(listOf("p-1"), names("alex"))    // name only
        assertEquals(listOf("p-2"), names("burnout"))
    }

    @Test
    fun filterSearchesFactContentAndSessionNotes() {
        assertEquals(listOf("p-3"), names("lotte"))
        assertEquals(listOf("p-1"), names("meetings"))
        assertEquals(listOf("p-3"), names("grief"))
        assertEquals(listOf("p-3"), names("work stress"))
        assertEquals(listOf("p-3"), names("can't sleep"))
    }

    @Test
    fun filterNoMatchIsEmpty() {
        assertTrue(names("zzz").isEmpty())
        assertTrue(SessionLogic.filterPatients(emptyList(), "a").isEmpty())
    }

    @Test
    fun filterMultipleMatchesKeepOrder() {
        assertEquals(listOf("p-1", "p-2", "p-3"), names("e"))
        assertEquals(listOf("p-1", "p-2"), names("x"))
        assertEquals(listOf("p-1", "p-2"), names("example"))
    }

    @Test
    fun filterTreatsRegexCharactersLiterally() {
        assertTrue(names(".*").isEmpty())
        assertTrue(names("j.").isEmpty())   // as a regex this would match "jr"
        assertEquals(listOf("p-1"), names("jr."))
    }

    // ---- session tab routing ----

    @Test
    fun sessionTabRouting() {
        assertEquals(SessionTabTarget.Chooser, SessionLogic.sessionTabTarget(isSessionActive = false, hasSessionPatient = false))
        assertEquals(SessionTabTarget.Chooser, SessionLogic.sessionTabTarget(isSessionActive = false, hasSessionPatient = true))
        assertEquals(SessionTabTarget.Chooser, SessionLogic.sessionTabTarget(isSessionActive = true, hasSessionPatient = false))
        assertEquals(SessionTabTarget.Live, SessionLogic.sessionTabTarget(isSessionActive = true, hasSessionPatient = true))
    }

    // ---- play / pause state machine ----

    /** Drives togglePause the way MindVaultApp does and records the side effects. */
    private class Session {
        var paused = true          // sessions start paused
        var recordingStarted = false
        val actions = mutableListOf<PauseAction>()
        fun tap() {
            val t = SessionLogic.togglePause(paused, recordingStarted)
            actions += t.action
            paused = t.paused
            recordingStarted = t.recordingStarted
        }
    }

    @Test
    fun firstPlayStartsRecordingOnce() {
        val s = Session()
        s.tap()
        assertEquals(listOf(PauseAction.StartRecording), s.actions)
        assertFalse(s.paused)
        assertTrue(s.recordingStarted)
    }

    @Test
    fun pauseThenResumeDoesNotRestartRecording() {
        val s = Session()
        s.tap(); s.tap(); s.tap()
        assertEquals(listOf(PauseAction.StartRecording, PauseAction.PauseRecording, PauseAction.ResumeRecording), s.actions)
        assertFalse(s.paused)
        assertTrue(s.recordingStarted)
    }

    @Test
    fun pausingNeverChangesRecordingStartedFlag() {
        assertEquals(true, SessionLogic.togglePause(paused = false, recordingStarted = true).recordingStarted)
        assertEquals(false, SessionLogic.togglePause(paused = false, recordingStarted = false).recordingStarted)
        assertTrue(SessionLogic.togglePause(paused = false, recordingStarted = false).paused)
    }

    @Test
    fun manyTapsAlternateAndStartExactlyOnce() {
        val s = Session()
        repeat(101) { s.tap() }
        assertEquals(1, s.actions.count { it == PauseAction.StartRecording })
        assertEquals(PauseAction.StartRecording, s.actions.first())
        s.actions.drop(1).forEachIndexed { i, a ->
            assertEquals(if (i % 2 == 0) PauseAction.PauseRecording else PauseAction.ResumeRecording, a)
        }
        assertFalse(s.paused) // odd number of taps -> playing
    }
}
