package com.therapylocal.app.memory

import com.therapylocal.app.vault.SecureFile
import com.therapylocal.app.vault.VaultCrypto
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MemoryQueueTest {
    @get:Rule
    val tmp = TemporaryFolder()
    private lateinit var file: File
    private val key = VaultCrypto.randomBytes(VaultCrypto.KEY_BYTES)

    private fun job(id: String, client: String = id.substringBefore('_'), status: JobStatus = JobStatus.Queued) = SessionJob(id, client, "$id.md", "2024-01-01", status)
    private fun jobs() = MemoryQueue.state.value.jobs
    private fun ids() = jobs().map { it.id }
    private fun status(id: String) = jobs().first { it.id == id }.status

    /** Writes a saved queue the way the app would have left it. */
    private fun saveOnDisk(list: List<SessionJob>) = SecureFile.writeText(file, JSONArray(list.map { it.toJson() }).toString(), key)

    @Before
    fun setUp() {
        file = File(tmp.root, "queue.json")
        MemoryQueue.keyProvider = { key }
        MemoryQueue.cancelRequested = false
        MemoryQueue.bind(file)
        MemoryQueue.update { QueueState() }
        file.delete()
    }

    @After
    fun tearDown() {
        MemoryQueue.update { QueueState() }
        MemoryQueue.cancelRequested = false
    }

    @Test
    fun enqueueAppendsInOrderAndIsIdempotentById() {
        MemoryQueue.enqueue(job("a_1"))
        MemoryQueue.enqueue(job("b_1"))
        MemoryQueue.enqueue(job("a_2"))
        MemoryQueue.enqueue(job("b_1").copy(date = "other", status = JobStatus.Done)) // same id: ignored entirely
        assertEquals(listOf("a_1", "b_1", "a_2"), ids())
        assertEquals("2024-01-01", jobs()[1].date)
        assertEquals(JobStatus.Queued, status("b_1"))
    }

    @Test
    fun enqueueDoesNotDuplicateEvenAfterTheJobFinished() {
        MemoryQueue.enqueue(job("a_1"))
        MemoryQueue.updateJob("a_1") { it.copy(status = JobStatus.Done) }
        MemoryQueue.enqueue(job("a_1"))
        assertEquals(1, jobs().size)
        assertEquals(JobStatus.Done, status("a_1"))
    }

    @Test
    fun updateJobChangesOnlyTheMatchingJob() {
        MemoryQueue.enqueue(job("a_1"))
        MemoryQueue.enqueue(job("a_2"))
        MemoryQueue.updateJob("a_2") { it.copy(stage = "extract", windowsDone = 2) }
        assertEquals("", jobs()[0].stage)
        assertEquals("extract", jobs()[1].stage)
        MemoryQueue.updateJob("missing") { it.copy(stage = "x") } // no-op, no crash
        assertEquals(2, jobs().size)
    }

    @Test
    fun loadFreshWithoutResumeIgnoresTheSavedQueue() {
        saveOnDisk(listOf(job("a_1", status = JobStatus.Done)))
        MemoryQueue.load(listOf(job("a_1"), job("a_2")), resume = false)
        assertEquals(listOf("a_1", "a_2"), ids())
        assertEquals(JobStatus.Queued, status("a_1"))
    }

    @Test
    fun loadResumeKeepsDoneRequeuesFailedAndRestoresRunning() {
        val done = job("a_1", status = JobStatus.Done).copy(added = 7, extracted = 9, finishedAt = 55)
        val failed = job("a_2", status = JobStatus.Failed).copy(error = "boom", stage = "extract", windowsDone = 1)
        val running = job("a_3", status = JobStatus.Running).copy(stage = "extract")
        val cancelled = job("a_4", status = JobStatus.Cancelled)
        saveOnDisk(listOf(done, failed, running, cancelled))
        MemoryQueue.load(listOf(job("a_1"), job("a_2"), job("a_3"), job("a_4"), job("a_5")), resume = true)
        assertEquals(listOf("a_1", "a_2", "a_3", "a_4", "a_5"), ids())
        assertEquals(done, jobs()[0]) // Done kept verbatim, counters included
        assertEquals(JobStatus.Queued, jobs()[1].status) // Failed requeued with the error cleared
        assertNull(jobs()[1].error)
        assertEquals(JobStatus.Queued, status("a_3"))
        assertEquals(job("a_3"), jobs()[2]) // Running job is replaced by the fresh definition
        assertEquals(job("a_4"), jobs()[3])
        assertEquals(job("a_5"), jobs()[4])
    }

    @Test
    fun loadResumeWithFreshListDropsSavedJobsNotInIt() {
        saveOnDisk(listOf(job("a_1", status = JobStatus.Done), job("zzz_1", status = JobStatus.Queued)))
        MemoryQueue.load(listOf(job("a_1")), resume = true)
        assertEquals(listOf("a_1"), ids())
        assertEquals(JobStatus.Done, status("a_1"))
    }

    @Test
    fun loadWithEmptyFreshListContinuesTheSavedQueue() {
        val failed = job("a_2", status = JobStatus.Failed).copy(error = "boom")
        saveOnDisk(listOf(job("a_1", status = JobStatus.Done), failed, job("a_3", status = JobStatus.Running), job("a_4")))
        MemoryQueue.restore()
        assertEquals(listOf("a_1", "a_2", "a_3", "a_4"), ids())
        assertEquals(JobStatus.Done, status("a_1"))
        assertEquals(failed, jobs()[1]) // in this mode a Failed job stays Failed until retryFailed()
        assertEquals(JobStatus.Queued, status("a_3")) // Running is restored as Queued
        assertTrue(MemoryQueue.hasQueued())
    }

    @Test
    fun loadWithEmptyFreshListAndNoResumeClearsTheQueue() {
        MemoryQueue.enqueue(job("a_1"))
        MemoryQueue.load(emptyList(), resume = false)
        assertTrue(jobs().isEmpty())
    }

    @Test
    fun dropSessionRemovesAnyButRunningJobs() {
        for (st in JobStatus.values()) MemoryQueue.enqueue(job("c_${st.name}", status = st))
        MemoryQueue.dropSession("c_Running")
        assertEquals(JobStatus.values().size, jobs().size)
        for (st in JobStatus.values().filter { it != JobStatus.Running }) MemoryQueue.dropSession("c_${st.name}")
        assertEquals(listOf("c_Running"), ids())
        MemoryQueue.dropSession("nope")
        assertEquals(1, jobs().size)
    }

    @Test
    fun dropClientKeepsRunningJobsAndOtherClientsAndClearsTheProfile() {
        MemoryQueue.enqueue(job("a_1"))
        MemoryQueue.enqueue(job("a_2", status = JobStatus.Running))
        MemoryQueue.enqueue(job("a_3", status = JobStatus.Done))
        MemoryQueue.enqueue(job("b_1"))
        val fact = Fact("1", "work", "c", "A fact sentence here.", "evidence", null)
        MemoryQueue.update { it.copy(profiles = mapOf("a" to listOf(fact), "b" to listOf(fact))) }
        MemoryQueue.dropClient("a")
        assertEquals(listOf("a_2", "b_1"), ids())
        assertEquals(setOf("b"), MemoryQueue.state.value.profiles.keys)
    }

    @Test
    fun retryFailedRequeuesFailedAndCancelledOnly() {
        MemoryQueue.enqueue(job("a_1", status = JobStatus.Failed).copy(error = "x", stage = "merge"))
        MemoryQueue.enqueue(job("a_2", status = JobStatus.Cancelled).copy(stage = "extract"))
        MemoryQueue.enqueue(job("a_3", status = JobStatus.Done).copy(stage = "done"))
        MemoryQueue.enqueue(job("a_4", status = JobStatus.Running).copy(stage = "extract"))
        MemoryQueue.enqueue(job("a_5", status = JobStatus.Queued).copy(stage = "waiting"))
        MemoryQueue.retryFailed()
        assertEquals(listOf(JobStatus.Queued, JobStatus.Queued, JobStatus.Done, JobStatus.Running, JobStatus.Queued), jobs().map { it.status })
        assertNull(jobs()[0].error)
        assertEquals(listOf("", "", "done", "extract", "waiting"), jobs().map { it.stage })
    }

    @Test
    fun hasQueuedReflectsOnlyTheInMemoryQueue() {
        assertFalse(MemoryQueue.hasQueued())
        MemoryQueue.enqueue(job("a_1", status = JobStatus.Running))
        MemoryQueue.enqueue(job("a_2", status = JobStatus.Failed))
        assertFalse(MemoryQueue.hasQueued())
        MemoryQueue.enqueue(job("a_3"))
        assertTrue(MemoryQueue.hasQueued())
    }

    @Test
    fun hasSavedWorkReadsTheFileNotTheMemory() {
        assertFalse(MemoryQueue.hasSavedWork())
        saveOnDisk(listOf(job("a_1", status = JobStatus.Done), job("a_2", status = JobStatus.Failed)))
        assertFalse(MemoryQueue.hasSavedWork())
        saveOnDisk(listOf(job("a_1", status = JobStatus.Done), job("a_2")))
        assertTrue(MemoryQueue.hasSavedWork())
        // A job that was Running when the process died counts as work (read back as Queued).
        saveOnDisk(listOf(job("a_1", status = JobStatus.Running)))
        assertTrue(MemoryQueue.hasSavedWork())
        assertTrue(jobs().isEmpty()) // memory untouched
    }

    @Test
    fun everyChangeIsPersistedAndRoundTripsAllFields() {
        val full = SessionJob("a_1", "a", "a_1.md", "2024-02-03", JobStatus.Done, "done", 5, 5, 11, 8, "e", 100, 200, 12.5, 3.25, 42)
        MemoryQueue.enqueue(full)
        MemoryQueue.enqueue(job("a_2", status = JobStatus.Running))
        MemoryQueue.enqueue(job("a_3", status = JobStatus.Failed).copy(error = "bad"))
        val onDisk = JSONArray(SecureFile.readText(file, key)!!)
        assertEquals(3, onDisk.length())
        MemoryQueue.update { QueueState() } // memory gone, file now empty list
        assertFalse(MemoryQueue.hasSavedWork())
        saveOnDisk(listOf(full, job("a_2", status = JobStatus.Running), job("a_3", status = JobStatus.Failed).copy(error = "bad")))
        MemoryQueue.restore()
        assertEquals(full, jobs()[0])
        assertEquals(JobStatus.Queued, jobs()[1].status)
        assertEquals("bad", jobs()[2].error)
    }

    @Test
    fun persistedFileIsEncryptedAndTiedToTheKey() {
        MemoryQueue.enqueue(job("secret_1"))
        val raw = file.readBytes()
        assertFalse(String(raw, Charsets.ISO_8859_1).contains("secret_1"))
        MemoryQueue.keyProvider = { VaultCrypto.randomBytes(VaultCrypto.KEY_BYTES) } // wrong key: unreadable, treated as empty
        assertFalse(MemoryQueue.hasSavedWork())
        MemoryQueue.restore()
        assertTrue(jobs().isEmpty())
    }

    @Test
    fun corruptedOrLockedStorageNeverThrows() {
        file.writeBytes(byteArrayOf(1, 2, 3))
        assertFalse(MemoryQueue.hasSavedWork())
        saveOnDisk(emptyList())
        SecureFile.writeText(file, "{\"not\":\"an array\"}", key)
        assertFalse(MemoryQueue.hasSavedWork())
        SecureFile.writeText(file, "[{\"id\":\"a_1\",\"client\":\"a\",\"file\":\"f\",\"status\":\"Bogus\"}]", key)
        assertFalse(MemoryQueue.hasSavedWork())
        // Locked vault: key unavailable, saving is skipped silently and in-memory state still works.
        MemoryQueue.keyProvider = { throw IllegalStateException("locked") }
        MemoryQueue.enqueue(job("a_1"))
        assertEquals(listOf("a_1"), ids())
        assertFalse(MemoryQueue.hasSavedWork())
    }

    @Test
    fun cancelRequestedIsOnlyChangedByItsOwner() {
        MemoryQueue.cancelRequested = true
        MemoryQueue.enqueue(job("a_1"))
        MemoryQueue.updateJob("a_1") { it.copy(status = JobStatus.Cancelled) }
        MemoryQueue.retryFailed()
        MemoryQueue.load(listOf(job("a_1")), resume = false)
        MemoryQueue.dropSession("a_1")
        MemoryQueue.dropClient("a")
        // No queue operation resets it: the runner clears it when a new run starts, so a Stop survives until then.
        assertTrue(MemoryQueue.cancelRequested)
        MemoryQueue.cancelRequested = false
        assertFalse(MemoryQueue.cancelRequested)
    }

    @Test
    fun clientsAreDistinctInQueueOrder() {
        MemoryQueue.enqueue(job("b_1"))
        MemoryQueue.enqueue(job("a_1"))
        MemoryQueue.enqueue(job("b_2"))
        assertEquals(listOf("b", "a"), MemoryQueue.state.value.clients)
    }
}
