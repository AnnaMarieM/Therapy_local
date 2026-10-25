package com.therapylocal.app.data

import com.therapylocal.app.asr.AsrEngine
import com.therapylocal.app.audio.AudioChunk
import com.therapylocal.app.config.EmbeddingConfig
import com.therapylocal.app.embed.Embedder
import com.therapylocal.app.log.AppLog
import com.therapylocal.app.log.LogCtx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The per-chunk pipeline shared by live recording and deferred transcription: embed the AUDIO, transcribe it, embed the transcript too, store. */
class ChunkProcessor(private val store: Store, private val embedder: Embedder, private val asr: AsrEngine) {
    /** [onStage] gets "embedding" before the audio is embedded and "transcribing" before the speech engine runs. Throws if any step fails. */
    suspend fun process(recordingId: Long, chunk: AudioChunk, onStage: (String) -> Unit = {}): ChunkRow {
        val c = LogCtx(recordingId).chunk(chunk.idx)
        onStage("embedding")
        return AppLog.span(TAG, "chunk_pipeline", c, "engine" to asr.engineName, "model" to asr.modelName, "start_ms" to chunk.startMs, "end_ms" to chunk.endMs, "overlap_ms" to chunk.overlapMs) { span ->
            val audioVec = embedder.embedAudio(chunk.pcm, c)
            onStage("transcribing")
            val result = asr.transcribe(chunk.pcm, c)
            span.put("text_chars", result.text.length)
            val textVec = if (result.text.isBlank()) {
                AppLog.w(TAG, "chunk_empty_transcript", c, "audio_sec" to "%.2f".format(result.audioSec))
                null
            } else {
                embedder.embedText(result.text, false, c)
            }
            val row = ChunkRow(
                0, recordingId, chunk.idx, chunk.startMs, chunk.endMs, chunk.overlapMs, result.text,
                asr.engineName, asr.modelName, result.wallMs, EmbeddingConfig.MODEL_ID, audioVec.size, "text ${embedder.backend} / audio CPU",
            )
            withContext(Dispatchers.IO) { store.insertChunk(row, audioVec, textVec) }
            row
        }
    }

    companion object {
        const val TAG = "App"

        /** The whole recording as one text, with the words repeated in each overlap removed. */
        fun transcript(chunks: List<ChunkRow>): String = TranscriptMerge.merge(chunks.map { TranscriptClean.stripPromptEcho(it.text) }).text
    }
}
