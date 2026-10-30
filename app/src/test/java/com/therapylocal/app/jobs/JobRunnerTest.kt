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
    private class FakeLlm : Llm {
        override val model = "gemma-test"
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

    private fun pool() = ModelPool({ FakeLlm().also { llms += it } }, { error("not used") }, { error("not used") }, { "gemma-test" })

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
}
