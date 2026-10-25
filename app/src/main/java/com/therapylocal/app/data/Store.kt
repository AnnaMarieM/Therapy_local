package com.therapylocal.app.data

import android.content.ContentValues
import android.content.Context
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SQLiteOpenHelper
import com.therapylocal.app.log.AppLog
import com.therapylocal.app.log.LogCtx
import com.therapylocal.app.vault.Vault
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class RecordingRow(
    val id: Long,
    val startedAt: Long,
    val engine: String,
    val model: String,
    val chunkCount: Int,
    val durationMs: Long,
)

/** One chunk. [startMs]..[endMs] is its span in the recording; it shares [overlapMs] with the previous chunk. */
data class ChunkRow(
    val id: Long,
    val recordingId: Long,
    val idx: Int,
    val startMs: Long,
    val endMs: Long,
    val overlapMs: Long,
    val text: String,
    val asrEngine: String,
    val asrModel: String,
    val asrMs: Long,
    val embedModel: String,
    val embedDim: Int,
    val embedBackend: String,
)

data class DeleteResult(val recordings: Int, val chunks: Int)

/**
 * Local store: recordings and their chunks. Each chunk keeps its transcript (for reading), an AUDIO embedding (what search
 * compares against) and a TEXT embedding of the transcript (kept so retrieval quality of the two can be compared). No audio is stored.
 * Foreign keys cascade, so deleting a recording removes its chunks, transcripts and embeddings in one transaction.
 * Encrypted with SQLCipher (AES-256) under the vault key, see vault/Vault.kt. Opening the database while the vault is locked throws
 * [com.therapylocal.app.vault.VaultLockedException] (fail closed: no key, no data).
 */
