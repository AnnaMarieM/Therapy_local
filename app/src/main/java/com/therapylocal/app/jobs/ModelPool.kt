package com.therapylocal.app.jobs

/**
 * The models the handlers use, each loaded on first use and kept across jobs until [close] (the end of a drain), so a multi-GB model is not
 * reloaded per job. The loaders are given by the app (see [com.therapylocal.app.memory.DeviceModels]) or by a test. [loadAsr] gets the LLM,
 * so Gemma speech recognition can use the LLM's engine instead of loading the same Gemma file a second time.
 */
class ModelPool(
    private val loadLlm: () -> Llm,
    private val loadEmbed: () -> Embed,
    private val loadAsr: (llm: () -> Llm) -> Asr,
    /** The id of the LLM [llm] would load, known without loading it (cache keys). */
    val llmModel: () -> String,
) : AutoCloseable {
    private var llm: Llm? = null
    private var embed: Embed? = null
    private var asr: Asr? = null

    fun llm(): Llm {
        dropStaleLlm()
        return llm ?: loadLlm().also { llm = it }
    }

    fun embed(): Embed = embed ?: loadEmbed().also { embed = it }

    fun asr(): Asr {
        dropStaleLlm()
        return asr ?: loadAsr(::llm).also { asr = it }
    }

    /** The Gemma build was switched in the settings mid-drain: unload the old one, and the speech recogniser that may run on it. */
    private fun dropStaleLlm() {
        val loaded = llm ?: return
        if (loaded.model == llmModel()) return
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
    }
}
