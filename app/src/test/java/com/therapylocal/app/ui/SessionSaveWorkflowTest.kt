package com.therapylocal.app.ui

import com.therapylocal.app.memory.JobStatus
import com.therapylocal.app.memory.MemoryProcessor
import com.therapylocal.app.memory.SessionJob
import com.therapylocal.app.ui.logic.SessionLogic
import com.therapylocal.app.ui.logic.SessionLogic.PauseAction
import com.therapylocal.app.ui.logic.SessionLogic.SessionTabTarget
import com.therapylocal.app.ui.models.Patient
import com.therapylocal.app.ui.models.SessionRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.random.Random

class SessionSaveWorkflowTest {

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
        assertTrue(SessionLogic.formatMinSec(Long.MAX_VALUE).matches(Regex("\\d+:\\d{2}")))
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

    // ---- MemoryProcessor ids ----

    @Test
    fun clientIdIsLowercaseAlphanumericOnly() {
        assertEquals("sarahchen", MemoryProcessor.clientId("Sarah Chen"))
        assertEquals("marcusvancephd", MemoryProcessor.clientId("Marcus Vance, PhD"))
        assertEquals("georgem", MemoryProcessor.clientId("GEORGE M."))
        assertEquals("abc123", MemoryProcessor.clientId("a-b_c 1.2/3"))
        assertEquals("o", MemoryProcessor.clientId("O'"))
    }

    @Test
    fun clientIdFallsBackWhenNothingUsable() {
        assertEquals("client", MemoryProcessor.clientId(""))
        assertEquals("client", MemoryProcessor.clientId("   "))
        assertEquals("client", MemoryProcessor.clientId("...!"))
        assertEquals("client", MemoryProcessor.clientId("😀"))
        assertEquals("client", MemoryProcessor.clientId("山田"))
    }

    @Test
    fun clientIdOutputAlwaysMatchesIdRule() {
        val rnd = Random(7)
        val alphabet = "abcXYZ019 ,.-_'éüß中😀\t"
        repeat(300) {
            val name = String(CharArray(rnd.nextInt(0, 20)) { alphabet[rnd.nextInt(alphabet.length)] })
            val id = MemoryProcessor.clientId(name)
            assertTrue("'$id' from '$name'", id.matches(Regex("[a-z0-9]+")))
        }
    }

    @Test
    fun sessionIdFormatAndClientPrefix() {
        val t = 1771934400000L
        val id = MemoryProcessor.sessionId("sarahchen", t)
        assertTrue(id, id.matches(Regex("sarahchen_\\d{8}-\\d{6}")))
        assertEquals("sarahchen", id.substringBefore('_'))
        // The id encodes the start time to the second (default zone, same as production).
        val expected = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(java.util.Date(t))
        assertEquals("sarahchen_$expected", id)
    }

    @Test
    fun sessionIdsSortChronologicallyAndDifferPerSecond() {
        val base = 1771934400000L
        val a = MemoryProcessor.sessionId("c", base)
        val b = MemoryProcessor.sessionId("c", base + 1000)
        val sameSecond = MemoryProcessor.sessionId("c", base + 999)
        assertTrue(a < b)
        assertEquals(a, sameSecond)
        assertTrue(MemoryProcessor.sessionId("c", base) != MemoryProcessor.sessionId("d", base))
    }

    // ---- SessionJob defaults ----

    @Test
    fun newJobIsQueuedWithNoStage() {
        val job = SessionJob(id = "c_1", client = "c", file = "c_1.md", date = "2026-10-25")
        assertEquals(JobStatus.Queued, job.status)
        assertEquals("", job.stage)
        assertEquals(0L, job.recordingId)
        assertNull(job.error)
    }

    // ---- processing banner mapping ----

    private fun job(
        id: String, client: String, status: JobStatus,
        stage: String = "", date: String = "2026-10-25", recordingId: Long = 0
    ) = SessionJob(id = id, client = client, file = "$id.md", date = date, status = status, stage = stage, recordingId = recordingId)

    @Test
    fun processingOnlyQueuedAndRunningJobsAppear() {
        val jobs = listOf(
            job("a_1", "a", JobStatus.Done),
            job("b_1", "b", JobStatus.Failed),
            job("c_1", "c", JobStatus.Cancelled),
            job("d_1", "d", JobStatus.Queued),
            job("e_1", "e", JobStatus.Running, stage = "Reading 1/2"),
        )
        val map = SessionLogic.processingByClient(jobs, null)
        assertEquals(setOf("d", "e"), map.keys)
        assertEquals("Queued", map["d"]!!.stage)
        assertEquals("Reading 1/2", map["e"]!!.stage)
        assertEquals("d_1", map["d"]!!.sessionId)
        assertFalse(map["d"]!!.isPendingEnqueue)
        assertFalse(map["e"]!!.isPendingEnqueue)
    }

    @Test
    fun processingStageTextRules() {
        fun stage(j: SessionJob) = SessionLogic.processingByClient(listOf(j), null)[j.client]!!.stage
        assertEquals("Queued", stage(job("x_1", "x", JobStatus.Queued)))
        // An explicit stage always wins, even for a queued job.
        assertEquals("Waiting", stage(job("x_1", "x", JobStatus.Queued, stage = "Waiting")))
        // Whitespace-only stage counts as blank.
        assertEquals("Queued", stage(job("x_1", "x", JobStatus.Queued, stage = "  ")))
        assertEquals("Extracting facts", stage(job("x_1", "x", JobStatus.Running)))
        assertEquals("Transcribing audio", stage(job("x_1", "x", JobStatus.Running, recordingId = 5)))
        assertEquals("Reading 1/2", stage(job("x_1", "x", JobStatus.Running, stage = "Reading 1/2", recordingId = 5)))
    }

