package com.therapylocal.app.data

import android.content.Context
import com.therapylocal.app.BuildConfig
import com.therapylocal.app.log.AppLog
import com.therapylocal.app.log.LogCtx
import com.therapylocal.app.vault.Vault
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Debug and qa builds only ([BuildConfig.EXPORT_AUDIO]; the release build has no such code path): a decrypted 16 kHz mono wav of each
 * retained recording in the app's external files folder, `retained-wav/<session>_<recording>.wav`, so a developer can pull a real
 * recording with adb and run it through the speech models. It lives and dies with the retained audio it comes from.
 */
object DebugAudio {
    private fun root(context: Context) = File(context.getExternalFilesDir(null), "retained-wav")
    private fun file(context: Context, session: String, recording: Any) = File(root(context), "${session}_$recording.wav")

    /** Writes the wav for a retained recording (needs the vault unlocked). */
    fun export(context: Context, session: String, recordingId: Long) {
        if (!BuildConfig.EXPORT_AUDIO) return
        val chunks = PendingAudio.retainedDir(context, session, recordingId).listFiles { f -> f.name.endsWith(".pcm") }.orEmpty()
            .mapNotNull { f -> f.name.removeSuffix(".pcm").toIntOrNull()?.let { it to f } }.sortedBy { it.first }
        if (chunks.isEmpty()) return
        val out = file(context, session, recordingId).apply { parentFile?.mkdirs() }
        var samples = 0L
        RandomAccessFile(out, "rw").use { raf ->
            raf.setLength(0)
            raf.write(ByteArray(44))
            for ((_, f) in chunks) {
                val bytes = com.therapylocal.app.vault.SecureFile.read(f, Vault.key()) ?: continue
                val c = PendingAudio.decode(bytes)
                // Chunks overlap: the part already written by the previous chunk is skipped.
                val skip = (c.overlapMs * 16).toInt().coerceIn(0, c.pcm.size)
                val bb = ByteBuffer.allocate((c.pcm.size - skip) * 2).order(ByteOrder.LITTLE_ENDIAN)
                for (i in skip until c.pcm.size) bb.putShort(c.pcm[i])
                raf.write(bb.array())
                samples += c.pcm.size - skip
            }
            raf.seek(0)
            raf.write(header(samples * 2))
        }
        AppLog.i("Audio", "debug_wav_exported", LogCtx.NONE, "recording" to recordingId, "audio_sec" to "%.1f".format(samples / 16000.0))
    }

    fun delete(context: Context, session: String, recording: Any) {
        file(context, session, recording).delete()
    }

    fun deleteSession(context: Context, session: String) {
        root(context).listFiles { f -> f.name.startsWith("${session.replace(Regex("[^A-Za-z0-9._-]"), "_")}_") }?.forEach { it.delete() }
    }

    fun deleteAll(context: Context) {
        root(context).deleteRecursively()
    }

    private fun header(dataBytes: Long): ByteArray =
        ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt((36 + dataBytes).toInt()); put("WAVE".toByteArray()); put("fmt ".toByteArray())
            putInt(16); putShort(1); putShort(1); putInt(16000); putInt(32000); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(dataBytes.toInt())
        }.array()
}
