package com.therapylocal.app.jobs

/** The memory LLM as the handlers use it. Every [ask] is a fresh conversation. */
interface Llm : AutoCloseable {
    /** Model id, kept with what it produced (cache keys, fact provenance). */
    val model: String

    /** Tokens of context the engine was loaded with: prompt and reply of one call must fit in it. */
    val contextTokens: Int get() = Int.MAX_VALUE

    /** False once the engine keeps failing on real prompts: [ModelPool] then loads the model again, on the next backend. */
    val healthy: Boolean get() = true

    /** [schema] switches on constrained decoding for this call only. Returns the reply text. */
    fun ask(system: String, user: String, schema: String?, maxOutputTokens: Int, temperature: Double, topK: Int): String
}

/** Text embeddings as stored documents (the embedder adds the document task prefix). */
interface Embed : AutoCloseable {
    val model: String

    /** False once the embedder can no longer serve: [ModelPool] then loads it again. */
    val healthy: Boolean get() = true

    fun embed(text: String): FloatArray
}

/** Speech to text for one chunk of 16 kHz mono PCM16. */
interface Asr : AutoCloseable {
    /** False once the recogniser can no longer serve: [ModelPool] then loads it again. */
    val healthy: Boolean get() = true

    fun transcribe(pcm: ShortArray): String

    override fun close() {}
}