    @Test
    fun processingEmptyDateBecomesToday() {
        val map = SessionLogic.processingByClient(listOf(job("x_1", "x", JobStatus.Queued, date = "")), null)
        assertEquals("Today", map["x"]!!.date)
        val p = SessionLogic.processingByClient(emptyList(), Triple("y", "y_1", ""))
        assertEquals("Today", p["y"]!!.date)
        assertEquals("2026-01-02", SessionLogic.processingByClient(emptyList(), Triple("y", "y_1", "2026-01-02"))["y"]!!.date)
    }

    @Test
    fun processingLaterJobOfSameClientOverwritesEarlier() {
        val jobs = listOf(
            job("g_1", "g", JobStatus.Running, stage = "first"),
            job("g_2", "g", JobStatus.Queued),
            job("h_1", "h", JobStatus.Queued),
        )
        val map = SessionLogic.processingByClient(jobs, null)
        assertEquals(2, map.size)
        assertEquals("g_2", map["g"]!!.sessionId)
        assertEquals("Queued", map["g"]!!.stage)
        // A finished later job does not hide the still-running earlier one.
        val map2 = SessionLogic.processingByClient(listOf(job("g_1", "g", JobStatus.Running, stage = "first"), job("g_2", "g", JobStatus.Done)), null)
        assertEquals("g_1", map2["g"]!!.sessionId)
    }

    @Test
    fun pendingEnqueueShownOnlyWhenClientHasNoActiveJob() {
        val pending = Triple("alice", "alice_20261025-151000", "2026-10-25")
        val alone = SessionLogic.processingByClient(emptyList(), pending)["alice"]!!
        assertEquals("alice_20261025-151000", alone.sessionId)
        assertEquals("Finalizing recording...", alone.stage)
        assertTrue(alone.isPendingEnqueue)

        // Active job for the same client wins over the pending placeholder.
        val withJob = SessionLogic.processingByClient(listOf(job("alice_0", "alice", JobStatus.Running, stage = "Reading")), pending)["alice"]!!
        assertEquals("alice_0", withJob.sessionId)
        assertFalse(withJob.isPendingEnqueue)

        // A Done job for the same client does not block the placeholder.
        val withDone = SessionLogic.processingByClient(listOf(job("alice_0", "alice", JobStatus.Done)), pending)["alice"]!!
        assertTrue(withDone.isPendingEnqueue)

        // Other clients' jobs coexist with the placeholder.
        val both = SessionLogic.processingByClient(listOf(job("bob_1", "bob", JobStatus.Queued)), pending)
        assertEquals(setOf("alice", "bob"), both.keys)
    }

    @Test
    fun processingEmptyInputs() {
        assertTrue(SessionLogic.processingByClient(emptyList(), null).isEmpty())
    }

    // ---- patient filtering ----

    private val george = Patient(id = "p-1", name = "George M.", age = 41, presenting = "Anxiety", lastSession = "", sessionCount = 0, preSessionNote = "")
    private val sarah = Patient(id = "p-2", name = "Sarah Chen", age = 29, presenting = "Burnout", lastSession = "", sessionCount = 0, preSessionNote = "")
    private val rene = Patient(
        id = "p-3", name = "René Émile", age = 35, presenting = "Depression", lastSession = "", sessionCount = 1, preSessionNote = "",
        facts = listOf("Has a sister called Lotte"),
        sessions = listOf(SessionRecord(date = "d", duration = "10:00", summary = "Talked about Grief", topics = listOf("Work stress"), verbatimQuotes = listOf("I can't sleep")))
    )
    private val all = listOf(george, sarah, rene)

    private fun names(q: String) = SessionLogic.filterPatients(all, q).map { it.id }

    @Test
    fun filterBlankQueryReturnsEveryoneInOrder() {
        assertEquals(listOf("p-1", "p-2", "p-3"), names(""))
        assertEquals(listOf("p-1", "p-2", "p-3"), names("   "))
        assertEquals(listOf("p-1", "p-2", "p-3"), names("\t\n"))
    }

    @Test
    fun filterIsCaseInsensitiveAndTrimmed() {
        assertEquals(listOf("p-2"), names("sarah"))
        assertEquals(listOf("p-2"), names("SARAH"))
        assertEquals(listOf("p-2"), names("  SaRaH  "))
        assertEquals(listOf("p-2"), names("h ch"))
    }

    @Test
    fun filterMatchesNameAndPresentingSeparately() {
        assertEquals(listOf("p-1"), names("anxi"))      // presenting only
        assertEquals(listOf("p-1"), names("george"))    // name only
        assertEquals(listOf("p-2"), names("burnout"))
    }

    @Test
    fun filterSearchesFactsAndSessionContent() {
        assertEquals(listOf("p-3"), names("lotte"))
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
        assertEquals(listOf("p-1", "p-3"), names("g"))
    }

    @Test
    fun filterAccentsAreExactNotFolded() {
        // Current rule: lowercase-contains only, accents are significant.
        assertEquals(listOf("p-3"), names("émile"))
        assertEquals(listOf("p-3"), names("ÉMILE"))
        assertTrue(names("emile").isEmpty())
        assertTrue(names("rene").isEmpty())
    }

    @Test
    fun filterTreatsRegexCharactersLiterally() {
        assertTrue(names(".*").isEmpty())
        assertEquals(listOf("p-1"), names("m."))
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
