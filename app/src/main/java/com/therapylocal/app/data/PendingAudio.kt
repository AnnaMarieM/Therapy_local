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
 * Audio of recordings made before setup finished, kept as SecureFiles until transcribed, then deleted.
 *
 * Audio is normally never written to disk. It is stored only for a deferred recording (the speech files are not on the phone yet),
 * because the session cannot be written down until they are. Every chunk is encrypted with the vault key (`<idx>.pcm`) and the whole
 * folder is deleted right after the transcript has been produced.
 */
object PendingAudio {
    private val MAGIC = "TLA1".toByteArray(Charsets.US_ASCII)
    private const val HEADER = 4 + 4 + 8 + 8 + 8 + 4

    fun dir(context: Context, recordingId: Long): File = File(MemoryFiles.secureRoot(context), "audio/$recordingId")

    fun save(context: Context, recordingId: Long, chunk: AudioChunk) {
        SecureFile.write(File(dir(context, recordingId), "${chunk.idx}.pcm"), encode(chunk), Vault.key())
    }

    /** All stored chunks of the recording, in chunk order. */
    fun load(context: Context, recordingId: Long): List<AudioChunk> {
        val files = dir(context, recordingId).listFiles { f -> f.isFile && f.name.endsWith(".pcm") }.orEmpty()
        val key = Vault.key()
        return files.sortedBy { it.name.removeSuffix(".pcm").toIntOrNull() ?: Int.MAX_VALUE }
            .mapNotNull { f -> SecureFile.read(f, key)?.let { decode(it) } }
            .sortedBy { it.idx }
    }

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
