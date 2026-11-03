package com.therapylocal.app.data

import android.content.Context
import com.therapylocal.app.audio.AudioChunk
import com.therapylocal.app.log.AppLog
import com.therapylocal.app.log.LogCtx
import com.therapylocal.app.memory.MemoryFiles
import com.therapylocal.app.vault.SecureFile
import com.therapylocal.app.vault.Vault
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The audio of a recording, kept as SecureFiles.
 *
 * Every chunk is saved while recording (nothing is transcribed live), so a crash mid-session loses no audio. Each is encrypted with the
 * vault key (`<idx>.pcm`). When the session's last job (cleanup) has stored its facts, the audio is not deleted but moved to
 * `retained/<session>/<recording>` and kept for [RETENTION_DAYS] days as debugging material (so a transcript can be re-run and checked
 * against what was said). It stays encrypted, and goes at once with its session or client, with an erase of the vault, or when a week has passed.
 */
object PendingAudio {
    private val MAGIC = "TLA1".toByteArray(Charsets.US_ASCII)
    private const val HEADER = 4 + 4 + 8 + 8 + 8 + 4

    fun dir(context: Context, recordingId: Long): File = File(MemoryFiles.secureRoot(context), "audio/$recordingId")

    fun save(context: Context, recordingId: Long, chunk: AudioChunk) {
        SecureFile.write(File(dir(context, recordingId), "${chunk.idx}.pcm"), encode(chunk), Vault.key())
    }

    /** The indices of the stored chunks, in order, without reading them (a long recording does not fit in memory at once). */
    fun indices(context: Context, recordingId: Long): List<Int> =
        dir(context, recordingId).listFiles { f -> f.isFile && f.name.endsWith(".pcm") }.orEmpty().mapNotNull { it.name.removeSuffix(".pcm").toIntOrNull() }.sorted()

    /** One stored chunk, null if it is missing. Throws (IOException / GeneralSecurityException) if it cannot be read. */
    fun load(context: Context, recordingId: Long, idx: Int): AudioChunk? =
        SecureFile.read(File(dir(context, recordingId), "$idx.pcm"), Vault.key())?.let { decode(it) }

    fun has(context: Context, recordingId: Long): Boolean =
        dir(context, recordingId).listFiles { f -> f.isFile && f.name.endsWith(".pcm") }.orEmpty().isNotEmpty()

    fun delete(context: Context, recordingId: Long) {
        dir(context, recordingId).deleteRecursively()
    }

    // ---- retention ---------------------------------------------------------------------------------------------

    const val RETENTION_DAYS = 7L

    private fun retainedRoot(context: Context): File = File(MemoryFiles.secureRoot(context), "retained")

    /** Session ids are free text: keep only what is safe in a folder name. */
    private fun folder(session: String): String = session.replace(Regex("[^A-Za-z0-9._-]"), "_")

    fun retainedDir(context: Context, session: String, recordingId: Long): File = File(retainedRoot(context), "${folder(session)}/$recordingId")

    /** Moves the recording's audio out of the pending folder into the retained one. Nothing to move leaves it as it is. */
    fun retain(context: Context, recordingId: Long, session: String) {
        val from = dir(context, recordingId)
        if (!from.isDirectory) return
        val to = retainedDir(context, session, recordingId)
        to.parentFile?.mkdirs()
        to.deleteRecursively()
        if (!from.renameTo(to)) {
            from.copyRecursively(to, overwrite = true)
            from.deleteRecursively()
        }
        AppLog.i(TAG, "audio_retained", LogCtx.NONE, "recording" to recordingId, "days" to RETENTION_DAYS)
    }

    /** The retained audio of a session goes now (the session or its client was deleted). */
    fun deleteRetained(context: Context, session: String) {
        File(retainedRoot(context), folder(session)).deleteRecursively()
        DebugAudio.deleteSession(context, session)
    }

    fun deleteAllRetained(context: Context) {
        retainedRoot(context).deleteRecursively()
        DebugAudio.deleteAll(context)
    }

    /** Deletes the retained recordings older than [RETENTION_DAYS] days, by when their audio was last written. Returns how many went. */
    fun sweepRetained(context: Context, now: Long = System.currentTimeMillis()): Int {
        val limit = now - RETENTION_DAYS * 24 * 60 * 60 * 1000
        var removed = 0
        for (session in retainedRoot(context).listFiles().orEmpty()) {
            for (recording in session.listFiles().orEmpty()) {
                val written = recording.walkTopDown().maxOfOrNull { it.lastModified() } ?: 0L
                if (written < limit) { recording.deleteRecursively(); DebugAudio.delete(context, session.name, recording.name); removed++ }
            }
            if (session.listFiles().isNullOrEmpty()) session.delete()
        }
        if (removed > 0) AppLog.i(TAG, "retained_audio_expired", LogCtx.NONE, "recordings" to removed)
        return removed
    }

    /** The retained recordings: session folder name and recording id. */
    fun retained(context: Context): List<Pair<String, Long>> =
        retainedRoot(context).listFiles().orEmpty().flatMap { s -> s.listFiles().orEmpty().mapNotNull { r -> r.name.toLongOrNull()?.let { s.name to it } } }

    private const val TAG = "Audio"

    /** "TLA1" + idx + startMs + endMs + overlapMs + sample count + PCM16 little-endian. */
    fun encode(chunk: AudioChunk): ByteArray {
        val bb = ByteBuffer.allocate(HEADER + chunk.pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        bb.put(MAGIC).putInt(chunk.idx).putLong(chunk.startMs).putLong(chunk.endMs).putLong(chunk.overlapMs).putInt(chunk.pcm.size)
        for (s in chunk.pcm) bb.putShort(s)
        return bb.array()
    }

    /** Throws IOException if the magic or the length does not match. */
    fun decode(bytes: ByteArray): AudioChunk {
        if (bytes.size < HEADER || !bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) throw IOException("not a pending audio chunk")
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        bb.position(MAGIC.size)
        val idx = bb.int
        val startMs = bb.long
        val endMs = bb.long
        val overlapMs = bb.long
        val n = bb.int
        if (n < 0 || bytes.size.toLong() != HEADER.toLong() + n * 2L) throw IOException("pending audio chunk has the wrong length")
        val pcm = ShortArray(n) { bb.short }
        return AudioChunk(idx, startMs, endMs, overlapMs, pcm)
    }
}
