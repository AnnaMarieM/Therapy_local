package com.therapylocal.app.data

import android.content.Context
import com.therapylocal.app.audio.AudioChunk
import com.therapylocal.app.memory.MemoryFiles
import com.therapylocal.app.vault.SecureFile
import com.therapylocal.app.vault.Vault
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The audio of a recording, kept as SecureFiles until the session is fully processed, then deleted.
 *
 * Every chunk is saved while recording (nothing is transcribed live), so a crash mid-session loses no audio. Each is encrypted with the
 * vault key (`<idx>.pcm`), and the whole folder is deleted only by the session's last job (cleanup), once its facts are stored.
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
