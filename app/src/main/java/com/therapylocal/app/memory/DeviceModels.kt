package com.therapylocal.app.memory

import android.content.Context
import com.therapylocal.app.asr.Diarizer
import com.therapylocal.app.asr.SpeakerDiarizer
import com.therapylocal.app.asr.SpeechRecogniser
import com.therapylocal.app.config.EmbeddingConfig
import com.therapylocal.app.config.Workflow
import com.therapylocal.app.config.WorkflowSetting
import com.therapylocal.app.models.ModelRegistry
import com.therapylocal.app.config.MemoryConfig
import com.therapylocal.app.embed.Embedder
import com.therapylocal.app.engine.LabWiring
import com.therapylocal.app.jobs.Asr
import com.therapylocal.app.jobs.Embed
import com.therapylocal.app.jobs.ModelPool
import com.therapylocal.app.log.LogCtx
import com.therapylocal.app.models.SpeechLanguage
import com.therapylocal.app.models.ModelSetup
import kotlinx.coroutines.runBlocking

/** The phone's models behind the pipeline's ports, for the runner's [ModelPool]. */
object DeviceModels {
    /** [contextNeeded]: the context the open work needs, counted from its prompts before the LLM is loaded (see [com.therapylocal.app.jobs.Pipeline.requiredContext]). */
    fun pool(context: Context, contextNeeded: () -> Int = { MemoryConfig.LLM_MAX_TOKENS }): ModelPool = ModelPool(
        // Fast: the engine is loaded with its audio encoder (CPU) and serves every stage, so Gemma is loaded once per session (AD-22).
        loadLlm = { ctx -> MemoryLlm(context, ctx, LabWiring.spec(ModelSetup.gemma(context)), withAudio = WorkflowSetting.get(context) == Workflow.Fast).apply { load() } },
        loadEmbed = { embed(context) },
        loadAsr = { language -> asr(context, language) },
        llmModel = { LabWiring.spec(ModelSetup.gemma(context)).id },
        contextNeeded = contextNeeded,
        baseContext = MemoryConfig.LLM_MAX_TOKENS,
        loadDiarizer = { diarizer(context) },
        listening = { WorkflowSetting.get(context) == Workflow.Fast },
    )

    /** Speaker diarization (docs/AD/AD-06.md), loaded in the `:batch` host. */
    private fun diarizer(context: Context): Diarizer {
        val d = SpeakerDiarizer(context)
        try {
            runBlocking { d.load() }
        } catch (t: Throwable) {
            d.close()
            throw t
        }
        return d
    }

    /** Canary, the only speech recogniser (docs/AD/AD-02.md), for the client's [language]. */
    private fun asr(context: Context, language: String): Asr {
        // Quality: Whisper large-v3 writes English from the audio of a client who speaks another language (the translate task), and transcribes English as it is.
        val quality = WorkflowSetting.get(context) == Workflow.Quality
        val engineImpl = if (quality) {
            SpeechRecogniser(context, SpeechLanguage.of(language), modelSpec = ModelRegistry.WHISPER_LARGE_V3_Q8, translate = !language.equals("en", true), beam = WorkflowSetting.beam(context))
        } else SpeechRecogniser(context, SpeechLanguage.of(language))
        try {
            runBlocking { engineImpl.load() }
        } catch (t: Throwable) {
            engineImpl.close()
            throw t
        }
        return object : Asr {
            override val healthy: Boolean get() = engineImpl.healthy
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
            override val healthy: Boolean get() = embedder.healthy
            override fun embed(text: String): FloatArray = runBlocking { embedder.embedText(text, false, LogCtx.NONE) }
            override fun close() = embedder.close()
        }
    }
}
