package com.therapylocal.app.data

/** Cleans a raw Gemma transcript. Pure Kotlin so it is unit-testable on the JVM. */
object TranscriptClean {
    private val ECHO = Regex("(?i)(transcribe the speech in this audio verbatim|output only the transcript,? nothing else)\\.?")
    private val SPACES = Regex("\\s{2,}")

    /** Observed on device: when a chunk ends mid-sentence, Gemma can echo its instruction into the transcript. */
    fun stripPromptEcho(text: String): String = text.replace(ECHO, " ").replace(SPACES, " ").trim()
}
