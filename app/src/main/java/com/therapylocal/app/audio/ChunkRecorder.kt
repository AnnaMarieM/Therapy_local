package com.therapylocal.app.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import com.therapylocal.app.asr.Pcm
import com.therapylocal.app.config.ChunkConfig
import com.therapylocal.app.log.AppLog
import com.therapylocal.app.log.LogCtx
import kotlinx.coroutines.channels.Channel

/**
 * One slice of a recording: 16 kHz mono PCM16, held in memory only.
 * Chunks overlap: chunk i covers [startMs, endMs] and shares [overlapMs] with chunk i-1 (see ChunkConfig).
 */
class AudioChunk(val idx: Int, val startMs: Long, val endMs: Long, val overlapMs: Long, val pcm: ShortArray)

/**
 * Cuts a live PCM stream into overlapping chunks while it keeps recording.
 * Chunks arrive on [chunks] as they are cut; the channel closes after the final chunk.
 * Audio is never written to disk. Use [recording] for a session and [query] for a one-shot voice search clip.
 */
class ChunkRecorder private constructor(
    private val chunkSamples: Int,
    private val overlapSamples: Int,
    private val minNewSamples: Int,
    private val minFirstSamples: Int,
    private val ctx: LogCtx,
    private val label: String,
) {
    val chunks = Channel<AudioChunk>(Channel.UNLIMITED)

    /** Sees every block of raw samples (buffer, count) on the capture thread, before chunking. Must be fast and must not keep the buffer. */
    @Volatile
    var tap: ((ShortArray, Int) -> Unit)? = null

    @Volatile
    private var running = false
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    private var startedAt = 0L

    /** Live microphone. */
    @SuppressLint("MissingPermission")
    fun start() {
        val minBuf = AudioRecord.getMinBufferSize(Pcm.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.MIC, Pcm.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 4)
        check(rec.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord failed to initialise" }
        record = rec
        logStart("mic", minBuf)
        rec.startRecording()
        launch(minBuf / 2) { buf -> rec.read(buf, 0, buf.size) }
    }

    /** Debug and test source: replays [pcm] at real-time pace (times [speed]) instead of the microphone. */
    fun startFromPcm(pcm: ShortArray, speed: Float = 1f) {
        var pos = 0
        logStart("file", 0)
        launch(FILE_READ_SAMPLES) { buf ->
            if (pos >= pcm.size) {
                running = false
                -1
            } else {
                val n = minOf(buf.size, pcm.size - pos)
                System.arraycopy(pcm, pos, buf, 0, n)
                pos += n
                Thread.sleep((n * 1000L / Pcm.SAMPLE_RATE / speed).toLong().coerceAtLeast(1))
                n
            }
        }
    }

    private fun logStart(source: String, minBuf: Int) {
        running = true
        startedAt = SystemClock.elapsedRealtime()
        AppLog.i(
            TAG, "record_start", ctx,
            "kind" to label, "source" to source, "chunk_s" to chunkSamples / Pcm.SAMPLE_RATE, "overlap_s" to overlapSamples / Pcm.SAMPLE_RATE,
            "sample_rate" to Pcm.SAMPLE_RATE, "min_buf_bytes" to minBuf,
        )
    }

    private fun launch(readSize: Int, read: (ShortArray) -> Int) {
        thread = Thread({ loop(readSize, read) }, "chunk-recorder").also { it.start() }
    }

    private fun loop(readSize: Int, read: (ShortArray) -> Int) {
        var buf = ShortArray(chunkSamples)
        var filled = 0
        var idx = 0
        var readErrors = 0
        val tmp = ShortArray(readSize)
        while (running) {
            val n = read(tmp)
            if (n < 0) {
                if (!running) break // end of a file source
                readErrors++
                AppLog.w(TAG, "record_read_error", ctx, "code" to n, "errors" to readErrors)
                if (readErrors > 5) break
                continue
            }
            tap?.invoke(tmp, n)
            var off = 0
            while (off < n) {
                val take = minOf(n - off, chunkSamples - filled)
                System.arraycopy(tmp, off, buf, filled, take)
                filled += take
                off += take
                if (filled == chunkSamples) {
                    emit(idx++, buf.copyOf(filled))
                    // keep the tail as the overlap of the next chunk
                    System.arraycopy(buf, chunkSamples - overlapSamples, buf, 0, overlapSamples)
                    filled = overlapSamples
                }
            }
        }
        // Final chunk: it includes the overlap with the previous chunk, so judge it by the NEW audio it adds.
        val newSamples = if (idx == 0) filled else filled - overlapSamples
        val keep = if (idx == 0) filled >= minFirstSamples else newSamples >= minNewSamples
        if (keep) emit(idx++, buf.copyOf(filled))
        else if (filled > 0) {
            AppLog.i(
                TAG, "chunk_dropped_short", ctx,
                "new_ms" to newSamples * 1000L / Pcm.SAMPLE_RATE, "min_new_ms" to (if (idx == 0) minFirstSamples else minNewSamples) * 1000L / Pcm.SAMPLE_RATE,
            )
        }
        chunks.close()
        AppLog.i(TAG, "record_loop_end", ctx, "kind" to label, "chunks" to idx, "read_errors" to readErrors)
        buf = ShortArray(0)
    }

    private fun emit(idx: Int, pcm: ShortArray) {
        val c = ctx.chunk(idx)
        val startMs = idx * (chunkSamples - overlapSamples) * 1000L / Pcm.SAMPLE_RATE
        val endMs = startMs + pcm.size * 1000L / Pcm.SAMPLE_RATE
        val overlapMs = if (idx == 0) 0L else overlapSamples * 1000L / Pcm.SAMPLE_RATE
        AppLog.span(
            TAG, "chunk_cut", c,
            "idx" to idx, "start_ms" to startMs, "end_ms" to endMs, "overlap_ms" to overlapMs, "samples" to pcm.size,
            "audio_sec" to "%.2f".format(pcm.size.toDouble() / Pcm.SAMPLE_RATE),
        ) { span ->
            span.put("rms", "%.4f".format(Pcm.rms(pcm)))
            chunks.trySend(AudioChunk(idx, startMs, endMs, overlapMs, pcm))
        }
    }

    /** True once the capture thread has ended on its own (a file source reaching its end). */
    val finished: Boolean get() = thread?.isAlive == false

    fun stop() {
        running = false
        thread?.join()
        record?.apply {
            stop()
            release()
        }
        record = null
        AppLog.i(TAG, "record_stop", ctx, "kind" to label, "wall_ms" to (SystemClock.elapsedRealtime() - startedAt))
    }

    companion object {
        const val TAG = "Audio"
        private const val FILE_READ_SAMPLES = 320 // 20 ms

        /** A recording session: overlapping chunks per ChunkConfig. */
        fun recording(ctx: LogCtx) = ChunkRecorder(
            ChunkConfig.CHUNK_SAMPLES, ChunkConfig.OVERLAP_SAMPLES, ChunkConfig.MIN_NEW_SAMPLES, ChunkConfig.MIN_RECORDING_SAMPLES, ctx, "recording",
        )

        /** A voice search clip: one chunk, no overlap. */
        fun query(ctx: LogCtx) = ChunkRecorder(
            ChunkConfig.QUERY_MAX_SAMPLES, 0, ChunkConfig.QUERY_MIN_SAMPLES, ChunkConfig.QUERY_MIN_SAMPLES, ctx, "voice_query",
        )
    }
}
