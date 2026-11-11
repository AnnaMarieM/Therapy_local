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

    /** Default clinical format: SOAP. Labels are model-facing and stay English in every prompt language. */
    val SECTIONS = listOf(
        Section("Subjective", "what the client reported: feelings, events, complaints, in their own terms"),
        Section("Objective", "what was observed or stated as fact in the session: behaviour, affect, what the therapist did"),
        Section("Assessment", "the main themes and how the client seems to be doing, only as far as the conversation itself supports"),
        Section("Plan", "what was agreed or suggested for after the session, homework, the next topic"),
    )

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
     * Long transcripts (see MapReduceSummary): the transcript is cut into windows of [WINDOW_TOKENS] (estimated tokens, overlapping by
     * [WINDOW_OVERLAP_TOKENS]), each is condensed to notes, and the notes are merged until they fit [REDUCE_INPUT_TOKENS]. A transcript that
     * fits one window is summarised in one call. Estimated at English word length: Czech needs about 1.5 times the tokens, which the context counting (PromptBudget) covers by loading a larger context. Our own choice until the benchmark is done.
     */
    const val WINDOW_TOKENS = 2000
    const val WINDOW_OVERLAP_TOKENS = 100
    const val REDUCE_INPUT_TOKENS = 2500

    /** Safety stop for the merge levels; each level cuts the notes by a large factor, so this is never reached in practice. */
    const val MAX_REDUCE_LEVELS = 4

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
        formatInstruction(), MAX_WORDS_PER_SECTION, MAX_SENTENCES_PER_SECTION, NOTES_MAX_WORDS, LABEL_THERAPIST, LABEL_PATIENT, LABEL_SPEAKER, PROMPT_VERSION,
    ).joinToString("|")
}