class Store(context: Context) : SQLiteOpenHelper(context, "recordings.db", Vault.dbPassword(), null, 2, 0, null, null, false) {

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE recordings(
                 id INTEGER PRIMARY KEY AUTOINCREMENT,
                 started_at INTEGER NOT NULL,
                 engine TEXT NOT NULL,
                 model TEXT NOT NULL,
                 chunk_seconds INTEGER NOT NULL)""",
        )
        createChunks(db, "chunks")
    }

    private fun createChunks(db: SQLiteDatabase, name: String) {
        db.execSQL(
            """CREATE TABLE $name(
                 id INTEGER PRIMARY KEY AUTOINCREMENT,
                 recording_id INTEGER NOT NULL REFERENCES recordings(id) ON DELETE CASCADE,
                 idx INTEGER NOT NULL,
                 start_ms INTEGER NOT NULL,
                 end_ms INTEGER NOT NULL,
                 overlap_ms INTEGER NOT NULL,
                 text TEXT NOT NULL,
                 asr_engine TEXT NOT NULL,
                 asr_model TEXT NOT NULL,
                 asr_ms INTEGER NOT NULL,
                 embed_model TEXT NOT NULL,
                 embed_dim INTEGER NOT NULL,
                 embed_backend TEXT NOT NULL,
                 embedding BLOB NOT NULL,
                 embedding_text BLOB,
                 created_at INTEGER NOT NULL)""",
        )
    }

    /** v1 to v2: chunks gain start/end/overlap and a text vector. Old rows are kept, with the span derived from their offset. */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            createChunks(db, "chunks_v2")
            db.execSQL(
                """INSERT INTO chunks_v2(id, recording_id, idx, start_ms, end_ms, overlap_ms, text, asr_engine, asr_model, asr_ms,
                                         embed_model, embed_dim, embed_backend, embedding, embedding_text, created_at)
                   SELECT id, recording_id, idx, offset_ms, offset_ms + duration_ms, 0, text, asr_engine, asr_model, asr_ms,
                          embed_model, embed_dim, embed_backend, embedding, NULL, created_at FROM chunks""",
            )
            db.execSQL("DROP TABLE chunks")
            db.execSQL("ALTER TABLE chunks_v2 RENAME TO chunks")
            AppLog.i(TAG, "db_migrated", LogCtx.NONE, "from" to oldVersion, "to" to newVersion)
        }
    }

    override fun onOpen(db: SQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS chunks_recording ON chunks(recording_id, idx)")
    }

    fun createRecording(engine: String, model: String): Long = AppLog.span(TAG, "db_create_recording", LogCtx.NONE, "engine" to engine, "model" to model) { span ->
        val id = writableDatabase.insertOrThrow(
            "recordings", null,
            ContentValues().apply {
                put("started_at", System.currentTimeMillis())
                put("engine", engine)
                put("model", model)
                put("chunk_seconds", 0) // legacy column, the chunk length now lives in ChunkConfig
            },
        )
        span.put("recording_id", id)
        id
    }

    fun insertChunk(c: ChunkRow, audioVec: FloatArray, textVec: FloatArray?): Long {
        val ctx = LogCtx(c.recordingId, "${c.recordingId}.${c.idx}")
        return AppLog.span(TAG, "db_insert_chunk", ctx, "start_ms" to c.startMs, "end_ms" to c.endMs, "overlap_ms" to c.overlapMs, "text_chars" to c.text.length, "embed_dim" to audioVec.size) { span ->
            val id = writableDatabase.insertOrThrow(
                "chunks", null,
                ContentValues().apply {
                    put("recording_id", c.recordingId)
                    put("idx", c.idx)
                    put("start_ms", c.startMs)
                    put("end_ms", c.endMs)
                    put("overlap_ms", c.overlapMs)
                    put("text", c.text)
                    put("asr_engine", c.asrEngine)
                    put("asr_model", c.asrModel)
                    put("asr_ms", c.asrMs)
                    put("embed_model", c.embedModel)
                    put("embed_dim", audioVec.size)
                    put("embed_backend", c.embedBackend)
                    put("embedding", toBlob(audioVec))
                    if (textVec != null) put("embedding_text", toBlob(textVec))
                    put("created_at", System.currentTimeMillis())
                },
            )
            span.put("chunk_row", id)
            id
        }
    }

    fun listRecordings(): List<RecordingRow> = AppLog.span(TAG, "db_list_recordings") { span ->
        val out = ArrayList<RecordingRow>()
        readableDatabase.rawQuery(
            """SELECT r.id, r.started_at, r.engine, r.model, COUNT(c.id), COALESCE(MAX(c.end_ms), 0)
               FROM recordings r LEFT JOIN chunks c ON c.recording_id = r.id
               GROUP BY r.id ORDER BY r.id DESC""",
            null,
        ).use { cur ->
            while (cur.moveToNext()) out += RecordingRow(cur.getLong(0), cur.getLong(1), cur.getString(2), cur.getString(3), cur.getInt(4), cur.getLong(5))
        }
        span.put("rows", out.size)
        out
    }

    fun chunks(recordingId: Long): List<ChunkRow> {
        val out = ArrayList<ChunkRow>()
        readableDatabase.rawQuery("$CHUNK_COLUMNS FROM chunks WHERE recording_id = ? ORDER BY idx", arrayOf(recordingId.toString())).use { cur ->
            while (cur.moveToNext()) out += chunkOf(cur)
        }
        return out
    }

    /** Streams every chunk of [model] with its audio and text vectors, for brute-force cosine search. */
    inline fun forEachEmbedding(model: String, block: (row: ChunkRow, audio: FloatArray, text: FloatArray?) -> Unit) {
        readableDatabase.rawQuery("$CHUNK_COLUMNS, embedding, embedding_text FROM chunks WHERE embed_model = ?", arrayOf(model)).use { cur ->
            while (cur.moveToNext()) {
                block(chunkOf(cur), fromBlob(cur.getBlob(13)), if (cur.isNull(14)) null else fromBlob(cur.getBlob(14)))
            }
        }
    }

    /** Chunks embedded by some other model, which search must ignore (different vector space). */
    fun countOtherModels(model: String): Int = count(readableDatabase, "SELECT COUNT(*) FROM chunks WHERE embed_model <> ?", model)

    fun deleteRecording(id: Long): DeleteResult = AppLog.span(TAG, "db_delete_recording", LogCtx(rid = id)) { span ->
        val db = writableDatabase
        db.beginTransaction()
        try {
            val chunks = count(db, "SELECT COUNT(*) FROM chunks WHERE recording_id = ?", id.toString())
            val recs = db.delete("recordings", "id = ?", arrayOf(id.toString()))
            db.setTransactionSuccessful()
            span.put("recordings_removed", recs)
            span.put("chunks_removed", chunks)
            span.put("chunks_left_for_recording", count(db, "SELECT COUNT(*) FROM chunks WHERE recording_id = ?", id.toString()))
            DeleteResult(recs, chunks)
        } finally {
            db.endTransaction()
        }
    }

    fun deleteAll(): DeleteResult = AppLog.span(TAG, "db_delete_all") { span ->
        val db = writableDatabase
        db.beginTransaction()
        try {
            val chunks = count(db, "SELECT COUNT(*) FROM chunks")
            val recs = db.delete("recordings", null, null)
            db.setTransactionSuccessful()
            span.put("recordings_removed", recs)
            span.put("chunks_removed", chunks)
            span.put("chunks_left", count(db, "SELECT COUNT(*) FROM chunks"))
            DeleteResult(recs, chunks)
        } finally {
            db.endTransaction()
        }
    }

    /** Row counts, used to prove a delete left nothing behind. */
    fun counts(): Pair<Int, Int> = count(readableDatabase, "SELECT COUNT(*) FROM recordings") to count(readableDatabase, "SELECT COUNT(*) FROM chunks")

    private fun count(db: SQLiteDatabase, sql: String, vararg args: String): Int =
        db.rawQuery(sql, args.takeIf { it.isNotEmpty() }).use { it.moveToFirst(); it.getInt(0) }

    @PublishedApi
    internal fun chunkOf(cur: android.database.Cursor) = ChunkRow(
        cur.getLong(0), cur.getLong(1), cur.getInt(2), cur.getLong(3), cur.getLong(4), cur.getLong(5), cur.getString(6),
        cur.getString(7), cur.getString(8), cur.getLong(9), cur.getString(10), cur.getInt(11), cur.getString(12),
    )

    companion object {
        const val TAG = "DB"

        @PublishedApi
        internal const val CHUNK_COLUMNS =
            "SELECT id, recording_id, idx, start_ms, end_ms, overlap_ms, text, asr_engine, asr_model, asr_ms, embed_model, embed_dim, embed_backend"

        fun toBlob(v: FloatArray): ByteArray {
            val bb = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            bb.asFloatBuffer().put(v)
            return bb.array()
        }

        @PublishedApi
        internal fun fromBlob(b: ByteArray): FloatArray {
            val out = FloatArray(b.size / 4)
            ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(out)
            return out
        }
    }
}
