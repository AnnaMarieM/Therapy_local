package com.therapylocal.app.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SQLiteOpenHelper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The data layer of docs/AD/AD-10.md on the real SQLCipher database: the version 4 to 5 migration, the append-only transcript, the immutability
 * triggers, provenance and the stored stale flag. Needs a device (SQLCipher is native), so CI runs it.
 */
@RunWith(AndroidJUnit4::class)
class DataLayerTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val password = "test password".toByteArray()
    private val stores = ArrayList<MindStore>()

    @Before
    fun clean() {
        context.deleteDatabase(V4_DB)
        context.deleteDatabase(FRESH_DB)
    }

    @After
    fun closeAll() {
        stores.forEach { it.close() }
        context.deleteDatabase(V4_DB)
        context.deleteDatabase(FRESH_DB)
    }

    private fun fresh(): MindStore = MindStore(context, FRESH_DB, password).also { stores += it }

    private fun seg(idx: Int, text: String, speaker: String? = "S0") = SegmentRow(idx, idx * 1000L, idx * 1000L + 900, text, speaker)

    private fun session(store: MindStore, id: String = "s1", client: String = "c", started: Long = 1) {
        if (store.clients().none { it.id == client }) store.createClient(client, client, "en")
        store.createSession(id, client, started)
    }

    private val noEmbedding = FloatArray(4) { 0.5f }

    private fun window(i: Int) = WindowRow(i, 1, 2, "text $i", noEmbedding, "embedder")

    private fun fact(session: String, prov: Provenance) =
        NewFact("c", session, "heading", "content of the fact", "evidence", 1, noEmbedding, prov.model, prov.promptVersion, transcriptSha = prov.transcriptSha, speakerMapVersion = prov.speakerMapVersion)

    /** A database as version 4 left it: segments without versions, windows and facts without provenance. */
    private fun makeV4() {
        object : SQLiteOpenHelper(context, V4_DB, password, null, 4, 0, null, null, true) {
            override fun onCreate(db: SQLiteDatabase) {
                db.execSQL("CREATE TABLE clients(id TEXT PRIMARY KEY, name TEXT NOT NULL, created_at INTEGER NOT NULL, language TEXT NOT NULL DEFAULT 'en', first_name TEXT NOT NULL DEFAULT '', last_name TEXT NOT NULL DEFAULT '')")
                db.execSQL("CREATE TABLE sessions(id TEXT PRIMARY KEY, client_id TEXT NOT NULL REFERENCES clients(id) ON DELETE CASCADE, started_at INTEGER NOT NULL, ended_at INTEGER)")
                db.execSQL("CREATE TABLE segments(session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE, idx INTEGER NOT NULL, start_ms INTEGER NOT NULL, end_ms INTEGER NOT NULL, text TEXT NOT NULL, speaker TEXT, PRIMARY KEY(session_id, idx))")
                db.execSQL("CREATE TABLE windows(session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE, idx INTEGER NOT NULL, seg_from INTEGER NOT NULL, seg_to INTEGER NOT NULL, text TEXT NOT NULL, embedding BLOB NOT NULL, embed_model TEXT NOT NULL, PRIMARY KEY(session_id, idx))")
                db.execSQL("CREATE TABLE facts(id INTEGER PRIMARY KEY AUTOINCREMENT, client_id TEXT NOT NULL REFERENCES clients(id) ON DELETE CASCADE, session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE, heading TEXT NOT NULL, content TEXT NOT NULL, evidence TEXT NOT NULL, seg_idx INTEGER NOT NULL, embedding BLOB, llm_model TEXT NOT NULL, prompt_version TEXT NOT NULL, created_at INTEGER NOT NULL, scope TEXT NOT NULL DEFAULT 'profile', category TEXT NOT NULL DEFAULT '')")
                db.execSQL("CREATE TABLE mentions(id INTEGER PRIMARY KEY AUTOINCREMENT, fact_id INTEGER NOT NULL REFERENCES facts(id) ON DELETE CASCADE, session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE, evidence TEXT NOT NULL, seg_idx INTEGER NOT NULL, created_at INTEGER NOT NULL, UNIQUE(fact_id, session_id, seg_idx))")
                db.execSQL("CREATE TABLE diarization_turns(recording_id INTEGER NOT NULL, idx INTEGER NOT NULL, start_ms INTEGER NOT NULL, end_ms INTEGER NOT NULL, speaker TEXT NOT NULL, text TEXT, PRIMARY KEY(recording_id, idx))")
                db.execSQL("CREATE TABLE speaker_maps(session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE, version INTEGER NOT NULL, map_json TEXT NOT NULL, source TEXT NOT NULL, confidence REAL NOT NULL, evidence_json TEXT NOT NULL, created_at INTEGER NOT NULL, PRIMARY KEY(session_id, version))")
                db.execSQL("INSERT INTO clients(id, name, created_at) VALUES('c', 'C', 1)")
                db.execSQL("INSERT INTO sessions(id, client_id, started_at) VALUES('s1', 'c', 1), ('s2', 'c', 2)")
                db.execSQL("INSERT INTO segments VALUES('s1', 1, 0, 900, 'hello', 'S0'), ('s1', 2, 1000, 1900, 'world', NULL)")
                db.execSQL("INSERT INTO windows VALUES('s1', 1, 1, 2, 'hello world', x'00000000', 'embedder')")
                db.execSQL("INSERT INTO facts(client_id, session_id, heading, content, evidence, seg_idx, llm_model, prompt_version, created_at) VALUES('c', 's1', 'h', 'c', 'e', 1, 'gemma', 'p1', 1)")
                db.execSQL("INSERT INTO speaker_maps VALUES('s1', 1, '{}', 'llm', 0.9, '[]', 1), ('s1', 2, '{}', 'user', 1.0, '[]', 2)")
            }

            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
        }.also { it.writableDatabase.close() }
    }

    @Test
    fun aVersion4DatabaseMigratesItsTranscriptsAndGivesDerivedRowsProvenance() {
        makeV4()
        val store = MindStore(context, V4_DB, password).also { stores += it }

        val head = store.currentTranscript("s1")!!
        assertEquals(1, head.version)
        assertEquals(TranscriptVersions.MIGRATED, head.source)
        assertEquals(2, head.segmentCount)
        assertEquals(TranscriptHash.of(listOf(SegmentRow(1, 0, 900, "hello", "S0"), SegmentRow(2, 1000, 1900, "world", null))), head.sha256)
        assertEquals(listOf("hello", "world"), store.segments("s1").map { it.text })
        assertEquals(listOf("S0", null), store.segments("s1").map { it.speaker })
        assertEquals(null, store.currentTranscript("s2"))

        val w = store.windows("s1").single()
        assertEquals(head.sha256, w.transcriptSha)
        val f = store.facts("c").single()
        assertEquals(Provenance(head.sha256, 2, "p1", "gemma"), f.provenance)
        assertFalse(f.stale)
        assertFalse(store.hasStale("s1"))
        // Windows have no prompt version before AD-10: unknown, and so not stale when the cut configuration is compared.
        assertEquals(0, store.refreshStale("s1", mapOf(Derived.WINDOWS to Expectation("t1000-o50", "embedder"), Derived.FACTS to Expectation("p1", "gemma"))))
        // A fact made with another prompt is stale; so is a window made with another embedder.
        assertEquals(2, store.refreshStale("s1", mapOf(Derived.WINDOWS to Expectation("t1000-o50", "embedder-2"), Derived.FACTS to Expectation("p2", "gemma"))))
        assertTrue(store.facts("c").single().stale)
        // And back: flags are computed, not sticky.
        assertEquals(0, store.refreshStale("s1", mapOf(Derived.FACTS to Expectation("p1", "gemma"))))
        assertFalse(store.facts("c").single().stale)
    }

    @Test
    fun aFreshDatabaseHasTheSameSchema() {
        val store = fresh()
        session(store)
        val v = store.appendTranscript("s1", listOf(seg(1, "hello")), TranscriptVersions.IMPORT)
        assertEquals(1, v.version)
        store.replaceWindows("s1", listOf(window(1)), Provenance(v.sha256, Provenance.NO_MAP, "t1000-o50", "embedder"))
        store.insertFact(fact("s1", Provenance(v.sha256, Provenance.NO_MAP, "p1", "gemma")))
        assertEquals(v.sha256, store.windows("s1").single().transcriptSha)
        assertEquals(Provenance(v.sha256, Provenance.NO_MAP, "p1", "gemma"), store.facts("c").single().provenance)
    }

    @Test
    fun theSameTranscriptAgainWritesNothingAndAChangeIsTheNextVersion() {
        val store = fresh()
        session(store)
        val v1 = store.appendTranscript("s1", listOf(seg(1, "a"), seg(2, "b")), TranscriptVersions.DIARIZED, 7L, "audio-hash")
        assertEquals(v1, store.appendTranscript("s1", listOf(seg(1, "a"), seg(2, "b")), TranscriptVersions.CHUNKS))
        assertEquals(1, store.transcripts("s1").size)
        assertEquals("audio-hash", store.currentTranscript("s1")!!.audioSha256)
        assertEquals(7L, store.currentTranscript("s1")!!.recordingId)

        val v2 = store.appendTranscript("s1", listOf(seg(1, "a"), seg(2, "c")), TranscriptVersions.CHUNKS)
        assertEquals(2, v2.version)
        assertEquals(listOf("a", "c"), store.segments("s1").map { it.text })
        assertEquals(listOf("a", "b"), store.segments("s1", 1).map { it.text })
        assertEquals(2, store.segmentsAround("s1", 2, 1).size)
        assertEquals(2000L + 900, store.transcriptLengthMs("s1"))
    }

    @Test
    fun inputsCannotBeUpdatedAndGoWithTheirSession() {
        val store = fresh()
        session(store)
        store.appendTranscript("s1", listOf(seg(1, "a")), TranscriptVersions.IMPORT)
        store.appendSpeakerMap("s1", "{}", "llm", 0.5)
        val db = store.writableDatabase
        for (sql in listOf("UPDATE segments SET text = 'x'", "UPDATE transcripts SET sha256 = 'x'", "UPDATE speaker_maps SET map_json = 'x'")) {
            try {
                db.execSQL(sql)
                fail("accepted: $sql")
            } catch (_: Exception) {
                // refused by the trigger
            }
        }
        assertEquals(listOf("a"), store.segments("s1").map { it.text })
        store.deleteSession("s1")
        assertTrue(store.transcripts("s1").isEmpty() && store.segments("s1", 1).isEmpty())
    }

    @Test
    fun aNewTranscriptVersionFlagsTheDerivedRowsOfTheOldOneStale() {
        val store = fresh()
        session(store)
        val v1 = store.appendTranscript("s1", listOf(seg(1, "a")), TranscriptVersions.IMPORT)
        val prov = Provenance(v1.sha256, Provenance.NO_MAP, "p1", "gemma")
        store.replaceWindows("s1", listOf(window(1)), prov.copy(promptVersion = "t1000-o50", model = "embedder"))
        store.insertFact(fact("s1", prov))
        assertFalse(store.hasStale("s1"))

        store.appendTranscript("s1", listOf(seg(1, "a"), seg(2, "b")), TranscriptVersions.CHUNKS)
        assertTrue(store.hasStale("s1"))
        assertTrue(store.facts("c").single().stale)

        // The rebuild supersedes them: the new generation is made from the new transcript and is not stale.
        val v2 = store.currentTranscript("s1")!!
        store.replaceSessionFacts("s1", listOf(fact("s1", prov.copy(transcriptSha = v2.sha256))), emptyList())
        store.replaceWindows("s1", listOf(window(1)), prov.copy(transcriptSha = v2.sha256, promptVersion = "t1000-o50", model = "embedder"))
        assertFalse(store.hasStale("s1"))
        assertEquals(1, store.facts("c").size)
    }

    @Test
    fun aSpeakerSwapDoesNotMakeWindowsOrFactsStale() {
        val store = fresh()
        session(store)
        val v1 = store.appendTranscript("s1", listOf(seg(1, "a")), TranscriptVersions.IMPORT)
        store.appendSpeakerMap("s1", "{\"S0\":\"therapist\"}", "llm", 0.5)
        store.replaceWindows("s1", listOf(window(1)), Provenance(v1.sha256, 1, "t1000-o50", "embedder"))
        store.insertFact(fact("s1", Provenance(v1.sha256, 1, "p1", "gemma")))
        store.appendSpeakerMap("s1", "{\"S0\":\"patient\"}", "user", 1.0)
        assertFalse(store.hasStale("s1"))
        assertNotNull(store.currentSpeakerMap("s1"))
        assertEquals(2, store.currentSpeakerMap("s1")!!.version)
    }

    private companion object {
        const val V4_DB = "datalayer-v4.db"
        const val FRESH_DB = "datalayer-fresh.db"
    }
}
