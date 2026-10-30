package com.therapylocal.app.data

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SQLiteOpenHelper
import com.therapylocal.app.vault.Vault
import com.therapylocal.app.vault.VaultLockedException

data class ClientRow(val id: String, val name: String, val createdAt: Long)

data class SessionRow(val id: String, val clientId: String, val startedAt: Long, val endedAt: Long?, val observations: String)

data class RecordingRow(val id: Long, val sessionId: String?, val startedAt: Long, val engine: String, val model: String, val chunkCount: Int, val durationMs: Long)

/** Raw speech-recognition output of one chunk; [text] is null until it has been transcribed. It shares [overlapMs] with the previous chunk. */
data class ChunkRow(val recordingId: Long, val idx: Int, val startMs: Long, val endMs: Long, val overlapMs: Long, val text: String?)

data class SegmentRow(val idx: Int, val startMs: Long, val endMs: Long, val text: String)

/** An embedded stretch of transcript covering segments [segFrom]..[segTo]. */
data class WindowRow(val idx: Int, val segFrom: Int, val segTo: Int, val text: String, val embedding: FloatArray, val embedModel: String)

data class WindowRef(val sessionId: String, val idx: Int, val segFrom: Int, val segTo: Int, val text: String)

data class NewFact(
    val clientId: String,
    val sessionId: String,
    val heading: String,
    val content: String,
    val evidence: String,
    val segIdx: Int,
    val embedding: FloatArray?,
    val llmModel: String,
    val promptVersion: String,
)

data class NewMention(val factId: Long, val sessionId: String, val evidence: String, val segIdx: Int)

/** A fact as the app reads it. Provenance (model, prompt version) is never read back: it is for debugging the database only. */
data class FactRow(
    val id: Long,
    val clientId: String,
    val sessionId: String,
    val heading: String,
    val content: String,
    val evidence: String,
    val segIdx: Int,
    val createdAt: Long,
    val mentionCount: Int,
)

/** Where a fact was said: its first occurrence ([first]) or a later mention. */
data class Occurrence(val sessionId: String, val startedAt: Long, val evidence: String, val segIdx: Int, val first: Boolean)

data class SessionDelete(val promoted: Int, val deleted: Int)

/**
 * The one database: clients, sessions, recordings and their transcripts, embedded windows, facts and mentions, caches and the job queue.
 * Encrypted with SQLCipher under the vault key; [get] while the vault is locked throws [com.therapylocal.app.vault.VaultLockedException] (fail closed).
 * Every foreign key cascades, so deleting a client or a session removes everything stored for it. Write-ahead logging keeps UI reads from waiting on the runner.
 */
