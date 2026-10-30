package com.therapylocal.app.jobs

/** Thrown when the Gemma build was switched in the settings during a job, before the job loaded it: the job goes back to pending. */
class ModelSwitched : Exception()

/**
 * The models the handlers use, each loaded on first use and kept across jobs until [close] (the end of a drain), so a multi-GB model is not
 * reloaded per job. The loaders are given by the app (see [com.therapylocal.app.memory.DeviceModels]) or by a test. [loadAsr] gets the LLM,
 * so Gemma speech recognition can use the LLM's engine instead of loading the same Gemma file a second time.
 *
 * The Gemma build chosen in the settings is only picked up by [refresh], which [JobRunner] calls between jobs, so one job never mixes two
 * builds or unloads the model it is using. The runner only claims a job once its models are ready, so the new build is ready by then.
 */
class ModelPool(
    private val loadLlm: () -> Llm,
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

    fun llm(): Llm {
        llm?.let { return it }
        val model = llmModel()
        if (chosenLlm() != model) throw ModelSwitched()
        val loaded = loadLlm()
        if (loaded.model != model) {
            loaded.close()
            throw ModelSwitched()
        }
        return loaded.also { llm = it }
    }

    fun embed(): Embed = embed ?: loadEmbed().also { embed = it }

    fun asr(): Asr = asr ?: loadAsr(::llm).also { asr = it }

    /** Between jobs: if another Gemma build was chosen, unload the old one, and the speech recogniser that may run on it. */
    fun refresh() {
        val next = chosenLlm()
        if (next == current) return
        current = next
        val loaded = llm ?: return
        if (loaded.model == next) return
        asr?.close()
        loaded.close()
        asr = null
        llm = null
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
