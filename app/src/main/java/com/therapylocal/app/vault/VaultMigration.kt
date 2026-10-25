package com.therapylocal.app.vault

import android.content.Context
import com.therapylocal.app.log.AppLog
import com.therapylocal.app.log.LogCtx
import com.therapylocal.app.memory.MemoryFiles
import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.io.File

/**
 * Brings data written by pre-vault versions under the Vault. Idempotent and safe after a crash midway; run with the DEK set and before
 * any store is opened.
 */
internal object VaultMigration {
    private const val TAG = "VAULT"
    private val SQLITE_MAGIC = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

    fun run(context: Context) {
        listOf("recordings.db", "memory.db").forEach { encryptDatabase(context, it) }
        migrateLegacyFiles(context)
    }

    /** Plaintext SQLite -> SQLCipher via sqlcipher_export into a temp file that then replaces the original. */
    private fun encryptDatabase(context: Context, name: String) {
        val f = context.getDatabasePath(name)
        val tmp = File(f.path + ".enc-tmp")
        tmp.delete()
        if (!isPlaintextSqlite(f)) return
        val db = SQLiteDatabase.openDatabase(f.path, "", null, SQLiteDatabase.OPEN_READWRITE, null, null)
        try {
            val version = db.version
            val keyLiteral = String(Vault.dbPassword(), Charsets.UTF_8)
            db.rawExecSQL("ATTACH DATABASE '${tmp.path}' AS enc KEY \"$keyLiteral\"")
            db.rawExecSQL("SELECT sqlcipher_export('enc')")
            db.rawExecSQL("PRAGMA enc.user_version = $version")
            db.rawExecSQL("DETACH DATABASE enc")
        } finally {
            db.close()
        }
        // A stale plaintext journal/WAL must never be applied to the encrypted file.
        listOf("-journal", "-wal", "-shm").forEach { File(f.path + it).delete() }
        if (!tmp.renameTo(f)) {
            f.delete()
            tmp.renameTo(f)
        }
        AppLog.i(TAG, "db_encrypted", LogCtx.NONE, "name" to name)
    }

    private fun isPlaintextSqlite(f: File): Boolean {
        if (!f.isFile || f.length() < SQLITE_MAGIC.size) return false
        val head = ByteArray(SQLITE_MAGIC.size)
        f.inputStream().use { if (it.read(head) != head.size) return false }
        return head.contentEquals(SQLITE_MAGIC)
    }

    /** Legacy external transcripts/queue move into SecureFiles; legacy diagnostics carrying patient text are deleted. */
    private fun migrateLegacyFiles(context: Context) {
        val dev = MemoryFiles.devRoot(context)
        val key = Vault.key()
        var transcripts = 0
        File(dev, "in").listFiles()?.filter { it.isFile }?.forEach {
            val target = MemoryFiles.transcript(context, it.name)
            if (!target.exists()) SecureFile.write(target, it.readBytes(), key)
            it.delete()
            transcripts++
        }
        val out = File(dev, "out")
        val queue = File(out, "queue.json")
        if (queue.isFile) {
            val target = MemoryFiles.queue(context)
            if (!target.exists()) SecureFile.write(target, queue.readBytes(), key)
            queue.delete()
        }
        var removed = 0
        out.listFiles()?.forEach {
            if (it.name == "raw" || it.name.endsWith(".chunks") || it.name in setOf("decisions.jsonl", "bench.jsonl", "ERROR", "DONE")) {
                if (it.deleteRecursively()) removed++
            }
        }
        if (transcripts + removed > 0) {
            AppLog.i(TAG, "legacy_files_migrated", LogCtx.NONE, "transcripts" to transcripts, "removed" to removed)
        }
    }
}
