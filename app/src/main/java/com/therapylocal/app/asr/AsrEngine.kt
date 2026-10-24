package com.therapylocal.app.asr

import com.therapylocal.app.log.LogCtx
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class AsrResult(val text: String, val audioSec: Double, val wallMs: Long)

/** Speech to text for one chunk of 16 kHz mono PCM16. Engines are loaded lazily and reused. */
interface AsrEngine {
    /** "gemma" or "whisper". */
    val engineName: String

    /** Model identity, for example "gemma-4-e2b" or "whisper-base". */
    val modelName: String

    /** Backend that actually loaded: NPU, GPU or CPU. "none" until [load] succeeds. */
    val backend: String

    suspend fun load()

    suspend fun transcribe(pcm: ShortArray, ctx: LogCtx): AsrResult

    fun close()
}

object Pcm {
    const val SAMPLE_RATE = com.therapylocal.app.config.ChunkConfig.SAMPLE_RATE

    fun seconds(pcm: ShortArray) = pcm.size.toDouble() / SAMPLE_RATE

    /** Root mean square level in [0, 1]. A level, not content, so it is safe to log. */
    fun rms(pcm: ShortArray): Double {
        if (pcm.isEmpty()) return 0.0
        var sum = 0.0
        for (s in pcm) sum += s.toDouble() * s
        return Math.sqrt(sum / pcm.size) / 32768.0
    }

    fun toFloats(pcm: ShortArray): FloatArray = FloatArray(pcm.size) { pcm[it] / 32768f }

    fun toWav(pcm: ShortArray): ByteArray {
        val data = pcm.size * 2
        val bb = ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray()).putInt(36 + data).put("WAVE".toByteArray())
        bb.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
        bb.putInt(SAMPLE_RATE).putInt(SAMPLE_RATE * 2).putShort(2).putShort(16)
        bb.put("data".toByteArray()).putInt(data)
        bb.asShortBuffer().put(pcm)
        return bb.array()
    }
}
