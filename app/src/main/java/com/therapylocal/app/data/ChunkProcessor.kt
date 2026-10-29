package com.therapylocal.app.data

import com.therapylocal.app.asr.AsrEngine
import com.therapylocal.app.audio.AudioChunk
import com.therapylocal.app.log.AppLog
import com.therapylocal.app.log.LogCtx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The per-chunk pipeline shared by live recording and deferred transcription: transcribe the chunk and store its text. */
class ChunkProcessor(private val store: MindStore, private val asr: AsrEngine) {
    /** Throws if transcription or storing fails. */
    suspend fun process(recordingId: Long, chunk: AudioChunk): ChunkRow {
        val c = LogCtx(recordingId).chunk(chunk.idx)
        return AppLog.span(TAG, "chunk_pipeline", c, "engine" to asr.engineName, "model" to asr.modelName, "start_ms" to chunk.startMs, "end_ms" to chunk.endMs, "overlap_ms" to chunk.overlapMs) { span ->
            val result = asr.transcribe(chunk.pcm, c)
            span.put("text_chars", result.text.length)
            if (result.text.isBlank()) AppLog.w(TAG, "chunk_empty_transcript", c, "audio_sec" to "%.2f".format(result.audioSec))
            val row = ChunkRow(recordingId, chunk.idx, chunk.startMs, chunk.endMs, chunk.overlapMs, result.text)
            withContext(Dispatchers.IO) { store.putChunk(row) }
            row
        }
    }

    companion object {
        const val TAG = "App"

        /** The whole recording as one text, with the words repeated in each overlap removed. */
        fun transcript(chunks: List<ChunkRow>): String = TranscriptMerge.merge(chunks.map { TranscriptClean.stripPromptEcho(it.text.orEmpty()) }).text
    }
}
