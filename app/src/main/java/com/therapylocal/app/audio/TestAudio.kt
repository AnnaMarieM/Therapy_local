package com.therapylocal.app.audio

import android.content.Context
import com.therapylocal.app.asr.Pcm
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Debug builds only: replay a WAV from the app's files dir (the `files/test` folder) through the real chunker instead of
 * the microphone, so a run is repeatable with known speech. Mixes down to mono and resamples to 16 kHz by linear interpolation.
 */
object TestAudio {
    fun dir(context: Context): File = File(context.getExternalFilesDir(null), "test")

    fun list(context: Context): List<String> = dir(context).listFiles { f -> f.extension == "wav" }?.map { it.name }?.sorted() ?: emptyList()

    fun load(context: Context, name: String): ShortArray {
        val bytes = File(dir(context), name).readBytes()
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(bytes.size > 44 && String(bytes, 0, 4) == "RIFF") { "$name is not a WAV file" }
        var pos = 12
        var channels = 1
        var rate = Pcm.SAMPLE_RATE
        var dataStart = -1
        var dataLen = 0
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4)
            val len = bb.getInt(pos + 4)
            if (id == "fmt ") {
                channels = bb.getShort(pos + 10).toInt()
                rate = bb.getInt(pos + 12)
            } else if (id == "data") {
                dataStart = pos + 8
                dataLen = minOf(len, bytes.size - dataStart)
                break
            }
            pos += 8 + len + (len and 1)
        }
        require(dataStart >= 0) { "$name has no data chunk" }
        val frames = dataLen / 2 / channels
        val mono = ShortArray(frames) { i ->
            var sum = 0
            for (c in 0 until channels) sum += bb.getShort(dataStart + (i * channels + c) * 2)
            (sum / channels).toShort()
        }
        if (rate == Pcm.SAMPLE_RATE) return mono
        val outLen = (frames.toLong() * Pcm.SAMPLE_RATE / rate).toInt()
        return ShortArray(outLen) { i ->
            val src = i.toDouble() * rate / Pcm.SAMPLE_RATE
            val i0 = src.toInt().coerceAtMost(frames - 1)
            val i1 = (i0 + 1).coerceAtMost(frames - 1)
            val f = src - i0
            (mono[i0] * (1 - f) + mono[i1] * f).toInt().toShort()
        }
    }
}
