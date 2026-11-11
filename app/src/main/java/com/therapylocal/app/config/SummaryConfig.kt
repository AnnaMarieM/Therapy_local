package com.therapylocal.app.config

/**
 * The ONE place that defines the last-session summary (docs/AD/AD-12.md): its format, its length limits and how the long-transcript method
 * is tuned. The format and length are still an open product decision, so everything that could change is here and nowhere else. The prompt
 * files (`demo/prompts/summary.*.txt`) get the format and the limits through placeholders, never as text of their own.
 *
 * Any change here or in a prompt file changes the prompt version of a summary ([com.therapylocal.app.summary.SummaryPrompts.version]), so
 * the summaries made before it show as stale.
 */
object SummaryConfig {
    /** One section of the summary: [label] is the heading the model writes (and the parser looks for), [asks] what goes under it. */
    data class Section(val label: String, val asks: String)

    /** Every registered format by id. Only the FINAL call of the summary uses it; the chunk and group levels of the tree are format-independent. */
    val FORMATS: Map<String, List<Section>> = mapOf(
        "soap" to listOf(
            Section("Subjective", "what the client reported: feelings, events, complaints, in their own terms"),
            Section("Objective", "what was observed or stated as fact in the session: behaviour, affect, what the therapist did"),
            Section("Assessment", "the main themes and how the client seems to be doing, only as far as the conversation itself supports"),
            Section("Plan", "what was agreed or suggested for after the session, homework, the next topic"),
        ),
    )

    /** The format in use, a single id handled here only. It is part of [fingerprint], so changing it makes stored summaries stale and a rebuild writes the new one. */
    const val FORMAT_ID = "soap"

    /** Sections of the format in use. Labels are model-facing and stay English in every prompt language. */
    val SECTIONS: List<Section> = FORMATS.getValue(FORMAT_ID)

    /** Length limit the prompt states for each section, in words. A section with nothing to say is written as "None." */
    const val MAX_WORDS_PER_SECTION = 70

    /** Short paragraphs: at most this many sentences per section (stated in the prompt). */
    const val MAX_SENTENCES_PER_SECTION = 4

    /** Reply cap of the final summary, sized for the sections above with room for a Czech reply (about 2 tokens a word). */
    const val FINAL_MAX_OUTPUT_TOKENS = 700

    /** Intermediate notes of the long-transcript method. */
    const val NOTES_MAX_WORDS = 200
    const val NOTES_MAX_OUTPUT_TOKENS = 450

    const val TEMPERATURE = 0.2
    const val TOP_K = 20

    /**
     * Long transcripts (see TreeSummary, AD-12): every call is budgeted against the context the app loads for its Gemma jobs, not a number of
     * our own: [MemoryConfig.LLM_MAX_TOKENS] (the pool's base context). A chunk or a group of summaries fills one call with its prompt and its
     * reply cap; a transcript that fits one call is one call. Tokens are counted with [com.therapylocal.app.facts.PromptBudget].
     */
    const val CONTEXT_TOKENS = MemoryConfig.LLM_MAX_TOKENS

    /** Safety stop for the tree: each level cuts the text by a large factor, so this is never reached in practice. */
    const val MAX_LEVELS = 8

    /** A call that fails or gives no usable reply is tried this many times before the job fails. */
    const val ATTEMPTS = 2

    /** A reply shorter than this is no summary. */
    const val MIN_REPLY_CHARS = 40

    /** Role names written in the transcript the model reads. Model-facing, not UI text. */
    const val LABEL_THERAPIST = "Therapist"
    const val LABEL_PATIENT = "Patient"
    const val LABEL_SPEAKER = "Speaker"

    /** Bump when the meaning of the prompts or of the method changes in a way the text hash would not show. */
    const val PROMPT_VERSION = 1

    /** Names of the prompt files (English; Czech ones have the same names in the cs/ folder). */
    const val PROMPT_FINAL_SYSTEM = "summary.system.txt"
    const val PROMPT_FINAL_USER = "summary.user.txt"
    const val PROMPT_NOTES_SYSTEM = "summary.notes.system.txt"
    const val PROMPT_NOTES_USER = "summary.notes.user.txt"

    /** The format as the prompt shows it: one "Label: what goes here" line per section. */
    fun formatInstruction(): String = SECTIONS.joinToString("\n") { "${it.label}: <${it.asks}>" }

    /** Everything above that reaches a prompt, as one string for the prompt version. */
    fun fingerprint(): String = listOf(
        FORMAT_ID, formatInstruction(), MAX_WORDS_PER_SECTION, MAX_SENTENCES_PER_SECTION, NOTES_MAX_WORDS, LABEL_THERAPIST, LABEL_PATIENT, LABEL_SPEAKER, PROMPT_VERSION,
    ).joinToString("|")
}
