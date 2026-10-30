package com.therapylocal.app.jobs

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.therapylocal.app.data.JobStatus
import com.therapylocal.app.vault.VaultLockedException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The session pipeline end to end on a real encrypted database: transcribe, window, extract, commit and cleanup, driven by the job runner,
 * with fake models. Covers mentions across sessions, a crash inside the commit's transaction, commit order (D4), deletes (cascade, D2) and erase.
 */
@RunWith(AndroidJUnit4::class)
class PipelineEndToEndTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val open = ArrayList<PipelineHarness>()

    private fun harness(name: String = "test.db") = PipelineHarness(context, name, CATALOGUE).also { open += it }

    @After
    fun eraseDatabases() {
        open.forEach { it.close() }
    }

    @Test
    fun aRepeatedFactBecomesAMentionAndAudioGoesOnlyWithCleanup() {
        val h = harness()
        val r1 = h.record(CLIENT, S1, 1_000, LINES_1)
        val r2 = h.record(CLIENT, S2, 2_000, LINES_2)

        h.drain { it != JobGraph.CLEANUP }
        assertEquals(EXPECTED.copy(recordings = 2, chunks = 5), h.outcome(CLIENT))
        assertTrue("audio is kept until cleanup", r1 in h.audio && r2 in h.audio)
        assertEquals(setOf(r1, r2), h.store.recordings().map { it.id }.toSet())
        val cleanups = h.store.jobs().filter { it.kind == JobGraph.CLEANUP }
        assertEquals(2, cleanups.size)
        assertTrue(cleanups.all { it.status == JobStatus.Pending })
        assertTrue(h.store.jobs().filter { it.kind != JobGraph.CLEANUP }.all { it.status == JobStatus.Done })

        h.drain()
        assertEquals(EXPECTED, h.outcome(CLIENT))
        assertTrue(h.audio.isEmpty())
        assertTrue(h.store.recordings().isEmpty())
        assertTrue(h.allJobsDone())
        val sister = h.store.facts(CLIENT).single { it.heading == SISTER.heading }
        assertEquals(1, sister.mentionCount)
        assertEquals(listOf(S1 to true, S2 to false), h.store.factOccurrences(sister.id).map { it.sessionId to it.first })
    }

    @Test
    fun aCrashInsideTheCommitTransactionLeavesNothingAndARecoveredRunEndsLikeACleanOne() {
        val clean = harness("test-clean.db")
        clean.record(CLIENT, S1, 1_000, LINES_1)
        clean.record(CLIENT, S2, 2_000, LINES_2)
        clean.drain()

        val h = harness()
        h.record(CLIENT, S1, 1_000, LINES_1)
        h.record(CLIENT, S2, 2_000, LINES_2)
        h.drain { it != JobGraph.COMMIT && it != JobGraph.CLEANUP }
        val pool = h.pool()
        val run = JobContext(pool, h.queue)
        val commit1 = h.queue.claimNext { it == JobGraph.COMMIT }!!
        assertEquals(S1, commit1.sessionId)
        h.handlers.getValue(JobGraph.COMMIT).run(commit1, run)
        h.queue.complete(commit1)

        // Session 2's commit inserts its new fact, then its mention fails: the process "dies" there, with the job still running.
        h.store.writableDatabase.execSQL("CREATE TRIGGER crash BEFORE INSERT ON mentions BEGIN SELECT RAISE(ABORT, 'crash'); END")
        val commit2 = h.queue.claimNext { it == JobGraph.COMMIT }!!
        assertEquals(S2, commit2.sessionId)
        try {
            h.handlers.getValue(JobGraph.COMMIT).run(commit2, run)
            fail("the commit should have crashed")
        } catch (_: Exception) {
        }
        pool.close()
        h.store.writableDatabase.execSQL("DROP TRIGGER crash")
        assertEquals("the transaction was rolled back", 0, h.count("facts", "session_id = ?", S2))
        assertEquals(JobStatus.Running, h.store.jobs().single { it.id == commit2.id }.status)

        h.queue.recoverStale()
        val recovered = h.store.jobs().single { it.id == commit2.id }
        assertEquals(JobStatus.Pending, recovered.status)
        assertEquals("a crash is not a counted attempt", 0, recovered.attempts)
        h.drain()

        assertEquals(clean.outcome(CLIENT), h.outcome(CLIENT))
        assertEquals(EXPECTED, h.outcome(CLIENT))
        assertTrue(h.allJobsDone())
    }

    @Test
    fun theOlderSessionIsCommittedFirstAndEachSessionOnceEvenWhenQueuedLater() {
        val h = harness()
        h.record(CLIENT, S2, 2_000, LINES_2)
        h.record(CLIENT, S1, 1_000, LINES_1)
        h.drain()

        // The newer session's commit waits for the older one's, so neither is ever re-run.
        assertEquals(listOf(S1, S2), h.ran.filter { it.first == JobGraph.COMMIT }.map { it.second })
        assertEquals(listOf(S1, S2), h.finished.filter { it.first == JobGraph.COMMIT }.map { it.second })
        // The result is as if the sessions had been committed in order: the fact belongs to the older session, the newer one mentions it.
        assertEquals(EXPECTED, h.outcome(CLIENT))
        assertTrue(h.allJobsDone())
    }

    @Test
    fun deletingTheFirstSessionPromotesItsMentionedFactAndDeletingTheClientLeavesNothing() {
        val h = harness()
        h.record(CLIENT, S1, 1_000, LINES_1)
        h.record(CLIENT, S2, 2_000, LINES_2)
        h.drain { it != JobGraph.CLEANUP }

        val deleted = h.store.deleteSession(S1)
        assertEquals(1, deleted.promoted)
        assertEquals(1, deleted.deleted)
        val sister = h.store.facts(CLIENT).single { it.heading == SISTER.heading }
        assertEquals(S2, sister.sessionId)
        assertEquals(SISTER.evidence, sister.evidence)
        assertEquals(1, sister.segIdx)
        assertEquals(0, sister.mentionCount)
        assertEquals(listOf(S2 to true), h.store.factOccurrences(sister.id).map { it.sessionId to it.first })
        assertEquals("the promoted mention is not also kept as a mention", 0, h.count("mentions", "fact_id = ?", "${sister.id}"))
        assertEquals("no fact is mentioned in its own session", 0, h.count("mentions m JOIN facts f ON f.id = m.fact_id", "m.session_id = f.session_id"))
        assertEquals(listOf(SISTER.heading, LIBRARY.heading), h.store.facts(CLIENT).map { it.heading }.sorted())
        for (table in listOf("segments", "windows", "facts", "mentions", "extract_cache", "review_cache", "jobs", "recordings")) {
            assertEquals("$table of the deleted session", 0, h.count(table, "session_id = ?", S1))
        }
        assertEquals(0, h.count("job_deps", "job_id NOT IN (SELECT id FROM jobs) OR depends_on NOT IN (SELECT id FROM jobs)"))
        assertNotNull("the other session's cleanup is still queued", h.store.jobs().singleOrNull { it.kind == JobGraph.CLEANUP && it.sessionId == S2 })

        assertTrue(h.store.deleteClient(CLIENT))
        for (table in PipelineHarness.TABLES) assertEquals("rows left in $table", 0, h.count(table))
        assertFalse(h.store.sessionExists(S2))
    }

    @Test
    fun anErasedStoreRefusesToReopenItsFile() {
        val h = harness()
        h.record(CLIENT, S1, 1_000, LINES_1)
        h.close()
        assertFalse(context.getDatabasePath(h.name).exists())

        assertThrows(VaultLockedException::class.java) { h.store.writableDatabase }
        assertThrows(VaultLockedException::class.java) { h.store.readableDatabase }
        assertFalse("a holder of the closed store does not recreate the file", context.getDatabasePath(h.name).exists())
    }

    companion object {
        const val CLIENT = "c1"
        const val S1 = "c1_s1"
        const val S2 = "c1_s2"

        val SISTER = Said("Sister moving to Lisbon", "Her sister moves to Lisbon next spring.", "my sister is moving to Lisbon")
        val RUNNING = Said("Started running", "She runs three mornings a week.", "I started running three mornings a week")
        val LIBRARY = Said("Library job offer", "She was offered work at the library.", "I got a job offer at the library")
        val CATALOGUE = listOf(SISTER, RUNNING, LIBRARY)

        val LINES_1 = listOf("we talked about the weekend and the weather", "my sister is moving to Lisbon next spring", "and I started running three mornings a week")
        val LINES_2 = listOf("I keep thinking that my sister is moving to Lisbon", "also I got a job offer at the library")

        /** Both sessions fully processed: three facts, the sister fact repeated in session 2 as a mention, no audio left. */
        val EXPECTED = PipelineHarness.Outcome(
            segments = mapOf(S1 to LINES_1, S2 to LINES_2),
            windows = 2,
            facts = listOf(
                listOf(S1, SISTER.heading, SISTER.content, SISTER.evidence, "2"),
                listOf(S1, RUNNING.heading, RUNNING.content, RUNNING.evidence, "3"),
                listOf(S2, LIBRARY.heading, LIBRARY.content, LIBRARY.evidence, "2"),
            ).sortedBy { it.toString() },
            mentions = listOf(listOf(SISTER.heading, S2, SISTER.evidence, "1")),
            recordings = 0,
            chunks = 0,
        )
    }
}
