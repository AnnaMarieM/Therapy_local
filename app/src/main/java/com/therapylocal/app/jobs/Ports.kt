package com.therapylocal.app.jobs

/** The memory LLM as the handlers use it. Every [ask] is a fresh conversation. */
interface Llm : AutoCloseable {
    /** Model id, kept with what it produced (cache keys, fact provenance). */
    val model: String

    /** [schema] switches on constrained decoding for this call only. Returns the reply text. */
    fun ask(system: String, user: String, schema: String?, maxOutputTokens: Int, temperature: Double, topK: Int): String
}

/** Text embeddings as stored documents (the embedder adds the document task prefix). */
interface Embed : AutoCloseable {
    val model: String

    fun embed(text: String): FloatArray
}

/** Speech to text for one chunk of 16 kHz mono PCM16. */
interface Asr : AutoCloseable {
    fun transcribe(pcm: ShortArray): String

    override fun close() {}
}
