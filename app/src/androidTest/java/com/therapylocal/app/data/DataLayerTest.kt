package com.therapylocal.app.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SQLiteOpenHelper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The data layer of docs/AD/AD-10.md on the real SQLCipher database: the versioned transcript, provenance, the stale flag, and AD-11's wipe. CI runs it. */
@RunWith(AndroidJUnit4::class)
class DataLayerTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val password = "test password".toByteArray()
    private val stores = ArrayList<MindStore>()

    @Before
    fun clean() {
        context.deleteDatabase(DB)
    }

    @After
    fun closeAll() {
        stores.forEach { it.close() }
        context.deleteDatabase(DB)
    }

    private fun open(): MindStore = MindStore(context, DB, password).also { stores += it }

    private fun store(): MindStore = open().also {
        it.createClient("c", "c", "en")
        it.createSession("s1", "c", 1)
    }

    private fun seg(idx: Int, text: String) = SegmentRow(idx, idx * 1000L, idx * 1000L + 900, text, "S0")

    private fun window() = WindowRow(1, 1, 2, "text", FloatArray(4) { 0.5f }, "embedder")

    private fun fact(sha: String) = NewFact("c", "s1", "heading", "content of the fact", "evidence", 1, null, Provenance(sha, 0, "p1", "gemma"))

    @Test
    fun theSameTranscriptAgainWritesNothingAndAChangeIsTheNextVersion() {
        val store = store()
        val v1 = store.appendTranscript("s1", listOf(seg(1, "a"), seg(2, "b")), 7L, "audio-hash")
        assertEquals(v1, store.appendTranscript("s1", listOf(seg(1, "a"), seg(2, "b"))))
        assertEquals("audio-hash", store.currentTranscript("s1")!!.audioSha256)
        assertEquals(7L, store.currentTranscript("s1")!!.recordingId)

        val v2 = store.appendTranscript("s1", listOf(seg(1, "a"), seg(2, "c")))
        assertEquals(2, v2.version)
        assertEquals(listOf("a", "c"), store.segments("s1").map { it.text })
        assertEquals(listOf("a", "b"), store.segments("s1", 1).map { it.text })
        assertEquals(2 * 1000L + 900, store.transcriptLengthMs("s1"))
        assertEquals(2, store.segmentsAround("s1", 2, 1).size)
    }

    @Test
    fun deletingTheSessionRemovesEveryVersion() {
        val store = store()
        store.appendTranscript("s1", listOf(seg(1, "a")))
        store.appendTranscript("s1", listOf(seg(1, "b")))
        store.deleteSession("s1")
        assertEquals(null, store.currentTranscript("s1"))
        assertTrue(store.segments("s1", 1).isEmpty() && store.segments("s1", 2).isEmpty())
    }

    @Test
    fun aNewTranscriptVersionMakesWindowsAndFactsStaleUntilTheyAreRebuilt() {
        val store = store()
        val v1 = store.appendTranscript("s1", listOf(seg(1, "a")))
        store.replaceWindows("s1", listOf(window()), Provenance(v1.sha256, 0, "cut", "embedder"))
        store.insertFact(fact(v1.sha256))
        store.refreshStale("s1", "windows", "cut", "embedder")
        store.refreshStale("s1", "facts", "p1", "gemma")
        assertFalse(store.hasStale("s1"))

        val v2 = store.appendTranscript("s1", listOf(seg(1, "a"), seg(2, "b")))
        store.refreshStale("s1", "facts", "p1", "gemma")
        assertTrue(store.hasStale("s1"))

        // The rebuild supersedes the old generation; the new one is made from the new transcript.
        store.replaceSessionFacts("s1", listOf(fact(v2.sha256)), emptyList())
        store.replaceWindows("s1", listOf(window()), Provenance(v2.sha256, 0, "cut", "embedder"))
        store.refreshStale("s1", "windows", "cut", "embedder")
        store.refreshStale("s1", "facts", "p1", "gemma")
        assertFalse(store.hasStale("s1"))
        assertEquals(1, store.facts("c").size)
    }

    @Test
    fun anotherPromptOrModelMakesFactsStaleAndBackAgain() {
        val store = store()
        val v1 = store.appendTranscript("s1", listOf(seg(1, "a")))
        store.insertFact(fact(v1.sha256))
        store.refreshStale("s1", "facts", "p2", "gemma")
        assertTrue(store.hasStale("s1"))
        store.refreshStale("s1", "facts", "p1", "gemma-2")
        assertTrue(store.hasStale("s1"))
        store.refreshStale("s1", "facts", "p1", "gemma")
        assertFalse(store.hasStale("s1"))
    }

    @Test
    fun aDatabaseOfAnotherVersionIsWipedAndRecreated() {
        object : SQLiteOpenHelper(context, DB, password, null, 1, 0, null, null, true) {
            override fun onCreate(db: SQLiteDatabase) {
                db.execSQL("CREATE TABLE clients(id TEXT PRIMARY KEY)")
                db.execSQL("INSERT INTO clients VALUES('old')")
            }

            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
        }.also { it.writableDatabase.close() }

        val store = open()
        assertTrue(store.clients().isEmpty())
        store.createClient("c", "c", "en")
        store.createSession("s1", "c", 1)
        store.appendTranscript("s1", listOf(seg(1, "a")))
        assertEquals(1, store.currentTranscript("s1")!!.version)
    }

    private companion object {
        const val DB = "datalayer.db"
    }
}
