package com.therapylocal.app.memory

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.therapylocal.app.data.Store

/**
 * Long-term memory per client: embedded transcript chunks (what a fact was said in) and the facts themselves, also embedded.
 * Facts point at their source chunk, so the therapist can always jump from a fact to the words. Not encrypted yet, like [Store].
 */
class MemoryStore(context: Context) : SQLiteOpenHelper(context, "memory.db", null, 2) {
    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE chunks(
                 id INTEGER PRIMARY KEY AUTOINCREMENT,
                 client TEXT NOT NULL,
                 session TEXT NOT NULL,
                 idx INTEGER NOT NULL,
                 text TEXT NOT NULL,
                 embedding BLOB NOT NULL,
                 created_at INTEGER NOT NULL,
                 UNIQUE(client, session, idx))""",
        )
        db.execSQL(
            """CREATE TABLE facts(
                 id TEXT NOT NULL,
                 client TEXT NOT NULL,
                 session TEXT NOT NULL,
                 category TEXT NOT NULL,
                 subject TEXT NOT NULL,
                 fact TEXT NOT NULL,
                 evidence TEXT NOT NULL,
                 chunk INTEGER NOT NULL,
                 embedding BLOB,
                 created_at INTEGER NOT NULL,
                 PRIMARY KEY(client, id))""",
        )
        db.execSQL("CREATE INDEX facts_client ON facts(client)")
        // Every later session that repeated a known fact: recurrence data, with the words that prove it.
        db.execSQL(
            """CREATE TABLE fact_support(
                 client TEXT NOT NULL,
                 fact_id TEXT NOT NULL,
                 session TEXT NOT NULL,
                 evidence TEXT NOT NULL,
                 chunk INTEGER NOT NULL)""",
        )
        createSessionMeta(db)
    }

    /** Per-session extras the pipeline does not produce: the session date and the therapist's own observations. */
    private fun createSessionMeta(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS session_meta(
                 client TEXT NOT NULL,
                 session TEXT NOT NULL,
                 date TEXT NOT NULL DEFAULT '',
                 observations TEXT NOT NULL DEFAULT '',
                 PRIMARY KEY(client, session))""",
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createSessionMeta(db)
    }

    fun clearClient(client: String) {
        writableDatabase.delete("chunks", "client = ?", arrayOf(client))
        writableDatabase.delete("facts", "client = ?", arrayOf(client))
        writableDatabase.delete("fact_support", "client = ?", arrayOf(client))
        writableDatabase.delete("session_meta", "client = ?", arrayOf(client))
    }

    fun putChunk(client: String, session: String, idx: Int, text: String, vec: FloatArray) {
        writableDatabase.insertWithOnConflict(
            "chunks", null,
            ContentValues().apply {
                put("client", client); put("session", session); put("idx", idx); put("text", text)
                put("embedding", Store.toBlob(vec)); put("created_at", System.currentTimeMillis())
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun putFact(client: String, f: Fact, vec: FloatArray?) {
        writableDatabase.insertWithOnConflict(
            "facts", null,
            ContentValues().apply {
                put("id", f.id); put("client", client); put("session", f.session); put("category", f.category); put("subject", f.subject)
                put("fact", f.fact); put("evidence", f.evidence); put("chunk", f.chunk)
                if (vec != null) put("embedding", Store.toBlob(vec))
                put("created_at", System.currentTimeMillis())
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun addSupport(client: String, factId: String, session: String, evidence: String, chunk: Int) {
        writableDatabase.insert(
            "fact_support", null,
            ContentValues().apply { put("client", client); put("fact_id", factId); put("session", session); put("evidence", evidence); put("chunk", chunk) },
        )
    }

    /** The [k] stored facts of [client] closest to [query] by cosine (vectors are normalised, so a dot product). Always returns up to k, no cutoff. */
    fun nearest(client: String, query: FloatArray, k: Int): List<Pair<Fact, Float>> {
        val scored = ArrayList<Pair<Fact, Float>>()
        readableDatabase.rawQuery(
            "SELECT id, category, subject, fact, evidence, session, chunk, embedding FROM facts WHERE client = ? AND embedding IS NOT NULL", arrayOf(client),
        ).use { c ->
            while (c.moveToNext()) {
                val v = Store.fromBlob(c.getBlob(7))
                if (v.size != query.size) continue
                var d = 0f
                for (i in v.indices) d += v[i] * query[i]
                scored += Fact(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), null, c.getString(5), c.getInt(6)) to d
            }
        }
        return scored.sortedByDescending { it.second }.take(k)
    }

    fun facts(client: String): List<Fact> {
        val out = ArrayList<Fact>()
        readableDatabase.rawQuery("SELECT id, category, subject, fact, evidence, session, chunk FROM facts WHERE client = ? ORDER BY created_at, rowid", arrayOf(client)).use { c ->
            while (c.moveToNext()) out += Fact(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), null, c.getString(5), c.getInt(6))
        }
        return out
    }

    fun counts(client: String): Pair<Int, Int> {
        fun n(sql: String) = readableDatabase.rawQuery(sql, arrayOf(client)).use { it.moveToFirst(); it.getInt(0) }
        return n("SELECT COUNT(*) FROM chunks WHERE client = ?") to n("SELECT COUNT(*) FROM facts WHERE client = ?")
    }

    // ---- clients, sessions, source text, recurrence (read by the UI repository) ----------------------------------

    /** Every client that has any stored data, sorted by name. */
    fun clients(): List<String> {
        val out = ArrayList<String>()
        readableDatabase.rawQuery(
            "SELECT client FROM chunks UNION SELECT client FROM facts UNION SELECT client FROM session_meta ORDER BY 1", null,
        ).use { c -> while (c.moveToNext()) out += c.getString(0) }
        return out
    }

    /** One session of a client: its date (empty if unknown) and how much was stored for it. */
    data class SessionInfo(val id: String, val date: String, val chunks: Int, val facts: Int)

    /** Sessions of [client] (chunks grouped by session), oldest first: by date when known, else by id. */
    fun sessions(client: String): List<SessionInfo> {
        val out = ArrayList<SessionInfo>()
        readableDatabase.rawQuery(
            """SELECT s.session, COALESCE(m.date, ''),
                      (SELECT COUNT(*) FROM chunks c WHERE c.client = s.client AND c.session = s.session),
                      (SELECT COUNT(*) FROM facts f WHERE f.client = s.client AND f.session = s.session)
               FROM (SELECT client, session FROM chunks UNION SELECT client, session FROM facts UNION SELECT client, session FROM session_meta) s
               LEFT JOIN session_meta m ON m.client = s.client AND m.session = s.session
               WHERE s.client = ?""",
            arrayOf(client),
        ).use { c -> while (c.moveToNext()) out += SessionInfo(c.getString(0), c.getString(1), c.getInt(2), c.getInt(3)) }
        return out.sortedWith(compareBy({ it.date.ifEmpty { "~" } }, { it.id }))
    }

    fun putSession(client: String, session: String, date: String) {
        writableDatabase.execSQL(
            "INSERT INTO session_meta(client, session, date) VALUES(?, ?, ?) ON CONFLICT(client, session) DO UPDATE SET date = excluded.date",
            arrayOf(client, session, date),
        )
    }

    /** The transcript chunks of one session in order, as (idx, text). */
    fun chunks(client: String, session: String): List<Pair<Int, String>> {
        val out = ArrayList<Pair<Int, String>>()
        readableDatabase.rawQuery("SELECT idx, text FROM chunks WHERE client = ? AND session = ? ORDER BY idx", arrayOf(client, session)).use { c ->
            while (c.moveToNext()) out += c.getInt(0) to c.getString(1)
        }
        return out
    }

    /** The source words behind a fact: the transcript chunk [idx] of [session], or null if it was never stored. */
    fun chunkText(client: String, session: String, idx: Int): String? =
        readableDatabase.rawQuery("SELECT text FROM chunks WHERE client = ? AND session = ? AND idx = ?", arrayOf(client, session, idx.toString())).use {
            if (it.moveToFirst()) it.getString(0) else null
        }

    data class ChunkHit(val session: String, val idx: Int, val text: String, val score: Float)

    /** The [k] stored chunks of [client] closest to [query] by cosine. Chunks without a usable vector (demo data) are skipped. */
    fun nearestChunks(client: String, query: FloatArray, k: Int): List<ChunkHit> {
        val scored = ArrayList<ChunkHit>()
        readableDatabase.rawQuery("SELECT session, idx, text, embedding FROM chunks WHERE client = ?", arrayOf(client)).use { c ->
            while (c.moveToNext()) {
                val v = Store.fromBlob(c.getBlob(3))
                if (v.size != query.size) continue
                var d = 0f
                for (i in v.indices) d += v[i] * query[i]
                scored += ChunkHit(c.getString(0), c.getInt(1), c.getString(2), d)
            }
        }
        return scored.sortedByDescending { it.score }.take(k)
    }

    /** How many later sessions repeated each fact: fact id -> number of supports. Facts never repeated are absent. */
    fun supportCounts(client: String): Map<String, Int> {
        val out = HashMap<String, Int>()
        readableDatabase.rawQuery("SELECT fact_id, COUNT(*) FROM fact_support WHERE client = ? GROUP BY fact_id", arrayOf(client)).use { c ->
            while (c.moveToNext()) out[c.getString(0)] = c.getInt(1)
        }
        return out
    }

    /** One repeat of a known fact, with the words that prove it. */
    data class Support(val factId: String, val session: String, val evidence: String, val chunk: Int)

    fun supports(client: String): List<Support> {
        val out = ArrayList<Support>()
        readableDatabase.rawQuery("SELECT fact_id, session, evidence, chunk FROM fact_support WHERE client = ? ORDER BY rowid", arrayOf(client)).use { c ->
            while (c.moveToNext()) out += Support(c.getString(0), c.getString(1), c.getString(2), c.getInt(3))
        }
        return out
    }

    // ---- therapist observations, deletion of one session ----------------------------------------------------------

    fun observations(client: String, session: String): String =
        readableDatabase.rawQuery("SELECT observations FROM session_meta WHERE client = ? AND session = ?", arrayOf(client, session)).use {
            if (it.moveToFirst()) it.getString(0) else ""
        }

    fun setObservations(client: String, session: String, text: String) {
        writableDatabase.execSQL(
            "INSERT INTO session_meta(client, session, observations) VALUES(?, ?, ?) ON CONFLICT(client, session) DO UPDATE SET observations = excluded.observations",
            arrayOf(client, session, text),
        )
    }

    data class SessionDelete(val chunks: Int, val facts: Int, val supports: Int)

    /**
     * Removes everything stored for one session: its chunks, the facts first seen in it, every repeat recorded in it, repeats in later sessions
     * that pointed at the removed facts (they would dangle), and its date and observations. One transaction.
     */
    fun deleteSession(client: String, session: String): SessionDelete {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val key = arrayOf(client, session)
            db.execSQL("DELETE FROM fact_support WHERE client = ? AND fact_id IN (SELECT id FROM facts WHERE client = ? AND session = ?)", arrayOf(client, client, session))
            val supports = db.delete("fact_support", "client = ? AND session = ?", key)
            val facts = db.delete("facts", "client = ? AND session = ?", key)
            val chunks = db.delete("chunks", "client = ? AND session = ?", key)
            db.delete("session_meta", "client = ? AND session = ?", key)
            db.setTransactionSuccessful()
            return SessionDelete(chunks, facts, supports)
        } finally {
            db.endTransaction()
        }
    }
}
