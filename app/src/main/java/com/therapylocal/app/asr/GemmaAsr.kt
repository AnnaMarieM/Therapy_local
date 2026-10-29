package com.therapylocal.app.asr

import android.os.SystemClock
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Engine
import com.therapylocal.app.data.TranscriptClean
import com.therapylocal.app.jobs.Asr
import com.therapylocal.app.log.AppLog
import com.therapylocal.app.log.LogCtx
import kotlinx.coroutines.runBlocking

/**
 * Gemma 4 audio input on an engine the memory LLM has loaded ([com.therapylocal.app.memory.MemoryLlm.speech]), so the same Gemma file is
 * never in memory twice. The engine belongs to the LLM: [close] leaves it open.
 */
class GemmaAsr(private val engine: Engine, private val modelName: String, private val backend: String) : Asr {
    override fun transcribe(pcm: ShortArray): String {
        val ctx = LogCtx.NONE
        val audioSec = Pcm.seconds(pcm)
        val wav = Pcm.toWav(pcm)
        val start = SystemClock.elapsedRealtime()
        return AppLog.span(TAG, "asr_transcribe", ctx, "engine" to "gemma", "model" to modelName, "backend" to backend, "audio_sec" to "%.2f".format(audioSec)) { span ->
            val sb = StringBuilder()
            engine.createConversation().use { conversation ->
                runBlocking { conversation.sendMessageAsync(Contents.of(Content.AudioBytes(wav), Content.Text(PROMPT))).collect { sb.append(it.toString()) } }
            }
            val raw = sb.toString().trim()
            // Observed on device: when a chunk ends mid-sentence, Gemma can echo the instruction into the transcript.
            val out = TranscriptClean.stripPromptEcho(raw)
            if (out.length != raw.length) AppLog.w(TAG, "prompt_echo_removed", ctx, "chars_removed" to (raw.length - out.length))
            val wall = SystemClock.elapsedRealtime() - start
            span.put("chars", out.length)
            span.put("rtf", "%.2f".format(wall / 1000.0 / audioSec.coerceAtLeast(0.01)))
            out
        }
    }

    companion object {
        const val TAG = "ASR"
        private const val PROMPT = "Transcribe the speech in this audio verbatim. Output only the transcript, nothing else."
    }
}
