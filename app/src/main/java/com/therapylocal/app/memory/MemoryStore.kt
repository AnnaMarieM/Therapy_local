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
class MemoryStore(context: Context) : SQLiteOpenHelper(context, "memory.db", null, 1) {
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
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    fun clearClient(client: String) {
        writableDatabase.delete("chunks", "client = ?", arrayOf(client))
        writableDatabase.delete("facts", "client = ?", arrayOf(client))
        writableDatabase.delete("fact_support", "client = ?", arrayOf(client))
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
}
