package com.therapylocal.app.jobs

import com.therapylocal.app.data.Job
import com.therapylocal.app.data.JobRepo
import com.therapylocal.app.data.JobStatus
import com.therapylocal.app.testsupport.FakeJobRepo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class JobRunnerTest {
    private class FakeLlm(override val model: String) : Llm {
        var closed = false
        override fun ask(system: String, user: String, schema: String?, maxOutputTokens: Int, temperature: Double, topK: Int) = ""
        override fun close() {
            closed = true
        }
    }

    private lateinit var fake: FakeJobRepo
    private val gone = HashSet<String>()
    private lateinit var q: JobQueue
    private val llms = ArrayList<FakeLlm>()
    private val ran = ArrayList<Long>()
    private var now = 1000L

    @Before
    fun setUp() {
        fake = FakeJobRepo()
        fake.sessions["a_1"] = "a" to 1L
        fake.sessions["a_2"] = "a" to 2L
        // A session deleted while its job runs: the SQL repo would cascade its jobs away; here they stay visible so the outcome can be read.
        val repo = object : JobRepo by fake {
            override fun sessionExists(id: String) = id !in gone && fake.sessionExists(id)
        }
        q = JobQueue(repo, clock = { now++ })
    }

    private var chosen = "gemma-test"

    private fun pool() = ModelPool({ FakeLlm(chosen).also { llms += it } }, { error("not used") }, { error("not used") }, { chosen })

    private fun runner(handler: (Job, JobContext) -> Unit) =
        JobRunner(q, mapOf("work" to JobHandler { j, c -> ran += j.id; handler(j, c) }), pool()) { true }

    private fun job(id: Long) = fake.jobs().first { it.id == id }

    @Test
    fun aJobWhoseSessionWasDeletedIsCancelledNotFailed() {
        val id = q.enqueue("work", "a_1")
        runner { j, _ -> gone += j.sessionId; error("no such session") }.drain()
        val j = job(id)
        assertEquals(JobStatus.Cancelled, j.status)
        assertEquals(0, j.attempts)
        assertNull(j.errorCode)
        assertEquals(listOf(id), ran)
    }

    @Test
    fun stopReleasesTheJobWithoutCountingAnAttemptAndEndsTheDrain() {
        val first = q.enqueue("work", "a_1")
        val second = q.enqueue("work", "a_2")
        runner { _, c -> q.requestStop(); c.checkStop() }.drain()
        assertEquals(JobStatus.Pending, job(first).status)
        assertEquals(0, job(first).attempts)
        assertEquals(listOf(first), ran)
        assertEquals(JobStatus.Pending, job(second).status)
    }

    @Test
    fun aPermanentFailureFailsAtOnceAndHoldsItsDependentsBack() {
        val first = q.enqueue("work", "a_1")
        val after = q.enqueue("work", "a_1", ref = "2", dependsOn = listOf(first))
        runner { j, _ -> if (j.id == first) throw JobFailure("LLM", permanent = true) }.drain()
        assertEquals(JobStatus.Failed, job(first).status)
        assertEquals(1, job(first).attempts)
        assertEquals("LLM", job(first).errorCode)
        assertEquals(JobStatus.Pending, job(after).status)
        assertEquals(listOf(first), ran)
    }

    @Test
    fun anOrdinaryFailureIsRetriedUntilItsAttemptsAreUsed() {
        val id = q.enqueue("work", "a_1")
        runner { _, _ -> throw JobFailure("LLM") }.drain()
        assertEquals(JobStatus.Failed, job(id).status)
        assertEquals(JobQueue.MAX_ATTEMPTS, job(id).attempts)
        assertEquals(List(JobQueue.MAX_ATTEMPTS) { id }, ran)
    }

    @Test
    fun onlyACodeIsStoredNeverTheMessage() {
        val named = q.enqueue("work", "a_1", maxAttempts = 1)
        val anonymous = q.enqueue("work", "a_2", maxAttempts = 1)
        runner { j, _ ->
            if (j.id == named) throw IllegalStateException("the client said something private")
            throw object : RuntimeException("the client said something private") {}
        }.drain()
        assertEquals("IllegalStateException", job(named).errorCode)
        assertEquals("ERROR", job(anonymous).errorCode)
    }

    @Test
    fun aRebuiltRemoteErrorKeepsTheCodeOfTheOriginal() {
        val remote = q.enqueue("work", "a_1", maxAttempts = 1)
        val local = q.enqueue("work", "a_2", maxAttempts = 1)
        runner { j, _ ->
            if (j.id == remote) throw com.therapylocal.app.engine.RemoteEngineException("LiteRtLmJniException", "private")
            throw LiteRtLmJniException("private")
        }.drain()
        assertEquals("LiteRtLmJniException", job(remote).errorCode)
        assertEquals(job(local).errorCode, job(remote).errorCode)
    }

    @Test
    fun aRebuiltAnonymousErrorStillGetsTheGenericCode() {
        val id = q.enqueue("work", "a_1", maxAttempts = 1)
        val anon = object : RuntimeException("x") {}
        runner { _, _ -> throw com.therapylocal.app.engine.EngineErrors.rebuild(com.therapylocal.app.engine.EngineErrors.capture(anon, true)) }.drain()
        assertEquals("ERROR", job(id).errorCode)
    }

    private class LiteRtLmJniException(m: String) : RuntimeException(m)

    @Test
    fun theModelsAreClosedWhenTheDrainEnds() {
        q.enqueue("work", "a_1")
        q.enqueue("work", "a_2")
        val r = runner { _, c -> c.pool.llm() }
        r.drain()
        assertEquals(1, llms.size)
        assertTrue(llms.single().closed)
        assertFalse(r.hasWork())
    }

    @Test
    fun aKindTheRunnerCannotRunNowIsLeftPending() {
        val id = q.enqueue("work", "a_1")
        JobRunner(q, mapOf("work" to JobHandler { j, _ -> ran += j.id }), pool()) { false }.drain()
        assertEquals(JobStatus.Pending, job(id).status)
        assertTrue(ran.isEmpty())
    }

    @Test
    fun aBuildSwitchedMidJobIsOnlyPickedUpByTheNextJob() {
        val first = q.enqueue("work", "a_1")
        val second = q.enqueue("work", "a_2")
        val used = HashMap<Long, List<String>>()
        runner { j, c ->
            val before = c.pool.llm()
            if (j.id == first) chosen = "gemma-other"
            used[j.id] = listOf(before.model, c.pool.llm().model, c.pool.llmModel())
        }.drain()
        assertEquals(listOf("gemma-test", "gemma-test", "gemma-test"), used[first])
        assertEquals(listOf("gemma-other", "gemma-other", "gemma-other"), used[second])
        assertEquals(listOf("gemma-test", "gemma-other"), llms.map { it.model })
        assertTrue(llms.all { it.closed })
        assertEquals(JobStatus.Done, job(first).status)
        assertEquals(JobStatus.Done, job(second).status)
    }

    @Test
    fun aBuildSwitchedBeforeTheJobLoadedItReleasesTheJobWithoutAnAttempt() {
        val id = q.enqueue("work", "a_1")
        var switched = false
        runner { _, c ->
            c.pool.llmModel()
            if (!switched) {
                switched = true
                chosen = "gemma-other"
            }
            c.pool.llm()
        }.drain()
        assertEquals(listOf(id, id), ran)
        assertEquals(JobStatus.Done, job(id).status)
        assertEquals(0, job(id).attempts)
        assertEquals(listOf("gemma-other"), llms.map { it.model })
    }

    // N36 EngineUnavailable defers the job, no attempt, one claim

    @Test
    fun noHostThatCanServeReleasesTheJobWithoutAnAttemptAndTheDrainStopsDeferred() {
        val first = q.enqueue("work", "a_1")
        val second = q.enqueue("work", "a_2")
        var claims = 0
        val result = runner { _, _ ->
            claims++
            throw com.therapylocal.app.engine.EngineUnavailable(com.therapylocal.app.engine.EngineRole.BATCH, "storm", 42_000)
        }.drain()
        assertEquals(DrainResult.Deferred(42_000), result)
        // One claim, no hot loop, no attempt counted, and the job (and the one behind it) is still pending.
        assertEquals(1, claims)
        assertEquals(JobStatus.Pending, job(first).status)
        assertEquals(0, job(first).attempts)
        assertEquals(JobStatus.Pending, job(second).status)
        assertEquals(listOf(first), ran)
    }

    @Test
    fun aDrainThatFindsNothingWrongIsFinished() {
        q.enqueue("work", "a_1")
        assertEquals(DrainResult.Finished, runner { _, _ -> }.drain())
    }

    @Test
    fun aTooLargeModelFailsTheJobForGoodWithItsCode() {
        val id = q.enqueue("work", "a_1")
        val result = runner { _, _ -> throw JobFailure.modelTooLarge() }.drain()
        assertEquals(DrainResult.Finished, result)
        assertEquals(JobStatus.Failed, job(id).status)
        assertEquals(1, job(id).attempts)
        assertEquals("model_too_large_for_device", job(id).errorCode)
    }

    // An engine death in a job is not the job's failure: no attempt, a bounded number of retries

    private fun died(kind: com.therapylocal.app.models.BackendGuard.ExitKind = com.therapylocal.app.models.BackendGuard.ExitKind.NATIVE_CRASH) =
        com.therapylocal.app.engine.EngineDied(
            com.therapylocal.app.engine.EngineRole.BATCH, 1, com.therapylocal.app.models.BackendGuard.LastExit(kind, true, "test"), null, "ask",
        )

    @Test
    fun aJobWhoseHostDiesSeveralTimesIsRetriedWithoutCountingAttempts() {
        val id = q.enqueue("work", "a_1")
        var n = 0
        val result = runner { _, _ -> if (++n <= 4) throw died() }.drain() // more deaths than the job has attempts (3)
        assertEquals(DrainResult.Finished, result)
        assertEquals(5, n)
        assertEquals(JobStatus.Done, job(id).status)
        assertEquals(0, job(id).attempts)
    }

    @Test
    fun aBenignEndOfTheHostInsideAJobAlsoCostsNoAttempt() {
        val id = q.enqueue("work", "a_1")
        var n = 0
        runner { _, _ -> if (++n == 1) throw died(com.therapylocal.app.models.BackendGuard.ExitKind.BENIGN) }.drain()
        assertEquals(JobStatus.Done, job(id).status)
        assertEquals(0, job(id).attempts)
    }

    @Test
    fun aHostThatKeepsDyingEndsTheJobAfterTheBoundedRetries() {
        val id = q.enqueue("work", "a_1")
        var n = 0
        runner { _, _ -> n++; throw died() }.drain()
        // five free retries, then the deaths count as attempts like any failure
        assertEquals(JobRunner.MAX_DIED_RETRIES + JobQueue.MAX_ATTEMPTS, n)
        assertEquals(JobQueue.MAX_ATTEMPTS, job(id).attempts)
        assertEquals(JobStatus.Failed, job(id).status)
        assertEquals("EngineDied", job(id).errorCode)
    }
}
