package com.therapylocal.app.jobs

import com.therapylocal.app.data.Job
import com.therapylocal.app.data.JobStatus
import com.therapylocal.app.testsupport.FakeJobRepo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class JobQueueTest {
    private lateinit var repo: FakeJobRepo
    private lateinit var q: JobQueue
    private var now = 1000L
    private val any: (String) -> Boolean = { true }

    @Before
    fun setUp() {
        repo = FakeJobRepo()
        q = JobQueue(repo, clock = { now++ })
        repo.sessions["a_1"] = "a" to 1L
        repo.sessions["a_2"] = "a" to 2L
        repo.sessions["b_1"] = "b" to 1L
    }

    private fun job(id: Long) = repo.jobs().first { it.id == id }
    private fun status(id: Long) = job(id).status
    private fun claimId(canRun: (String) -> Boolean = any) = q.claimNext(canRun)?.id

    @Test
    fun claimsOldestFirstAndOnlyOnceDependenciesAreDone() {
        val a = q.enqueue("extract", "a_1")
        val b = q.enqueue("commit", "a_1", dependsOn = listOf(a))
        val c = q.enqueue("extract", "b_1")
        assertEquals(a, claimId())
        // b waits on the running a, so the younger c goes first
        assertEquals(c, claimId())
        assertNull(claimId())
        q.complete(job(a))
        assertEquals(b, claimId())
        assertEquals(JobStatus.Running, status(b))
    }

    @Test
    fun failedDependencyBlocksDependentsUntilRetried() {
        val a = q.enqueue("extract", "a_1", maxAttempts = 1)
        val b = q.enqueue("commit", "a_1", dependsOn = listOf(a))
        val c = q.enqueue("cleanup", "a_1", dependsOn = listOf(b))
        q.fail(q.claimNext(any)!!, "LLM")
        assertEquals(JobStatus.Failed, status(a))
        assertNull(claimId())
        assertFalse(q.hasRunnable(any))
        // b and c (through b) are blocked, and the session counts as not finished
        assertEquals(2, q.snapshot.value.blocked)
        assertEquals(setOf(b, c), q.snapshot.value.jobs.filter { it.status == JobStatus.Pending }.map { it.id }.toSet())
        assertFalse(q.snapshot.value.sessions.single().finished)
        q.retryFailed()
        assertEquals(0, job(a).attempts)
        assertNull(job(a).errorCode)
        assertEquals(a, claimId())
        assertEquals(0, q.snapshot.value.blocked)
    }

    @Test
    fun failRetriesUntilAttemptsAreUsedUp() {
        val a = q.enqueue("extract", "a_1", maxAttempts = 3)
        repeat(2) { n ->
            q.fail(q.claimNext(any)!!, "LLM")
            assertEquals(JobStatus.Pending, status(a))
            assertEquals(n + 1, job(a).attempts)
        }
        q.fail(q.claimNext(any)!!, "IO")
        assertEquals(JobStatus.Failed, status(a))
        assertEquals(3, job(a).attempts)
        assertEquals("IO", job(a).errorCode)
        assertNull(claimId())
    }

    @Test
    fun permanentFailureSkipsTheRemainingAttempts() {
        val a = q.enqueue("transcribe", "a_1", maxAttempts = 3)
        q.fail(q.claimNext(any)!!, "NO_SPEECH", permanent = true)
        assertEquals(JobStatus.Failed, status(a))
        assertEquals(1, job(a).attempts)
    }

    @Test
    fun releaseReturnsToPendingWithoutCountingAnAttempt() {
        val a = q.enqueue("extract", "a_1", maxAttempts = 1)
        repeat(5) { q.release(q.claimNext(any)!!) }
        assertEquals(JobStatus.Pending, status(a))
        assertEquals(0, job(a).attempts)
        assertEquals(a, claimId())
    }

    @Test
    fun preconditionsKeepAJobUnclaimedWithoutTouchingIt() {
        val llm = q.enqueue("extract", "a_1")
        val asr = q.enqueue("transcribe", "a_2")
        val noLlm: (String) -> Boolean = { it != "extract" }
        assertFalse(q.hasRunnable { false })
        assertEquals(asr, claimId(noLlm))
        assertNull(claimId(noLlm))
        assertEquals(JobStatus.Pending, status(llm))
        assertEquals(0, job(llm).attempts)
        assertEquals(llm, claimId())
    }

    @Test
    fun recoverStaleRequeuesRunningJobsWithoutAnAttempt() {
        val a = q.enqueue("extract", "a_1")
        q.fail(q.claimNext(any)!!, "LLM")
        assertEquals(a, claimId())
        q.recoverStale()
        assertEquals(JobStatus.Pending, status(a))
        assertEquals(1, job(a).attempts)
        assertEquals(a, claimId())
    }

    @Test
    fun recoverStalePrunesOnlyFinishedJobsNothingWaitsOn() {
        val a = q.enqueue("extract", "a_1")
        val b = q.enqueue("commit", "a_1", dependsOn = listOf(a))
        val c = q.enqueue("extract", "b_1")
        val extract: (String) -> Boolean = { it == "extract" }
        q.complete(q.claimNext(extract)!!) // a
        q.complete(q.claimNext(extract)!!) // c
        q.recoverStale()
        // a is kept because b, still pending, depends on it; c is pruned.
        assertEquals(listOf(a, b), repo.jobs().map { it.id })
        assertFalse(c in repo.jobs().map { it.id })
        q.complete(q.claimNext(any)!!) // b
        q.recoverStale()
        assertTrue(repo.jobs().isEmpty())
    }

    @Test
    fun recoverStaleKeepsDependenciesOfFailedJobs() {
        val a = q.enqueue("extract", "a_1")
        q.enqueue("commit", "a_1", dependsOn = listOf(a), maxAttempts = 1)
        q.complete(q.claimNext(any)!!)
        q.fail(q.claimNext(any)!!, "LLM")
        q.recoverStale()
        assertTrue(a in repo.jobs().map { it.id })
    }

    @Test
    fun enqueueIsIdempotentUntilTheJobIsDone() {
        val a = q.enqueue("process", "a_1", ref = "a_1.md")
        assertEquals(a, q.enqueue("process", "a_1", ref = "a_1.md"))
        assertNotEquals(a, q.enqueue("process", "a_1", ref = "other"))
        assertNotEquals(a, q.enqueue("extract", "a_1", ref = "a_1.md"))
        q.fail(q.claimNext(any)!!, "LLM", permanent = true)
        assertEquals(a, q.enqueue("process", "a_1", ref = "a_1.md")) // failed: retried, not duplicated
        q.retryFailed()
        q.complete(q.claimNext { it == "process" }!!)
        assertNotEquals(a, q.enqueue("process", "a_1", ref = "a_1.md")) // done: a re-run is a new job
    }

    @Test
    fun enqueueIgnoresDependenciesThatNoLongerExist() {
        val b = q.enqueue("commit", "a_1", dependsOn = listOf(999))
        assertTrue(job(b).dependsOn.isEmpty())
        assertEquals(b, claimId())
    }

    @Test
    fun dropSessionRemovesItsJobsExceptTheRunningOne() {
        val running = q.enqueue("extract", "a_1")
        val pending = q.enqueue("commit", "a_1", dependsOn = listOf(running))
        val other = q.enqueue("extract", "a_2")
        q.claimNext(any)
        q.dropSession("a_1")
        assertEquals(listOf(running, other), repo.jobs().map { it.id })
        assertFalse(pending in repo.jobs().map { it.id })
    }

    @Test
    fun dropClientRemovesOnlyThatClientsJobsAndKeepsTheRunningOne() {
        val running = q.enqueue("extract", "a_1")
        q.enqueue("extract", "a_2")
        val other = q.enqueue("extract", "b_1")
        q.claimNext(any)
        q.dropClient("a")
        assertEquals(listOf(running, other), repo.jobs().map { it.id })
    }

    @Test
    fun cancelledIsNotFailedAndIsNeverRetried() {
        val a = q.enqueue("extract", "a_1", maxAttempts = 1)
        val b = q.enqueue("commit", "a_1", dependsOn = listOf(a))
        q.cancel(q.claimNext(any)!!)
        assertEquals(JobStatus.Cancelled, status(a))
        assertEquals(0, job(a).attempts)
        assertEquals(0, q.snapshot.value.failed)
        q.retryFailed()
        assertEquals(JobStatus.Cancelled, status(a))
        assertEquals(1, q.snapshot.value.blocked)
        assertEquals(JobStatus.Pending, status(b))
    }

    @Test
    fun deletedSessionTakesItsJobsWithItAndDoesNotBlockOthers() {
        val a = q.enqueue("commit", "a_1")
        val b = q.enqueue("commit", "a_2", dependsOn = listOf(a))
        repo.deleteSession("a_1")
        assertEquals(b, claimId())
    }

    @Test
    fun snapshotCountsPerSessionInQueueOrder() {
        val a1 = q.enqueue("extract", "a_2")
        q.enqueue("commit", "a_2", dependsOn = listOf(a1))
        q.enqueue("extract", "b_1")
        q.complete(q.claimNext(any)!!)
        q.claimNext(any)
        val s = q.snapshot.value
        assertEquals(listOf("a_2", "b_1"), s.sessions.map { it.sessionId })
        val a = s.sessions[0]
        assertEquals("a", a.clientId)
        assertEquals(2L, a.startedAt)
        assertEquals(2, a.total)
        assertEquals(1, a.done)
        assertTrue(a.running)
        assertFalse(s.sessions[1].running)
        assertEquals("commit", s.running?.kind)
    }

    @Test
    fun progressIsShownOnlyWhileAJobRuns() {
        q.enqueue("extract", "a_1")
        q.progress(StepProgress(JobPhase.Reading, 1, 4))
        assertNull(q.snapshot.value.progress) // nothing running
        val j = q.claimNext(any)!!
        q.progress(StepProgress(JobPhase.Reading, 2, 4))
        assertEquals(StepProgress(JobPhase.Reading, 2, 4), q.snapshot.value.progress)
        q.complete(j)
        assertNull(q.snapshot.value.progress)
        assertNull(q.snapshot.value.running)
    }

    @Test
    fun stopFlagSurvivesQueueChangesUntilCleared() {
        q.requestStop()
        q.enqueue("extract", "a_1")
        q.retryFailed()
        q.recoverStale()
        assertTrue(q.stopRequested)
        assertTrue(q.snapshot.value.stopping)
        q.clearStop()
        assertFalse(q.stopRequested)
        assertFalse(q.snapshot.value.stopping)
    }

    @Test
    fun hasPendingSeesOnlyPendingJobs() {
        assertFalse(q.hasPending())
        q.enqueue("extract", "a_1")
        assertTrue(q.hasPending())
        q.complete(q.claimNext(any)!!)
        assertFalse(q.hasPending())
    }
}
