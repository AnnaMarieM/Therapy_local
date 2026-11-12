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

    /**
     * One summary format. [sections] are the headings the model writes and the parser looks for; empty for a plain narrative, whose [freeform]
     * text is what the prompt shows instead. [systemPrompt] is the prompt file of the FINAL call (its Czech twin is in the cs/ folder);
     * [maxWords] and [maxSentences] are the limits that prompt states for each section.
     */
    data class Format(
        val id: String,
        val sections: List<Section>,
        val systemPrompt: String,
        val maxWords: Int = 70,
        val maxSentences: Int = 4,
        val freeform: String? = null,
    ) {
        /** The format as the prompt shows it: one "Label: what goes here" line per section, or the free-form description. */
        fun instruction(): String = freeform ?: sections.joinToString("\n") { "${it.label}: <${it.asks}>" }
    }

    /**
     * Every registered format by id; the order is the order of the stops of the setting. Only the FINAL call of the summary uses the format
     * (its prompt file and its limits); the chunk and group levels of the tree are format-independent. The display names and the examples
     * shown in Settings are string resources named `summary_format_<id>` and `summary_example_<id>`.
     */
    val FORMATS: Map<String, Format> = listOf(
        Format(
            "soap",
            listOf(
                Section("Subjective", "what the client reported: feelings, events, complaints, in their own terms"),
                Section("Objective", "what was observed or stated as fact in the session: behaviour, affect, what the therapist did"),
                Section("Assessment", "the main themes and how the client seems to be doing, only as far as the conversation itself supports"),
                Section("Plan", "what was agreed or suggested for after the session, homework, the next topic"),
            ),
            PROMPT_FINAL_SYSTEM,
        ),
        Format(
            "dap",
            listOf(
                Section("Data", "what the client reported and what was observed or done in the session: feelings, events, behaviour, what the therapist did"),
                Section("Assessment", "the main themes and how the client seems to be doing, only as far as the conversation itself supports"),
                Section("Plan", "what was agreed or suggested for after the session, homework, the next topic"),
            ),
            "summary.dap.system.txt", maxWords = 90,
        ),
        Format(
            "birp",
            listOf(
                Section("Behavior", "what the client reported and how the client presented: feelings, events, complaints, behaviour, affect"),
                Section("Intervention", "what the therapist did: techniques, exercises, questions, in the order they happened"),
                Section("Response", "how the client reacted to the interventions, as far as the conversation shows it"),
                Section("Plan", "what was agreed or suggested for after the session, homework, the next topic"),
            ),
            "summary.birp.system.txt",
        ),
        Format(
            "narrative", emptyList(), "summary.narrative.system.txt", maxWords = 200, maxSentences = 10,
            freeform = "one continuous note in short paragraphs with no headings: first what the client reported, then what was done in the session and how the client responded, last what was agreed for after it",
        ),
    ).associateBy { it.id }

    /** The format used until the therapist chooses another. */
    const val DEFAULT_FORMAT_ID = "soap"

    /** The format of [id]; the default for null or an id that is not registered (a setting from a later version). */
    fun format(id: String?): Format = FORMATS[id] ?: FORMATS.getValue(DEFAULT_FORMAT_ID)

    /** Headings of every format, for splitting a stored summary of any of them. */
    val ALL_LABELS: List<String> = FORMATS.values.flatMap { f -> f.sections.map { it.label } }.distinct()

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

    /** Everything above that reaches a prompt, for the format [formatId], as one string for the prompt version: the format id leads it. */
    fun fingerprint(formatId: String = DEFAULT_FORMAT_ID): String {
        val f = format(formatId)
        return listOf(f.id, f.instruction(), f.maxWords, f.maxSentences, NOTES_MAX_WORDS, LABEL_THERAPIST, LABEL_PATIENT, LABEL_SPEAKER, PROMPT_VERSION).joinToString("|")
    }
}
