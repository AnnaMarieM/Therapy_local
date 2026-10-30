package com.therapylocal.app.memory

import android.content.Context
import com.therapylocal.app.asr.SherpaWhisperAsr
import com.therapylocal.app.config.EmbeddingConfig
import com.therapylocal.app.config.MemoryConfig
import com.therapylocal.app.embed.Embedder
import com.therapylocal.app.jobs.Asr
import com.therapylocal.app.jobs.Embed
import com.therapylocal.app.jobs.Llm
import com.therapylocal.app.jobs.ModelPool
import com.therapylocal.app.log.AppLog
import com.therapylocal.app.log.LogCtx
import com.therapylocal.app.models.ModelSetup
import kotlinx.coroutines.runBlocking

/** The phone's models behind the pipeline's ports, for the runner's [ModelPool]. */
object DeviceModels {
    fun pool(context: Context): ModelPool = ModelPool(
        loadLlm = { MemoryLlm(context, MemoryConfig.LLM_MAX_TOKENS, ModelSetup.gemma(context)).apply { load(MemoryConfig.LLM_BACKENDS) } },
        loadEmbed = { embed(context) },
        loadAsr = { llm -> asr(context, llm) },
        llmModel = { ModelSetup.gemma(context).id },
    )

    /** The engine chosen in the settings. Gemma runs on the LLM's engine; a Gemma build without audio input falls back to Whisper. */
    private fun asr(context: Context, llm: () -> Llm): Asr {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        if (prefs.getString("engine", "whisper") == "gemma") {
            (llm() as? MemoryLlm)?.speech()?.let { return it }
            AppLog.w(TAG, "gemma_asr_unavailable", LogCtx.NONE, "fallback" to "whisper")
        }
        val whisper = SherpaWhisperAsr(context, prefs.getString("whisper_size", "base") ?: "base", prefs.getString("language", "auto") ?: "auto")
        try {
            runBlocking { whisper.load() }
        } catch (t: Throwable) {
            whisper.close()
            throw t
        }
        return object : Asr {
            override fun transcribe(pcm: ShortArray): String = runBlocking { whisper.transcribe(pcm, LogCtx.NONE).text }
            override fun close() = whisper.close()
        }
    }

    /** On CPU: the LLM holds the GPU, and both on the GPU at once can run out of memory on phones. */
    private fun embed(context: Context): Embed {
        val embedder = Embedder(context, forcedBackend = "CPU")
        runBlocking { embedder.load() }
        return object : Embed {
            override val model = EmbeddingConfig.MODEL_ID
            override fun embed(text: String): FloatArray = runBlocking { embedder.embedText(text, false, LogCtx.NONE) }
            override fun close() = embedder.close()
        }
    }

    private const val TAG = "Memory"
}