class MindStore(context: Context, name: String, password: ByteArray) :
    SQLiteOpenHelper(context, name, password, null, VERSION, 0, null, null, true), JobRepo {

    /** Once closed, a holder that kept this store (a runner finishing a step) must not reopen the file, which may be being erased. */
    @Volatile
    private var closed = false

    override val writableDatabase: SQLiteDatabase
        get() {
            if (closed) throw VaultLockedException()
            return super.writableDatabase
        }

    override val readableDatabase: SQLiteDatabase
        get() {
            if (closed) throw VaultLockedException()
            return super.readableDatabase
        }

    override fun close() {
        closed = true
        super.close()
    }

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        SCHEMA.forEach(db::execSQL)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    // ---- clients and sessions ----------------------------------------------------------------------------------

    fun createClient(id: String, name: String) {
        writableDatabase.insertOrThrow("clients", null, ContentValues().apply { put("id", id); put("name", name); put("created_at", System.currentTimeMillis()) })
    }

    /** Sorted by name. */
    fun clients(): List<ClientRow> = query("SELECT id, name, created_at FROM clients ORDER BY name COLLATE NOCASE") { ClientRow(it.getString(0), it.getString(1), it.getLong(2)) }

    /** True if the client existed. Everything stored for it cascades. */
    fun deleteClient(id: String): Boolean = writableDatabase.delete("clients", "id = ?", arrayOf(id)) > 0

    /** Does nothing if the session already exists. */
    fun createSession(id: String, clientId: String, startedAt: Long) {
        writableDatabase.insertWithOnConflict(
            "sessions", null,
            ContentValues().apply { put("id", id); put("client_id", clientId); put("started_at", startedAt) },
            SQLiteDatabase.CONFLICT_IGNORE,
        )
    }

    /** Oldest first. */
    fun sessions(client: String): List<SessionRow> =
        query("SELECT id, client_id, started_at, ended_at, observations FROM sessions WHERE client_id = ? ORDER BY started_at, id", client) {
            SessionRow(it.getString(0), it.getString(1), it.getLong(2), if (it.isNull(3)) null else it.getLong(3), it.getString(4))
        }

    fun observations(session: String): String = query("SELECT observations FROM sessions WHERE id = ?", session) { it.getString(0) }.firstOrNull().orEmpty()

    fun setObservations(session: String, text: String) {
        writableDatabase.update("sessions", ContentValues().apply { put("observations", text) }, "id = ?", arrayOf(session))
    }

    /**
     * Deletes a session and everything stored for it, in one transaction. A fact first seen in it moves to its earliest mention in another
     * session (that mention's session, evidence and segment), which then stops being a mention; a fact with no other mention is deleted.
     */
    fun deleteSession(id: String): SessionDelete = tx { db ->
        var promoted = 0
        var deleted = 0
        for (factId in query("SELECT id FROM facts WHERE session_id = ?", id) { it.getLong(0) }) {
            val next = query(
                """SELECT m.id, m.session_id, m.evidence, m.seg_idx FROM mentions m JOIN sessions s ON s.id = m.session_id
                   WHERE m.fact_id = ? AND m.session_id <> ? ORDER BY s.started_at, m.seg_idx, m.id LIMIT 1""",
                factId.toString(), id,
            ) { it.getLong(0) to ContentValues().apply { put("session_id", it.getString(1)); put("evidence", it.getString(2)); put("seg_idx", it.getInt(3)) } }.firstOrNull()
            if (next == null) {
                db.delete("facts", "id = ?", arrayOf(factId.toString()))
                deleted++
            } else {
                val (mentionId, takeOver) = next
                db.update("facts", takeOver, "id = ?", arrayOf(factId.toString()))
                db.delete("mentions", "id = ?", arrayOf(mentionId.toString()))
                promoted++
            }
        }
        db.delete("sessions", "id = ?", arrayOf(id))
        SessionDelete(promoted, deleted)
    }

    // ---- recordings and chunks -----------------------------------------------------------------------------------

    fun createRecording(sessionId: String?, engine: String, model: String, chunkSeconds: Int = 0): Long =
        writableDatabase.insertOrThrow(
            "recordings", null,
            ContentValues().apply {
                if (sessionId != null) put("session_id", sessionId)
                put("started_at", System.currentTimeMillis()); put("engine", engine); put("model", model); put("chunk_seconds", chunkSeconds)
            },
        )

    /** Newest first. */
    fun recordings(): List<RecordingRow> = query(
        """SELECT r.id, r.session_id, r.started_at, r.engine, r.model, COUNT(c.idx), COALESCE(MAX(c.end_ms), 0)
           FROM recordings r LEFT JOIN chunks c ON c.recording_id = r.id GROUP BY r.id ORDER BY r.id DESC""",
    ) { RecordingRow(it.getLong(0), it.getStringOrNull(1), it.getLong(2), it.getString(3), it.getString(4), it.getInt(5), it.getLong(6)) }

    /** Its chunks cascade. */
    fun deleteRecording(id: Long): Boolean = writableDatabase.delete("recordings", "id = ?", arrayOf(id.toString())) > 0

    /** Inserts or replaces the chunk with the same recording and index. */
    fun putChunk(c: ChunkRow) {
        writableDatabase.insertWithOnConflict(
            "chunks", null,
            ContentValues().apply {
                put("recording_id", c.recordingId); put("idx", c.idx); put("start_ms", c.startMs); put("end_ms", c.endMs); put("overlap_ms", c.overlapMs)
                if (c.text != null) put("text", c.text)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun chunks(recordingId: Long): List<ChunkRow> =
        query("SELECT recording_id, idx, start_ms, end_ms, overlap_ms, text FROM chunks WHERE recording_id = ? ORDER BY idx", recordingId.toString()) {
            ChunkRow(it.getLong(0), it.getInt(1), it.getLong(2), it.getLong(3), it.getLong(4), it.getStringOrNull(5))
        }

    // ---- segments and windows ------------------------------------------------------------------------------------

    fun replaceSegments(session: String, segments: List<SegmentRow>) = tx { db ->
        db.delete("segments", "session_id = ?", arrayOf(session))
        for (s in segments) {
            db.insertOrThrow("segments", null, ContentValues().apply { put("session_id", session); put("idx", s.idx); put("start_ms", s.startMs); put("end_ms", s.endMs); put("text", s.text) })
        }
    }

    fun segments(session: String): List<SegmentRow> =
        query("SELECT idx, start_ms, end_ms, text FROM segments WHERE session_id = ? ORDER BY idx", session) { SegmentRow(it.getInt(0), it.getLong(1), it.getLong(2), it.getString(3)) }

    /** How far the transcript of [session] reaches, 0 before it is transcribed. */
    fun transcriptLengthMs(session: String): Long = query("SELECT COALESCE(MAX(end_ms), 0) FROM segments WHERE session_id = ?", session) { it.getLong(0) }.first()

    /** Segments [seg] - [r] .. [seg] + [r] of [session], in order. */
    fun segmentsAround(session: String, seg: Int, r: Int): List<SegmentRow> =
        query("SELECT idx, start_ms, end_ms, text FROM segments WHERE session_id = ? AND idx BETWEEN ? AND ? ORDER BY idx", session, (seg - r).toString(), (seg + r).toString()) {
            SegmentRow(it.getInt(0), it.getLong(1), it.getLong(2), it.getString(3))
        }

    fun replaceWindows(session: String, windows: List<WindowRow>) = tx { db ->
        db.delete("windows", "session_id = ?", arrayOf(session))
        for (w in windows) {
            db.insertOrThrow(
                "windows", null,
                ContentValues().apply {
                    put("session_id", session); put("idx", w.idx); put("seg_from", w.segFrom); put("seg_to", w.segTo); put("text", w.text)
                    put("embedding", VectorMath.toBlob(w.embedding)); put("embed_model", w.embedModel)
                },
            )
        }
    }

    /** In order, without their embeddings. */
    fun windows(session: String): List<WindowRef> =
        query("SELECT session_id, idx, seg_from, seg_to, text FROM windows WHERE session_id = ? ORDER BY idx", session) {
            WindowRef(it.getString(0), it.getInt(1), it.getInt(2), it.getInt(3), it.getString(4))
        }

    fun hasWindows(session: String): Boolean = query("SELECT 1 FROM windows WHERE session_id = ? LIMIT 1", session) { true }.isNotEmpty()

    /** The [k] windows of [client] closest to [vec], best first. */
    fun nearestWindows(client: String, vec: FloatArray, k: Int): List<Pair<WindowRef, Float>> = VectorMath.topK(
        query(
            "SELECT w.session_id, w.idx, w.seg_from, w.seg_to, w.text, w.embedding FROM windows w JOIN sessions s ON s.id = w.session_id WHERE s.client_id = ?",
            client,
        ) { WindowRef(it.getString(0), it.getInt(1), it.getInt(2), it.getInt(3), it.getString(4)) to VectorMath.fromBlob(it.getBlob(5)) },
        vec, k,
    )

    // ---- facts and mentions --------------------------------------------------------------------------------------

    fun insertFact(f: NewFact): Long = insertFact(writableDatabase, f)

    /** False if this fact already has a mention at that place. */
    fun addMention(m: NewMention): Boolean = addMention(writableDatabase, m)

    /** In one transaction: the facts and mentions of [session] are replaced by [facts] and [mentions] (the mentions of its old facts go with them). */
    fun replaceSessionFacts(session: String, facts: List<NewFact>, mentions: List<NewMention>) = tx { db ->
        db.delete("mentions", "session_id = ?", arrayOf(session))
        db.delete("facts", "session_id = ?", arrayOf(session))
        facts.forEach { insertFact(db, it) }
        mentions.forEach { addMention(db, it) }
    }

    private fun insertFact(db: SQLiteDatabase, f: NewFact): Long = db.insertOrThrow(
        "facts", null,
        ContentValues().apply {
            put("client_id", f.clientId); put("session_id", f.sessionId); put("heading", f.heading); put("content", f.content); put("evidence", f.evidence)
            put("seg_idx", f.segIdx); if (f.embedding != null) put("embedding", VectorMath.toBlob(f.embedding))
            put("llm_model", f.llmModel); put("prompt_version", f.promptVersion); put("created_at", System.currentTimeMillis())
        },
    )

    private fun addMention(db: SQLiteDatabase, m: NewMention): Boolean = db.insertWithOnConflict(
        "mentions", null,
        ContentValues().apply { put("fact_id", m.factId); put("session_id", m.sessionId); put("evidence", m.evidence); put("seg_idx", m.segIdx); put("created_at", System.currentTimeMillis()) },
        SQLiteDatabase.CONFLICT_IGNORE,
    ) != -1L

    /** Oldest first. */
    fun facts(client: String): List<FactRow> = query("$FACT_COLUMNS FROM facts f WHERE f.client_id = ? ORDER BY f.created_at, f.id", client) { factOf(it) }

    /**
     * The [k] facts of [client] closest to [vec], best first. With [beforeSession], only facts first seen in sessions that started before it
     * (in [sessions] order), so a session is only ever compared with what was known when it took place.
     */
    fun nearestFacts(client: String, vec: FloatArray, k: Int, beforeSession: String? = null): List<Pair<FactRow, Float>> = VectorMath.topK(
        if (beforeSession == null) {
            query("$FACT_COLUMNS, f.embedding FROM facts f WHERE f.client_id = ? AND f.embedding IS NOT NULL", client) { factOf(it) to VectorMath.fromBlob(it.getBlob(9)) }
        } else {
            query(
                """$FACT_COLUMNS, f.embedding FROM facts f JOIN sessions o ON o.id = f.session_id JOIN sessions b ON b.id = ?
                   WHERE f.client_id = ? AND f.embedding IS NOT NULL AND (o.started_at < b.started_at OR (o.started_at = b.started_at AND o.id < b.id))""",
                beforeSession, client,
            ) { factOf(it) to VectorMath.fromBlob(it.getBlob(9)) }
        },
        vec, k,
    )

    /** The fact's first occurrence, then its mentions, in session order. */
    fun factOccurrences(factId: Long): List<Occurrence> = query(
        """SELECT f.session_id, s.started_at, f.evidence, f.seg_idx, 1 FROM facts f JOIN sessions s ON s.id = f.session_id WHERE f.id = ?
           UNION ALL
           SELECT m.session_id, s.started_at, m.evidence, m.seg_idx, 0 FROM mentions m JOIN sessions s ON s.id = m.session_id WHERE m.fact_id = ?
           ORDER BY 5 DESC, 2, 4""",
        factId.toString(), factId.toString(),
    ) { Occurrence(it.getString(0), it.getLong(1), it.getString(2), it.getInt(3), it.getInt(4) == 1) }

    // ---- caches ---------------------------------------------------------------------------------------------------

    /** The cached extraction of window [idx] as (key, json), whatever its key. */
    fun extractCache(session: String, idx: Int): Pair<String, String>? =
        query("SELECT key, json FROM extract_cache WHERE session_id = ? AND idx = ?", session, idx.toString()) { it.getString(0) to it.getString(1) }.firstOrNull()

    fun putExtractCache(session: String, idx: Int, key: String, json: String) {
        writableDatabase.insertWithOnConflict(
            "extract_cache", null,
            ContentValues().apply { put("session_id", session); put("idx", idx); put("key", key); put("json", json) },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun reviewVerdict(session: String, key: String): String? =
        query("SELECT verdict FROM review_cache WHERE session_id = ? AND cand_key = ?", session, key) { it.getString(0) }.firstOrNull()

    fun putReviewVerdict(session: String, key: String, verdict: String) {
        writableDatabase.insertWithOnConflict(
            "review_cache", null,
            ContentValues().apply { put("session_id", session); put("cand_key", key); put("verdict", verdict) },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    // ---- jobs ----------------------------------------------------------------------------------------------------

    override fun <T> inTransaction(block: () -> T): T = tx { block() }

    override fun jobs(): List<Job> {
        val deps = HashMap<Long, MutableList<Long>>()
        readableDatabase.rawQuery("SELECT job_id, depends_on FROM job_deps ORDER BY rowid", null).use { c ->
            while (c.moveToNext()) deps.getOrPut(c.getLong(0)) { ArrayList() } += c.getLong(1)
        }
        return query(
            """SELECT j.id, j.kind, j.session_id, s.client_id, s.started_at, j.recording_id, j.ref, j.status, j.attempts, j.max_attempts, j.error_code, j.created_at
               FROM jobs j JOIN sessions s ON s.id = j.session_id ORDER BY j.created_at, j.id""",
        ) {
            Job(
                it.getLong(0), it.getString(1), it.getString(2), it.getString(3), it.getLong(4), if (it.isNull(5)) null else it.getLong(5), it.getString(6),
                JobStatus.valueOf(it.getString(7)), it.getInt(8), it.getInt(9), it.getStringOrNull(10), it.getLong(11), deps[it.getLong(0)].orEmpty(),
            )
        }
    }

    override fun insertJob(kind: String, sessionId: String, recordingId: Long?, ref: String, maxAttempts: Int, dependsOn: List<Long>, now: Long): Long = tx { db ->
        val id = db.insertOrThrow(
            "jobs", null,
            ContentValues().apply {
                put("kind", kind); put("session_id", sessionId); if (recordingId != null) put("recording_id", recordingId); put("ref", ref)
                put("status", JobStatus.Pending.name); put("max_attempts", maxAttempts); put("created_at", now); put("updated_at", now)
            },
        )
        for (d in dependsOn) db.insertOrThrow("job_deps", null, ContentValues().apply { put("job_id", id); put("depends_on", d) })
        id
    }

    override fun updateJob(id: Long, status: JobStatus, attempts: Int, errorCode: String?, now: Long) {
        writableDatabase.update(
            "jobs",
            ContentValues().apply { put("status", status.name); put("attempts", attempts); if (errorCode == null) putNull("error_code") else put("error_code", errorCode); put("updated_at", now) },
            "id = ?", arrayOf(id.toString()),
        )
    }

    override fun addDependency(jobId: Long, dependsOn: Long) {
        writableDatabase.insertWithOnConflict("job_deps", null, ContentValues().apply { put("job_id", jobId); put("depends_on", dependsOn) }, SQLiteDatabase.CONFLICT_IGNORE)
    }

    override fun deleteJobs(ids: Collection<Long>) {
        tx { db -> ids.forEach { db.delete("jobs", "id = ?", arrayOf(it.toString())) } }
    }

    override fun sessionExists(id: String): Boolean = query("SELECT 1 FROM sessions WHERE id = ?", id) { true }.isNotEmpty()

    // ---- helpers -------------------------------------------------------------------------------------------------

    private fun <T> query(sql: String, vararg args: String, map: (Cursor) -> T): List<T> {
        val out = ArrayList<T>()
        readableDatabase.rawQuery(sql, args.takeIf { it.isNotEmpty() }).use { c -> while (c.moveToNext()) out += map(c) }
        return out
    }

    private fun <T> tx(block: (SQLiteDatabase) -> T): T {
        val db = writableDatabase
        db.beginTransaction()
        try {
            return block(db).also { db.setTransactionSuccessful() }
        } finally {
            db.endTransaction()
        }
    }

    private fun Cursor.getStringOrNull(i: Int): String? = if (isNull(i)) null else getString(i)

    private fun factOf(c: Cursor) = FactRow(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5), c.getInt(6), c.getLong(7), c.getInt(8))

    companion object {
        const val NAME = "mind.db"

        /** The two databases before this one. Their data is not migrated. */
        val LEGACY = listOf("recordings.db", "memory.db")

        private const val VERSION = 1

        private const val FACT_COLUMNS =
            "SELECT f.id, f.client_id, f.session_id, f.heading, f.content, f.evidence, f.seg_idx, f.created_at, (SELECT COUNT(*) FROM mentions m WHERE m.fact_id = f.id)"

        private val SCHEMA = listOf(
            "CREATE TABLE clients(id TEXT PRIMARY KEY, name TEXT NOT NULL, created_at INTEGER NOT NULL)",
            """CREATE TABLE sessions(
                 id TEXT PRIMARY KEY,
                 client_id TEXT NOT NULL REFERENCES clients(id) ON DELETE CASCADE,
                 started_at INTEGER NOT NULL,
                 ended_at INTEGER,
                 observations TEXT NOT NULL DEFAULT '')""",
            "CREATE INDEX sessions_client ON sessions(client_id, started_at)",
            // session_id is null for a recording made outside a session (the developer harness).
            """CREATE TABLE recordings(
                 id INTEGER PRIMARY KEY AUTOINCREMENT,
                 session_id TEXT REFERENCES sessions(id) ON DELETE CASCADE,
                 started_at INTEGER NOT NULL,
                 engine TEXT NOT NULL,
                 model TEXT NOT NULL,
                 chunk_seconds INTEGER NOT NULL)""",
            """CREATE TABLE chunks(
                 recording_id INTEGER NOT NULL REFERENCES recordings(id) ON DELETE CASCADE,
                 idx INTEGER NOT NULL,
                 start_ms INTEGER NOT NULL,
                 end_ms INTEGER NOT NULL,
                 overlap_ms INTEGER NOT NULL,
                 text TEXT,
                 PRIMARY KEY(recording_id, idx))""",
            """CREATE TABLE segments(
                 session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
                 idx INTEGER NOT NULL,
                 start_ms INTEGER NOT NULL,
                 end_ms INTEGER NOT NULL,
                 text TEXT NOT NULL,
                 PRIMARY KEY(session_id, idx))""",
            """CREATE TABLE windows(
                 session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
                 idx INTEGER NOT NULL,
                 seg_from INTEGER NOT NULL,
                 seg_to INTEGER NOT NULL,
                 text TEXT NOT NULL,
                 embedding BLOB NOT NULL,
                 embed_model TEXT NOT NULL,
                 PRIMARY KEY(session_id, idx))""",
            """CREATE TABLE facts(
                 id INTEGER PRIMARY KEY AUTOINCREMENT,
                 client_id TEXT NOT NULL REFERENCES clients(id) ON DELETE CASCADE,
                 session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
                 heading TEXT NOT NULL,
                 content TEXT NOT NULL,
                 evidence TEXT NOT NULL,
                 seg_idx INTEGER NOT NULL,
                 embedding BLOB,
                 llm_model TEXT NOT NULL,
                 prompt_version TEXT NOT NULL,
                 created_at INTEGER NOT NULL)""",
            "CREATE INDEX facts_client ON facts(client_id)",
            "CREATE INDEX facts_session ON facts(session_id)",
            """CREATE TABLE mentions(
                 id INTEGER PRIMARY KEY AUTOINCREMENT,
                 fact_id INTEGER NOT NULL REFERENCES facts(id) ON DELETE CASCADE,
                 session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
                 evidence TEXT NOT NULL,
                 seg_idx INTEGER NOT NULL,
                 created_at INTEGER NOT NULL,
                 UNIQUE(fact_id, session_id, seg_idx))""",
            "CREATE INDEX mentions_session ON mentions(session_id)",
            """CREATE TABLE extract_cache(
                 session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
                 idx INTEGER NOT NULL,
                 key TEXT NOT NULL,
                 json TEXT NOT NULL,
                 PRIMARY KEY(session_id, idx))""",
            """CREATE TABLE review_cache(
                 session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
                 cand_key TEXT NOT NULL,
                 verdict TEXT NOT NULL,
                 PRIMARY KEY(session_id, cand_key))""",
            // recording_id has no foreign key: cleanup deletes the recording while its jobs stay as done.
            """CREATE TABLE jobs(
                 id INTEGER PRIMARY KEY AUTOINCREMENT,
                 kind TEXT NOT NULL,
                 session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
                 recording_id INTEGER,
                 ref TEXT NOT NULL DEFAULT '',
                 status TEXT NOT NULL,
                 attempts INTEGER NOT NULL DEFAULT 0,
                 max_attempts INTEGER NOT NULL,
                 error_code TEXT,
                 created_at INTEGER NOT NULL,
                 updated_at INTEGER NOT NULL)""",
            "CREATE INDEX jobs_status ON jobs(status, created_at)",
            """CREATE TABLE job_deps(
                 job_id INTEGER NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
                 depends_on INTEGER NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
                 PRIMARY KEY(job_id, depends_on))""",
        )

        @SuppressLint("StaticFieldLeak") // built from the application context only
        @Volatile
        private var instance: MindStore? = null

        /** The process-wide store, opened on first use (needs the vault unlocked). The first open also deletes the legacy databases. */
        fun get(context: Context): MindStore = instance ?: synchronized(this) {
            instance ?: context.applicationContext.let { app ->
                LEGACY.forEach { app.deleteDatabase(it) }
                MindStore(app, NAME, Vault.dbPassword()).also { instance = it }
            }
        }

        /** Closes the process-wide store; the next [get] opens it again. */
        fun close() = synchronized(this) {
            instance?.close()
            instance = null
        }
    }
}
