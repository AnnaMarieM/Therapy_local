package com.therapylocal.app.jobs

/** Thrown when the Gemma build was switched in the settings during a job, before the job loaded it: the job goes back to pending. */
class ModelSwitched : Exception()

/**
 * The models the handlers use, each loaded on first use. The loaders are given by the app (see [com.therapylocal.app.memory.DeviceModels]) or by a test.
 *
 * **One model resident at a time.** The LLM, the embedder and the speech recogniser are never in memory together: loading one first unloads
 * the others. (Gemma speech recognition runs on the LLM's own engine, so those two count as one.) Between jobs [refresh] also drops what the
 * next job does not use, so a model stays loaded across consecutive jobs that use it.
 *
 * **Text only.** The LLM is loaded without its audio encoder; the audio encoder is only loaded for Gemma speech recognition ([loadAsr]), and
 * dropped as soon as a job needs text only.
 *
 * **Enough context.** [contextNeeded] is the context all open work needs (every prompt counted up front, see
 * [com.therapylocal.app.jobs.Pipeline.requiredContext]). [refresh] counts it between jobs; the LLM is loaded with it, and reloaded with more
 * when work that was queued meanwhile needs more than the loaded engine has.
 *
 * The Gemma build chosen in the settings is only picked up by [refresh], which [JobRunner] calls between jobs, so one job never mixes two
 * builds or unloads the model it is using. The runner only claims a job once its models are ready, so the new build is ready by then.
 */
class ModelPool(
    /** Loads the LLM with `context` tokens; `audio` asks for its audio encoder too (Gemma speech recognition), otherwise text only. */
    private val loadLlm: (audio: Boolean, context: Int) -> Llm,
    private val loadEmbed: () -> Embed,
    /**
     * Loads the speech recogniser for a client [language]. Gets the LLM engine if it has an audio encoder (null otherwise): a recogniser that
     * runs on it (Gemma) uses it, the others have their own model.
     */
    private val loadAsr: (llm: () -> Llm?, language: String) -> Asr,
    /** The id of the LLM chosen in the settings, known without loading it. */
    llmModel: () -> String,
    /** Whether the chosen speech recogniser runs on the LLM's engine (Gemma) or has its own model (Whisper). */
    private val asrOnLlm: () -> Boolean = { false },
    /** Tokens of context all open work needs, counted from its prompts. */
    private val contextNeeded: () -> Int = { 0 },
    /** The context the LLM is loaded with at least, and for speech recognition (no prompt to count there). */
    private val baseContext: Int = 0,
) : AutoCloseable {
    private val chosenLlm = llmModel
    private var llm: Llm? = null
    private var embed: Embed? = null
    private var asr: Asr? = null
    private var asrOnEngine = false
    private var asrLanguage: String? = null
    private var current: String? = null
    private var needed = baseContext

    /** The id of the LLM [llm] gives in this job (cache keys). */
    fun llmModel(): String = current ?: chosenLlm().also { current = it }

    /** The text LLM, loaded with the context [refresh] counted for the open work (an engine loaded for speech recognition is used as it is). */
    fun llm(): Llm {
        llm?.takeIf { it.hasAudio || it.contextTokens >= needed }?.let { return it }
        closeLlm()
        closeEmbed()
        closeWhisper()
        return load(audio = false, context = maxOf(needed, baseContext))
    }

    /** The LLM with its audio encoder, for Gemma speech recognition. A text-only engine is replaced (nothing can be using it then). */
    private fun audioLlm(): Llm {
        llm?.takeIf { it.hasAudio }?.let { return it }
        closeLlm()
        closeEmbed()
        closeWhisper()
        return load(audio = true, context = baseContext)
    }

    private fun load(audio: Boolean, context: Int): Llm {
        val model = llmModel()
        if (chosenLlm() != model) throw ModelSwitched()
        val loaded = loadLlm(audio, context)
        if (loaded.model != model) {
            loaded.close()
            throw ModelSwitched()
        }
        return loaded.also { llm = it }
    }

    fun embed(): Embed {
        embed?.let { return it }
        closeAsr()
        closeLlm()
        return loadEmbed().also { embed = it }
    }

    /** The speech recogniser for [language] (a language code). Only one is held: asking for another unloads the current one. */
    fun asr(language: String = "en"): Asr {
        asr?.let { if (asrLanguage == language) return it }
        closeAsr()
        closeEmbed()
        if (asrOnLlm()) {
            // The engine without an audio encoder cannot hear: it is not kept next to the recogniser that takes its place.
            if (!audioLlm().hasAudio) closeLlm()
        } else {
            closeLlm()
        }
        val recogniser = loadAsr({ llm?.takeIf { it.hasAudio } }, language)
        asrOnEngine = llm?.hasAudio == true
        return recogniser.also { asr = it; asrLanguage = language }
    }

    /**
     * Between jobs: if another Gemma build was chosen, unload the old one, and the speech recogniser that may run on it. With the [nextKind] of
     * the job about to run (a [JobGraph] kind), count the context the open work needs, and unload every model that job does not use.
     */
    fun refresh(nextKind: String? = null) {
        val next = chosenLlm()
        if (next != current) {
            current = next
            if (llm != null && llm?.model != next) closeLlm()
        }
        val needs = nextKind?.let { Needs.of(it) } ?: return
        needed = maxOf(contextNeeded(), baseContext)
        trim(needs)
    }

    /** Unloads the models [needs] does not list, an engine with an audio encoder no job needs, and one whose context the open work outgrew. */
    private fun trim(needs: Needs) {
        if (!needs.asr) closeAsr()
        if (!needs.embed) closeEmbed()
        val loaded = llm ?: return
        // An engine with the audio encoder is only for speech recognition; a text-only one only for the text jobs.
        val keep = if (loaded.hasAudio) needs.asr else needs.llm && loaded.contextTokens >= needed
        if (!keep) closeLlm()
    }

    /** The speech recogniser first when it runs on the LLM's engine. */
    private fun closeLlm() {
        if (asrOnEngine) closeAsr()
        llm?.close()
        llm = null
    }

    private fun closeEmbed() {
        embed?.close()
        embed = null
    }

    private fun closeAsr() {
        asr?.close()
        asr = null
        asrOnEngine = false
        asrLanguage = null
    }

    /** A recogniser with its own model (Whisper) only; one that runs on the LLM's engine goes with it. */
    private fun closeWhisper() {
        if (!asrOnEngine) closeAsr()
    }

    /** Which models a kind of job uses; null for a kind that is not part of the session pipeline (nothing is unloaded then). */
    private class Needs(val asr: Boolean = false, val embed: Boolean = false, val llm: Boolean = false) {
        companion object {
            fun of(kind: String): Needs? = when (kind) {
                JobGraph.TRANSCRIBE -> Needs(asr = true)
                JobGraph.WINDOW -> Needs(embed = true)
                JobGraph.EXTRACT -> Needs(llm = true)
                JobGraph.COMMIT -> Needs(embed = true, llm = true)
                JobGraph.CLEANUP -> Needs()
                else -> null
            }
        }
    }

    override fun close() {
        closeAsr()
        closeEmbed()
        closeLlm()
        current = null
    }
}
