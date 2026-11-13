package com.therapylocal.app.summary

import com.therapylocal.app.config.MemoryConfig
import com.therapylocal.app.config.SummaryConfig
import com.therapylocal.app.data.SegmentRow
import com.therapylocal.app.facts.PromptBudget
import com.therapylocal.app.facts.Prompts

/** One call of the LLM: a fresh conversation (the pool's `Llm.ask` with the sampler settings of [SummaryConfig] filled in by the caller). */
fun interface SummaryAsk {
    fun ask(system: String, user: String, maxOutputTokens: Int): String
}

/** The four prompt texts of a summary, with their placeholders already filled except {{TEXT}}, {{PART}} and {{PARTS}}. */
class SummaryPrompts(val finalSystem: String, val finalUser: String, val notesSystem: String, val notesUser: String, val formatId: String = SummaryConfig.DEFAULT_FORMAT_ID, val pivotTag: String = "") {
    /**
     * Changes whenever a prompt text, the format or a limit changes ([SummaryConfig.fingerprint], which leads with the format id); stored with every summary.
     * [pivotTag] (AD-17) is part of it when given, so a switch of the processing language marks the summary stale.
     */
    fun version(): String = if (pivotTag.isEmpty()) Prompts.version(finalSystem, finalUser, notesSystem, notesUser, SummaryConfig.fingerprint(formatId))
    else Prompts.version(finalSystem, finalUser, notesSystem, notesUser, SummaryConfig.fingerprint(formatId), pivotTag)

    companion object {
        /** Reads the four files with [prompt] (the final system prompt is the one of the format [formatId]) and fills the format and limits into the system prompts. */
        fun load(prompt: (String) -> String, language: String, formatId: String = SummaryConfig.DEFAULT_FORMAT_ID, pivotTag: String = ""): SummaryPrompts {
            fun named(n: String) = prompt(Prompts.named(n, language))
            val format = SummaryConfig.format(formatId)
            val vars = mapOf(
                "FORMAT" to format.instruction(),
                "MAX_WORDS" to format.maxWords.toString(),
                "MAX_SENTENCES" to format.maxSentences.toString(),
                "NOTES_WORDS" to SummaryConfig.NOTES_MAX_WORDS.toString(),
            )
            return SummaryPrompts(
                Prompts.fill(named(format.systemPrompt), vars), named(SummaryConfig.PROMPT_FINAL_USER),
                Prompts.fill(named(SummaryConfig.PROMPT_NOTES_SYSTEM), vars), named(SummaryConfig.PROMPT_NOTES_USER),
                format.id, pivotTag,
            )
        }
    }
}

/** What a method produced: the summary [text], how many model calls it took, how many oversize pieces had to be force-split or cut, and how many levels the tree had (logged, never the text). */
class SummaryOutput(val text: String, val calls: Int, val forcedSplits: Int = 0, val levels: Int = 0)

/**
 * How a transcript becomes a summary. The benchmark for long transcripts is not finished, so the method is a seam: [TreeSummary] is the
 * current one (AD-12), and another replaces it by implementing this and being passed to [SessionSummaries].
 * [id] is stored with the summary and changes whenever the method's tuning does. Pure apart from [SummaryAsk].
 */
interface SummaryMethod {
    val id: String

    /**
     * [segments] carry their role in the text ("Therapist: ..."). Null when there is nothing to summarise (no words). [ask] must retry and
     * throw as the caller wants; [step] is told how far the method is (done, total) and may throw to stop it.
     */
    fun summarize(segments: List<SegmentRow>, prompts: SummaryPrompts, ask: SummaryAsk, step: (done: Int, total: Int) -> Unit = { _, _ -> }): SummaryOutput?
}

/**
 * Tree summarisation (a pyramid, AD-12). Everything is measured in tokens with [PromptBudget] against ONE context, [contextTokens]: the
 * context the app loads for its Gemma jobs ([MemoryConfig.LLM_MAX_TOKENS]). A call is allowed what fits in it with its prompt, the chat
 * template, the safety margin and its reply cap ([PromptBudget.required]).
 *
 * - If the whole transcript fits one call, that call writes the summary.
 * - Level 0: the transcript, in order, is cut into chunks that each fill one notes call; every chunk is summarised on its own.
 * - Level n+1: consecutive summaries are grouped, as many as fit one call, and each group is summarised; repeated until what is left fits
 *   the final call, which writes the summary.
 * Order is kept (only consecutive pieces are grouped) and the roles stay in the text. A piece that alone is over the budget (a very long
 * utterance, or a summary longer than a call) is split at word boundaries, so no word is dropped; if the tree does not shrink or reaches
 * [maxLevels], the remainder is cut to fit the final call. The number of such events is returned, never the text.
 */
