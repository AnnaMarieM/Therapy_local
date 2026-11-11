package com.therapylocal.app.summary

import com.therapylocal.app.config.SummaryConfig
import com.therapylocal.app.data.SegmentRow
import com.therapylocal.app.facts.PromptBudget
import com.therapylocal.app.facts.Prompts
import com.therapylocal.app.facts.TranscriptWindows

/** One call of the LLM: a fresh conversation (the pool's `Llm.ask` with the sampler settings of [SummaryConfig] filled in by the caller). */
fun interface SummaryAsk {
    fun ask(system: String, user: String, maxOutputTokens: Int): String
}

/** The four prompt texts of a summary, with their placeholders already filled except {{TEXT}}, {{PART}} and {{PARTS}}. */
class SummaryPrompts(val finalSystem: String, val finalUser: String, val notesSystem: String, val notesUser: String) {
    /** Changes whenever a prompt text, the format or a limit changes ([SummaryConfig.fingerprint]); stored with every summary. */
    fun version(): String = Prompts.version(finalSystem, finalUser, notesSystem, notesUser, SummaryConfig.fingerprint())

    companion object {
        /** Reads the four files with [prompt] and fills the format and limits into the system prompts. */
        fun load(prompt: (String) -> String, language: String): SummaryPrompts {
            fun named(n: String) = prompt(Prompts.named(n, language))
            val vars = mapOf(
                "FORMAT" to SummaryConfig.formatInstruction(),
                "MAX_WORDS" to SummaryConfig.MAX_WORDS_PER_SECTION.toString(),
                "MAX_SENTENCES" to SummaryConfig.MAX_SENTENCES_PER_SECTION.toString(),
                "NOTES_WORDS" to SummaryConfig.NOTES_MAX_WORDS.toString(),
            )
            return SummaryPrompts(
                Prompts.fill(named(SummaryConfig.PROMPT_FINAL_SYSTEM), vars), named(SummaryConfig.PROMPT_FINAL_USER),
                Prompts.fill(named(SummaryConfig.PROMPT_NOTES_SYSTEM), vars), named(SummaryConfig.PROMPT_NOTES_USER),
            )
        }
    }
}

/** What a method produced: the summary [text] and how many model calls it took (logged, never the text). */
class SummaryOutput(val text: String, val calls: Int)

/**
 * How a transcript becomes a summary. The benchmark for long transcripts is not finished, so the method is a seam: [MapReduceSummary] is the
 * first one, and another (a rolling summary, a hierarchical one) replaces it by implementing this and being passed to [SessionSummaries].
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
 * Map-reduce over [TranscriptWindows]: a transcript that fits one window is summarised in a single call; a longer one is cut into windows,
 * each condensed to notes (map), the notes are merged in groups that fit [SummaryConfig.REDUCE_INPUT_TOKENS] until they all fit one call
 * (reduce levels, using the same notes prompt, which accepts notes as well as transcript), and the last call writes the summary from them.
 */
class MapReduceSummary(
    private val windowTokens: Int = SummaryConfig.WINDOW_TOKENS,
    private val overlapTokens: Int = SummaryConfig.WINDOW_OVERLAP_TOKENS,
    private val reduceTokens: Int = SummaryConfig.REDUCE_INPUT_TOKENS,
    private val maxLevels: Int = SummaryConfig.MAX_REDUCE_LEVELS,
) : SummaryMethod {
    override val id = "map-reduce/w$windowTokens/o$overlapTokens/r$reduceTokens"

    override fun summarize(segments: List<SegmentRow>, prompts: SummaryPrompts, ask: SummaryAsk, step: (Int, Int) -> Unit): SummaryOutput? {
        val windows = TranscriptWindows.bySegments(segments, TranscriptWindows.wordsFor(windowTokens), TranscriptWindows.wordsFor(overlapTokens))
        if (windows.isEmpty()) return null
        var calls = 0
        fun call(system: String, user: String, maxOut: Int): String { calls++; return ask.ask(system, user, maxOut) }
        fun notes(text: String, part: Int, parts: Int) = call(
            prompts.notesSystem, Prompts.fill(prompts.notesUser, mapOf("PART" to "$part", "PARTS" to "$parts", "TEXT" to text)), SummaryConfig.NOTES_MAX_OUTPUT_TOKENS,
        ).trim()

        if (windows.size == 1) {
            step(0, 1)
            val text = call(prompts.finalSystem, finalUser(prompts, windows[0].text), SummaryConfig.FINAL_MAX_OUTPUT_TOKENS)
            step(1, 1)
            return SummaryOutput(text, calls)
        }

        // Steps: every window, then the merges and the last call are counted as they come (the total grows by the merges).
        var total = windows.size + 1
        var done = 0
        step(done, total)
        var level = windows.mapIndexed { i, w -> notes(w.text, i + 1, windows.size).also { step(++done, total) } }
        var levels = 0
        while (words(level) > TranscriptWindows.wordsFor(reduceTokens) && levels < maxLevels) {
            levels++
            val groups = group(level, TranscriptWindows.wordsFor(reduceTokens))
            if (groups.size >= level.size) break // nothing would shrink
            total += groups.size
            level = groups.mapIndexed { i, g -> notes(g.joinToString("\n\n"), i + 1, groups.size).also { step(++done, total) } }
        }
        val joined = trimWords(level.joinToString("\n\n"), TranscriptWindows.wordsFor(reduceTokens))
        val text = call(prompts.finalSystem, finalUser(prompts, joined), SummaryConfig.FINAL_MAX_OUTPUT_TOKENS)
        step(total, total)
        return SummaryOutput(text, calls)
    }

    private fun finalUser(prompts: SummaryPrompts, text: String) = Prompts.fill(prompts.finalUser, mapOf("TEXT" to text))

    private fun words(parts: List<String>) = parts.sumOf { wordCount(it) }

    /** Consecutive notes, each group within [budget] words (a note over the budget alone is a group of its own). */
    private fun group(parts: List<String>, budget: Int): List<List<String>> {
        val out = ArrayList<MutableList<String>>()
        var size = 0
        for (p in parts) {
            val w = wordCount(p)
            if (out.isEmpty() || size + w > budget) { out += mutableListOf(p); size = w } else { out.last() += p; size += w }
        }
        return out
    }

    companion object {
        fun wordCount(s: String) = s.split(Regex("\\s+")).count { it.isNotEmpty() }

        fun trimWords(s: String, max: Int): String {
            val w = s.split(Regex("\\s+")).filter { it.isNotEmpty() }
            return if (w.size <= max) s else w.take(max).joinToString(" ")
        }

        /** The context the method needs at most: the largest prompt of the final call or a notes call, plus the longest reply. Counted for Czech-length words. */
        fun requiredContext(prompts: SummaryPrompts, windowTokens: Int = SummaryConfig.WINDOW_TOKENS, reduceTokens: Int = SummaryConfig.REDUCE_INPUT_TOKENS): Int {
            fun text(tokens: Int) = "ě".repeat(TranscriptWindows.wordsFor(tokens) * 10)
            val notes = PromptBudget.required(prompts.notesSystem, Prompts.fill(prompts.notesUser, mapOf("PART" to "10", "PARTS" to "10", "TEXT" to text(maxOf(windowTokens, reduceTokens)))), SummaryConfig.NOTES_MAX_OUTPUT_TOKENS)
            val final = PromptBudget.required(prompts.finalSystem, Prompts.fill(prompts.finalUser, mapOf("TEXT" to text(maxOf(windowTokens, reduceTokens)))), SummaryConfig.FINAL_MAX_OUTPUT_TOKENS)
            return maxOf(notes, final)
        }
    }
}
