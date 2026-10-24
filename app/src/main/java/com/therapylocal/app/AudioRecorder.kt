package com.therapylocal.app

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Hold-to-record microphone capture: 16 kHz mono PCM16, returned as WAV bytes. */
class AudioRecorder {
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    private val pcm = ByteArrayOutputStream()
    @Volatile private var recording = false

    @SuppressLint("MissingPermission")
    fun start(onLimit: () -> Unit) {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(
            MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 4,
        )
        check(rec.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord failed to initialise" }
        pcm.reset()
        record = rec
        recording = true
        rec.startRecording()
        thread = Thread({
            val buf = ByteArray(minBuf)
            while (recording) {
                val n = rec.read(buf, 0, buf.size)
                if (n > 0) pcm.write(buf, 0, n)
                if (pcm.size() >= MAX_BYTES) {
                    recording = false
                    onLimit()
                }
            }
        }, "audio-record").also { it.start() }
    }

    /** Stops capture and returns WAV bytes, or null if nothing usable was recorded. */
    fun stop(): ByteArray? {
        recording = false
        thread?.join()
        record?.apply { stop(); release() }
        record = null
        val data = pcm.toByteArray()
        return if (data.size < MIN_BYTES) null else wav(data)
    }

    private fun wav(data: ByteArray): ByteArray {
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + data.size); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
            putInt(SAMPLE_RATE); putInt(SAMPLE_RATE * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(data.size)
        }
        return header.array() + data
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        private const val MAX_BYTES = SAMPLE_RATE * 2 * 30 // Gemma audio input is capped at 30 s
        private const val MIN_BYTES = SAMPLE_RATE * 2 / 4 // ignore taps shorter than 250 ms
    }
}
