package com.therapylocal.app.memory

import android.content.Context
import com.therapylocal.app.asr.AsrEngine
import com.therapylocal.app.asr.SherpaCanaryAsr
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
import com.therapylocal.app.models.SpeechLanguage
import com.therapylocal.app.models.ModelRegistry
import com.therapylocal.app.models.ModelSetup
import kotlinx.coroutines.runBlocking

/** The phone's models behind the pipeline's ports, for the runner's [ModelPool]. */
object DeviceModels {
    /** [contextNeeded]: the context the open work needs, counted from its prompts before the LLM is loaded (see [com.therapylocal.app.jobs.Pipeline.requiredContext]). */
    fun pool(context: Context, contextNeeded: () -> Int = { MemoryConfig.LLM_MAX_TOKENS }): ModelPool = ModelPool(
        loadLlm = { audio, ctx -> MemoryLlm(context, ctx, ModelSetup.gemma(context)).apply { load(withAudio = audio) } },
        loadEmbed = { embed(context) },
        loadAsr = { llm, language -> asr(context, llm, language) },
        llmModel = { ModelSetup.gemma(context).id },
        asrOnLlm = { context.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("engine", ModelRegistry.DEFAULT_ENGINE) == "gemma" },
        contextNeeded = contextNeeded,
        baseContext = MemoryConfig.LLM_MAX_TOKENS,
        onAsrLlmFailed = { t -> AppLog.w(TAG, "gemma_asr_load_failed", LogCtx.NONE, "error" to t.javaClass.simpleName, "reason" to t.message, "fallback" to "canary") },
    )

    /**
     * The engine chosen in the settings: Canary (the default), Whisper or Gemma, for the client's [language]. Gemma runs on the LLM's engine;
     * a Gemma build without audio input, or one that could not be loaded (see [ModelPool.asr]), falls back to Canary.
     */
    private fun asr(context: Context, llm: () -> Llm?, language: String): Asr {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val speech = SpeechLanguage.of(language)
        var engine = prefs.getString("engine", ModelRegistry.DEFAULT_ENGINE) ?: ModelRegistry.DEFAULT_ENGINE
        if (engine == "gemma") {
            (llm() as? MemoryLlm)?.speech()?.let { return it }
            AppLog.w(TAG, "gemma_asr_unavailable", LogCtx.NONE, "fallback" to "canary")
            engine = "canary"
        }
        val engineImpl: AsrEngine = when (engine) {
            "whisper" -> SherpaWhisperAsr(context, prefs.getString("whisper_size", ModelRegistry.DEFAULT_WHISPER_SIZE) ?: ModelRegistry.DEFAULT_WHISPER_SIZE, speech)
            else -> SherpaCanaryAsr(context, speech)
        }
        try {
            runBlocking { engineImpl.load() }
        } catch (t: Throwable) {
            engineImpl.close()
            throw t
        }
        return object : Asr {
            override fun transcribe(pcm: ShortArray): String = runBlocking { engineImpl.transcribe(pcm, LogCtx.NONE).text }
            override fun close() = engineImpl.close()
        }
    }

    /**
     * Its own engine, on the best backend the gates allow: [ModelPool] unloads the LLM before it loads this, so the two never share the GPU.
     * Text only: the audio encoder stays unloaded.
     */
    private fun embed(context: Context): Embed {
        val embedder = Embedder(context, textOnly = true)
        runBlocking { embedder.load() }
        return object : Embed {
            override val model = EmbeddingConfig.MODEL_ID
            override fun embed(text: String): FloatArray = runBlocking { embedder.embedText(text, false, LogCtx.NONE) }
            override fun close() = embedder.close()
        }
    }

    private const val TAG = "Memory"
}
