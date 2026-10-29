package com.therapylocal.app.jobs

import com.therapylocal.app.data.JobStatus
import com.therapylocal.app.testsupport.FakeJobRepo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class JobGraphTest {
    private lateinit var repo: FakeJobRepo
    private lateinit var q: JobQueue
    private var now = 1000L

    @Before
    fun setUp() {
        repo = FakeJobRepo()
        q = JobQueue(repo, clock = { now++ })
        repo.sessions["a_1"] = "a" to 1L
        repo.sessions["a_2"] = "a" to 2L
        repo.sessions["a_3"] = "a" to 3L
    }

    private fun job(id: Long) = repo.jobs().first { it.id == id }

    /** Runs the queue like the runner does; [onRun] plays the handler (a window job expands the graph with [windows] windows). */
    private fun drain(windows: Map<String, Int> = emptyMap(), previous: (String) -> String? = { null }, onRun: (com.therapylocal.app.data.Job) -> Unit = {}): List<String> {
        val ran = ArrayList<String>()
        while (true) {
            val j = q.claimNext { true } ?: break
            ran += "${j.kind}(${j.sessionId}${if (j.ref.isEmpty()) "" else ",${j.ref}"})"
            if (j.kind == JobGraph.WINDOW) JobGraph.expand(q, j, windows[j.sessionId] ?: 0, previous(j.sessionId))
            onRun(j)
            q.complete(j)
        }
        return ran
    }

    @Test
    fun aRecordingRunsTranscribeWindowExtractsCommitThenCleanup() {
        JobGraph.enqueueRecording(q, "a_1", recording = 7)
        val ran = drain(windows = mapOf("a_1" to 2))
        assertEquals(listOf("transcribe(a_1)", "window(a_1)", "extract(a_1,1)", "extract(a_1,2)", "commit(a_1)", "cleanup(a_1)"), ran)
        val all = repo.jobs()
        assertTrue(all.all { it.status == JobStatus.Done })
        // The audio's jobs carry the recording, so cleanup knows what to delete and startup knows the recording is queued.
        assertEquals(setOf(JobGraph.TRANSCRIBE, JobGraph.WINDOW, JobGraph.COMMIT, JobGraph.CLEANUP), all.filter { it.recordingId == 7L }.map { it.kind }.toSet())
    }

    @Test
    fun theCommitWaitsForEveryExtractAndCleanupForTheCommit() {
        val w = JobGraph.enqueueRecording(q, "a_1", 7)
        q.claimNext { true }!!.also { q.complete(it) } // transcribe
        val window = q.claimNext { true }!!
        assertEquals(w, window.id)
        JobGraph.expand(q, window, 2, null)
        q.complete(window)
        val byKind = repo.jobs().groupBy { it.kind }
        val extracts = byKind.getValue(JobGraph.EXTRACT).map { it.id }
        val commit = byKind.getValue(JobGraph.COMMIT).single()
        assertEquals((listOf(w) + extracts).toSet(), commit.dependsOn.toSet())
        assertEquals(listOf(commit.id), byKind.getValue(JobGraph.CLEANUP).single().dependsOn)
        // An extract fails for good: the commit and cleanup stay pending, so the audio is kept.
        val e1 = q.claimNext { true }!!
        q.fail(e1, "LLM", permanent = true)
        q.claimNext { true }!!.also { q.complete(it) } // the other extract
        assertNull(q.claimNext { true })
        assertEquals(JobStatus.Pending, job(byKind.getValue(JobGraph.CLEANUP).single().id).status)
    }

    @Test
    fun aTranscriptOnlySessionHasNoCleanup() {
        JobGraph.enqueueTranscript(q, "a_1")
        assertEquals(listOf("window(a_1)", "extract(a_1,1)", "commit(a_1)"), drain(windows = mapOf("a_1" to 1)))
    }

    @Test
    fun aCommitWaitsForTheUnfinishedCommitOfTheSessionBefore() {
        JobGraph.enqueueTranscript(q, "a_1")
        val w1 = q.claimNext { true }!!
        JobGraph.expand(q, w1, 1, null)
        q.complete(w1)
        val commit1 = q.unfinished(JobGraph.COMMIT, "a_1")!!
        // a_2 is windowed while a_1 has not committed yet.
        JobGraph.enqueueTranscript(q, "a_2")
        val w2 = q.claimNext { it == JobGraph.WINDOW }!!
        JobGraph.expand(q, w2, 1, previousSession = "a_1")
        q.complete(w2)
        val commit2 = q.unfinished(JobGraph.COMMIT, "a_2")!!
        assertTrue(commit1.id in commit2.dependsOn)
        // a_1's extract fails for good, so its commit is stuck: a_2 still extracts, but its commit waits.
        q.fail(q.claimNext { true }!!.also { assertEquals("1" to "a_1", it.ref to it.sessionId) }, "LLM", permanent = true)
        assertEquals(listOf("extract(a_2,1)"), drain())
        assertEquals(JobStatus.Pending, job(commit2.id).status)
        // Retried, a_1 commits first and a_2 follows.
        q.retryFailed()
        assertEquals(listOf("extract(a_1,1)", "commit(a_1)", "commit(a_2)"), drain())
    }

    @Test
    fun aDoneCommitOfTheSessionBeforeIsNoDependency() {
        JobGraph.enqueueTranscript(q, "a_1")
        drain(windows = mapOf("a_1" to 0))
        JobGraph.enqueueTranscript(q, "a_2")
        val w2 = q.claimNext { true }!!
        JobGraph.expand(q, w2, 0, previousSession = "a_1")
        assertEquals(listOf(w2.id), q.unfinished(JobGraph.COMMIT, "a_2")!!.dependsOn)
    }

    @Test
    fun recommitQueuesTheLaterSessionsInOrderAfterTheRunningCommit() {
        JobGraph.enqueueTranscript(q, "a_1")
        drain(windows = mapOf("a_1" to 0))
        JobGraph.enqueueTranscript(q, "a_2")
        JobGraph.enqueueTranscript(q, "a_3")
        drain(windows = mapOf("a_2" to 0, "a_3" to 0))
        // a_1 is committed again (a retry): its later sessions follow it, one after the other.
        val again = q.enqueue(JobGraph.COMMIT, "a_1")
        val running = q.claimNext { true }!!
        assertEquals(again, running.id)
        JobGraph.recommit(q, running, listOf("a_2", "a_3"))
        q.complete(running)
        val ran = drain()
        assertEquals(listOf("commit(a_2)", "commit(a_3)"), ran)
        val c2 = repo.jobs().last { it.kind == JobGraph.COMMIT && it.sessionId == "a_2" }
        val c3 = repo.jobs().last { it.kind == JobGraph.COMMIT && it.sessionId == "a_3" }
        assertEquals(listOf(running.id), c2.dependsOn)
        assertEquals(listOf(c2.id), c3.dependsOn)
    }

    @Test
    fun aLaterSessionCommittedFirstIsCommittedAgainAfterTheEarlierOne() {
        JobGraph.enqueueTranscript(q, "a_2")
        val w2 = q.claimNext { true }!!
        JobGraph.expand(q, w2, 0, null)
        q.complete(w2)
        val c1 = q.enqueue(JobGraph.COMMIT, "a_1")
        // The oldest ready job runs first: a_2's commit, queued before a_1's.
        val first = q.claimNext { true }!!
        assertEquals(JobGraph.COMMIT to "a_2", first.kind to first.sessionId)
        q.complete(first)
        val running = q.claimNext { true }!!
        assertEquals(c1, running.id)
        JobGraph.recommit(q, running, listOf("a_2"))
        q.complete(running)
        // So the order ends right whatever order the sessions were queued in.
        assertEquals(listOf("commit(a_2)"), drain())
    }

    @Test
    fun recommitReusesALaterCommitThatIsStillPending() {
        val c2 = q.enqueue(JobGraph.COMMIT, "a_2")
        q.enqueue(JobGraph.COMMIT, "a_1")
        val running = q.claimNext { true }!!
        assertEquals(c2, running.id)
        q.complete(running)
        val c1 = q.claimNext { true }!!
        val pending3 = q.enqueue(JobGraph.COMMIT, "a_3")
        JobGraph.recommit(q, c1, listOf("a_2", "a_3"))
        // a_2 was done, so it is queued again; a_3's commit was still pending and is not doubled.
        assertEquals(2, repo.jobs().count { it.kind == JobGraph.COMMIT && it.sessionId == "a_2" })
        assertEquals(listOf(pending3), repo.jobs().filter { it.kind == JobGraph.COMMIT && it.sessionId == "a_3" }.map { it.id })
    }

    @Test
    fun startupQueuesOnlyRecordingsNoJobRefersTo() {
        JobGraph.enqueueRecording(q, "a_1", 7)
        // a_2's recording was cut off before it was queued; a_3's has only its commit and cleanup left (transcribe already pruned).
        q.enqueue(JobGraph.CLEANUP, "a_3", recording = 9)
        JobGraph.recoverRecordings(q, listOf(7L to "a_1", 8L to "a_2", 9L to "a_3"))
        val transcribed = repo.jobs().filter { it.kind == JobGraph.TRANSCRIBE }.map { it.recordingId to it.sessionId }
        assertEquals(listOf(7L to "a_1", 8L to "a_2"), transcribed)
        // Idempotent: running it again changes nothing.
        val before = repo.jobs().size
        JobGraph.recoverRecordings(q, listOf(7L to "a_1", 8L to "a_2", 9L to "a_3"))
        assertEquals(before, repo.jobs().size)
    }
}