class TreeSummary(private val contextTokens: Int = SummaryConfig.CONTEXT_TOKENS, private val maxLevels: Int = SummaryConfig.MAX_LEVELS) : SummaryMethod {
    override val id = "tree-v1/c$contextTokens"

    override fun summarize(segments: List<SegmentRow>, prompts: SummaryPrompts, ask: SummaryAsk, step: (Int, Int) -> Unit): SummaryOutput? {
        var pieces = segments.map { it.text.trim() }.filter { it.isNotEmpty() }
        if (pieces.isEmpty()) return null
        var calls = 0
        var splits = 0
        fun call(system: String, user: String, maxOut: Int): String { calls++; return ask.ask(system, user, maxOut) }
        val finalFits = { text: String -> fits(prompts.finalSystem, finalUser(prompts, text), SummaryConfig.FINAL_MAX_OUTPUT_TOKENS) }
        val notesFits = { text: String -> fits(prompts.notesSystem, notesUser(prompts, 99, 99, text), SummaryConfig.NOTES_MAX_OUTPUT_TOKENS) }

        var total = 1
        var done = 0
        step(done, total)
        var level = 0
        while (true) {
            val joined = pieces.joinToString("\n\n")
            val last = finalFits(joined) || level >= maxLevels
            if (last) {
                var text = joined
                if (!finalFits(text)) { text = cut(text, finalFits); splits++ }
                val out = call(prompts.finalSystem, finalUser(prompts, text), SummaryConfig.FINAL_MAX_OUTPUT_TOKENS)
                step(total, total)
                return SummaryOutput(out, calls, splits, level + 1)
            }
            val fitted = pieces.flatMap { p -> if (notesFits(p)) listOf(p) else splitToFit(p, notesFits).also { splits++ } }
            val groups = group(fitted, notesFits)
            if (groups.size >= pieces.size && fitted.size == pieces.size) {
                // Nothing would shrink (the budget holds hardly more than one piece): cut the remainder to the final call.
                level = maxLevels
                continue
            }
            total += groups.size
            pieces = groups.mapIndexed { i, g ->
                call(prompts.notesSystem, notesUser(prompts, i + 1, groups.size, g.joinToString("\n\n")), SummaryConfig.NOTES_MAX_OUTPUT_TOKENS).trim().also { step(++done, total) }
            }.filter { it.isNotEmpty() }
            if (pieces.isEmpty()) return SummaryOutput(call(prompts.finalSystem, finalUser(prompts, ""), SummaryConfig.FINAL_MAX_OUTPUT_TOKENS), calls, splits, level + 1)
            level++
        }
    }

    private fun finalUser(prompts: SummaryPrompts, text: String) = Prompts.fill(prompts.finalUser, mapOf("TEXT" to text))
    private fun notesUser(prompts: SummaryPrompts, part: Int, parts: Int, text: String) =
        Prompts.fill(prompts.notesUser, mapOf("PART" to "$part", "PARTS" to "$parts", "TEXT" to text))

    private fun fits(system: String, user: String, maxOut: Int) = PromptBudget.required(system, user, maxOut) <= contextTokens

    /** Consecutive pieces, as many per group as fit one call. Every piece fits alone (see [splitToFit]). */
    private fun group(pieces: List<String>, fits: (String) -> Boolean): List<List<String>> {
        val out = ArrayList<MutableList<String>>()
        for (p in pieces) {
            val cur = out.lastOrNull()
            if (cur != null && fits((cur + p).joinToString("\n\n"))) cur += p else out += mutableListOf(p)
        }
        return out
    }

    /** [text] cut at word boundaries into consecutive parts that each fit; at least one word per part, so it always terminates. */
    private fun splitToFit(text: String, fits: (String) -> Boolean): List<String> {
        val words = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val out = ArrayList<String>()
        var from = 0
        while (from < words.size) {
            var lo = from + 1
            var hi = words.size
            while (lo < hi) { // the longest run that fits (monotone)
                val mid = (lo + hi + 1) / 2
                if (fits(words.subList(from, mid).joinToString(" "))) lo = mid else hi = mid - 1
            }
            out += words.subList(from, lo).joinToString(" ")
            from = lo
        }
        return out
    }

    /** The longest start of [text] that fits (at least one word). */
    private fun cut(text: String, fits: (String) -> Boolean): String = splitToFit(text, fits).first()

    companion object {
        fun wordCount(s: String) = s.split(Regex("\\s+")).count { it.isNotEmpty() }

        /** The context the method needs at most: the one it budgets against, by construction (the app loads at least this much for every Gemma job). */
        fun requiredContext(): Int = SummaryConfig.CONTEXT_TOKENS
    }
}
