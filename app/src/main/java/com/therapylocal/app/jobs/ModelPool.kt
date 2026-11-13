package com.therapylocal.app.jobs

import com.therapylocal.app.asr.Diarizer

/** Thrown when the Gemma build was switched in the settings during a job, before the job loaded it: the job goes back to pending. */
class ModelSwitched : Exception()

/**
 * The models the handlers use, each loaded on first use. The loaders are given by the app (see [com.therapylocal.app.memory.DeviceModels]) or by a test.
 *
 * **One model resident at a time.** The LLM, the embedder, the speech recogniser and the speaker diarizer (docs/AD/AD-06.md) are never in memory together: loading one first unloads
 * the others. Between jobs [refresh] also drops what the next job does not use, so a model stays loaded across consecutive jobs that use it.
 *
 * **Text only.** The LLM never gets audio: speech recognition has its own model (Canary, see docs/AD/AD-02.md).
 *
 * **Enough context.** [contextNeeded] is the context all open work needs (every prompt counted up front, see
 * [com.therapylocal.app.jobs.Pipeline.requiredContext]). [refresh] counts it between jobs; the LLM is loaded with it, and reloaded with more
 * when work that was queued meanwhile needs more than the loaded engine has.
 *
 * The Gemma build chosen in the settings is only picked up by [refresh], which [JobRunner] calls between jobs, so one job never mixes two
 * builds or unloads the model it is using. The runner only claims a job once its models are ready, so the new build is ready by then.
 *
 * **Healthy or reloaded.** A loaded model is handed out again only while it is [Llm.healthy] ([Embed.healthy], [Asr.healthy]); otherwise
 * it is closed and loaded again, which goes to the next backend.
 *
 * **Diarization is a whole-recording call.** The diarizer holds the PCM of a session as float in `:batch` while it runs (230 MB for an hour), so
 * it is unloaded as soon as the LLM, the embedder or Canary is asked for, and a transcribe job that needs both gets them one after the other.
 */
class ModelPool(
    /** Loads the text LLM with `context` tokens. */
    private val loadLlm: (context: Int) -> Llm,
    private val loadEmbed: () -> Embed,
    /** Loads the speech recogniser for a client [language]. */
    private val loadAsr: (language: String) -> Asr,
    /** The id of the LLM chosen in the settings, known without loading it. */
    llmModel: () -> String,
    /** Tokens of context all open work needs, counted from its prompts. */
    private val contextNeeded: () -> Int = { 0 },
    /** The context the LLM is loaded with at least. */
    private val baseContext: Int = 0,
    /** Loads the speaker diarizer. */
    private val loadDiarizer: () -> Diarizer = { throw IllegalStateException("no diarizer configured") },
    /**
     * True while the Fast workflow is chosen (docs/AD/AD-22.md): the LLM is loaded with its audio encoder and is the one that hears the turns, so
     * TRANSCRIBE uses the LLM and no speech recogniser, and an LLM that cannot hear is reloaded.
     */
    private val listening: () -> Boolean = { false },
) : AutoCloseable {
    private val chosenLlm = llmModel
    private var llm: Llm? = null
    private var embed: Embed? = null
    private var asr: Asr? = null
    private var asrLanguage: String? = null
    private var diarizer: Diarizer? = null
    private var current: String? = null
    private var needed = baseContext

    /** The id of the LLM [llm] gives in this job (cache keys). */
    fun llmModel(): String = current ?: chosenLlm().also { current = it }

    /** The text LLM, loaded with the context [refresh] counted for the open work. */
    fun llm(): Llm {
        llm?.takeIf { it.healthy && it.contextTokens >= needed && (!listening() || it.hearing) }?.let { return it }
        closeLlm()
        closeEmbed()
        closeAsr()
        closeDiarizer()
        val model = llmModel()
        if (chosenLlm() != model) throw ModelSwitched()
        val loaded = loadLlm(maxOf(needed, baseContext))
        if (loaded.model != model) {
            loaded.close()
            throw ModelSwitched()
        }
        return loaded.also { llm = it }
    }

    fun embed(): Embed {
        embed?.takeIf { it.healthy }?.let { return it }
        closeEmbed()
        closeAsr()
        closeLlm()
        closeDiarizer()
        return loadEmbed().also { embed = it }
    }

    /** The speech recogniser for [language] (a language code). Only one is held: asking for another unloads the current one. */
    fun asr(language: String = "en"): Asr {
        asr?.takeIf { it.healthy && asrLanguage == language }?.let { return it }
        closeAsr()
        closeEmbed()
        closeLlm()
        closeDiarizer()
        return loadAsr(language).also { asr = it; asrLanguage = language }
    }

    /** The speaker diarizer. Loading it unloads the others, as every other model does. */
    fun diarizer(): Diarizer {
        diarizer?.takeIf { it.healthy }?.let { return it }
        closeDiarizer()
        closeAsr()
        closeEmbed()
        closeLlm()
        return loadDiarizer().also { diarizer = it }
    }

    /**
     * Between jobs: if another Gemma build was chosen, unload the old one. With the [nextKind] of
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
        trim(if (nextKind == JobGraph.TRANSCRIBE && listening()) Needs(llm = true, diarize = true) else needs)
    }


    /** Unloads the models [needs] does not list, and an LLM whose context the open work outgrew. */
    private fun trim(needs: Needs) {
        if (!needs.asr) closeAsr()
        if (!needs.diarize) closeDiarizer()
        if (!needs.embed) closeEmbed()
        val loaded = llm ?: return
        if (!(loaded.healthy && needs.llm && loaded.contextTokens >= needed && (!listening() || loaded.hearing))) closeLlm()
    }

    /** A close that throws still forgets the engine: it is never handed out again. */
    private fun closeLlm() {
        val l = llm
        llm = null
        l?.close()
    }

    private fun closeEmbed() {
        val e = embed
        embed = null
        e?.close()
    }

    private fun closeDiarizer() {
        val d = diarizer
        diarizer = null
        d?.close()
    }

    private fun closeAsr() {
        val a = asr
        asr = null
        asrLanguage = null
        a?.close()
    }

    /** Which models a kind of job uses; null for a kind that is not part of the session pipeline (nothing is unloaded then). */
    private class Needs(val asr: Boolean = false, val embed: Boolean = false, val llm: Boolean = false, val diarize: Boolean = false) {
        companion object {
            fun of(kind: String): Needs? = when (kind) {
                JobGraph.TRANSCRIBE -> Needs(asr = true, diarize = true)
                JobGraph.TRANSLATE -> Needs(llm = true)
                JobGraph.LOCALIZE -> Needs(llm = true)
                JobGraph.ROLES -> Needs(llm = true)
                JobGraph.WINDOW -> Needs(embed = true)
                JobGraph.EXTRACT -> Needs(llm = true)
                JobGraph.COMMIT -> Needs(embed = true, llm = true)
                JobGraph.SUMMARY -> Needs(llm = true)
                JobGraph.CLEANUP -> Needs()
                else -> null
            }
        }
    }

    override fun close() {
        closeDiarizer()
        closeAsr()
        closeEmbed()
        closeLlm()
        current = null
    }
}
