package com.therapylocal.app.jobs

/** Thrown when the Gemma build was switched in the settings during a job, before the job loaded it: the job goes back to pending. */
class ModelSwitched : Exception()

/**
 * The models the handlers use, each loaded on first use and kept across jobs, so a multi-GB model is not reloaded per job. The loaders are
 * given by the app (see [com.therapylocal.app.memory.DeviceModels]) or by a test.
 *
 * Memory: only what the next job uses stays loaded ([refresh] with the job's kind drops the rest), so the LLM, the embedder and the speech
 * recogniser are not resident together. The LLM is loaded as text only; its audio encoder is only loaded for speech recognition on Gemma
 * ([loadAsr] gets [audioLlm]), and is dropped again as soon as a job needs text only.
 *
 * The Gemma build chosen in the settings is only picked up by [refresh], which [JobRunner] calls between jobs, so one job never mixes two
 * builds or unloads the model it is using. The runner only claims a job once its models are ready, so the new build is ready by then.
 */
class ModelPool(
    /** Loads the LLM; `audio` asks for its audio encoder too (speech recognition on Gemma), otherwise text only. */
    private val loadLlm: (audio: Boolean) -> Llm,
    private val loadEmbed: () -> Embed,
    private val loadAsr: (llm: () -> Llm) -> Asr,
    /** The id of the LLM chosen in the settings, known without loading it. */
    llmModel: () -> String,
) : AutoCloseable {
    private val chosenLlm = llmModel
    private var llm: Llm? = null
    private var embed: Embed? = null
    private var asr: Asr? = null
    private var current: String? = null

    /** The id of the LLM [llm] gives in this job (cache keys). */
    fun llmModel(): String = current ?: chosenLlm().also { current = it }

    /** The text LLM. An engine already loaded for speech recognition is reused as it is. */
    fun llm(): Llm {
        llm?.let { return it }
        return load(audio = false)
    }

    /** The LLM with its audio encoder, for Gemma speech recognition. A text-only engine is replaced (nothing can be using it: the speech recogniser is not loaded yet). */
    private fun audioLlm(): Llm {
        llm?.takeIf { it.hasAudio }?.let { return it }
        llm?.close()
        llm = null
        return load(audio = true)
    }

    private fun load(audio: Boolean): Llm {
        val model = llmModel()
        if (chosenLlm() != model) throw ModelSwitched()
        val loaded = loadLlm(audio)
        if (loaded.model != model) {
            loaded.close()
            throw ModelSwitched()
        }
        return loaded.also { llm = it }
    }

    fun embed(): Embed = embed ?: loadEmbed().also { embed = it }

    fun asr(): Asr = asr ?: loadAsr(::audioLlm).also { asr = it }

    /**
     * Between jobs: if another Gemma build was chosen, unload the old one, and the speech recogniser that may run on it. With the [nextKind] of
     * the job about to run (a [JobGraph] kind), also unload every model that job does not use.
     */
    fun refresh(nextKind: String? = null) {
        val next = chosenLlm()
        if (next != current) {
            current = next
            val loaded = llm
            if (loaded != null && loaded.model != next) {
                asr?.close()
                loaded.close()
                asr = null
                llm = null
            }
        }
        nextKind?.let { Needs.of(it) }?.let(::trim)
    }

    /** Unloads the models [needs] does not list, and a LLM that carries an audio encoder no job needs. The speech recogniser first: it may run on the LLM's engine. */
    private fun trim(needs: Needs) {
        if (!needs.asr) {
            asr?.close()
            asr = null
        }
        if (!needs.embed) {
            embed?.close()
            embed = null
        }
        val loaded = llm ?: return
        // An engine with the audio encoder is only for speech recognition; a text-only one only for the text jobs.
        val keep = if (loaded.hasAudio) needs.asr else needs.llm
        if (!keep) {
            asr?.close()
            asr = null
            loaded.close()
            llm = null
        }
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

    /** The speech recogniser first: it may run on the LLM's engine. */
    override fun close() {
        asr?.close()
        embed?.close()
        llm?.close()
        asr = null
        embed = null
        llm = null
        current = null
    }
}
