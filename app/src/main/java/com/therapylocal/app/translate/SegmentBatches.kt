package com.therapylocal.app.translate

import com.therapylocal.app.facts.PromptBudget

/**
 * Many short texts in one model call, the protocol of the TRANSLATE and LOCALIZE jobs (docs/AD/AD-17.md): the request has one line per text,
 * `[id] text`, and the reply must have the same ids. A model does not always keep to it, so [parse] takes what it can and the caller asks again
 * for the missing ones. Pure Kotlin.
 */
object SegmentBatches {
    /** One text to translate; [id] is the segment index (TRANSLATE) or a running number (LOCALIZE). */
    data class Item(val id: Int, val text: String)

    /** Consecutive [items] grouped so that a group has at most about [maxWords] words; one longer text is a group of its own. */
    fun batches(items: List<Item>, maxWords: Int): List<List<Item>> {
        val out = ArrayList<List<Item>>()
        var cur = ArrayList<Item>()
        var words = 0
        for (item in items) {
            val w = wordCount(item.text)
            if (cur.isNotEmpty() && words + w > maxWords) {
                out += cur
                cur = ArrayList()
                words = 0
            }
            cur += item
            words += w
        }
        if (cur.isNotEmpty()) out += cur
        return out
    }

    /** One line per item, `[id] text`, line breaks inside a text turned into spaces. */
    fun request(batch: List<Item>): String = batch.joinToString("\n") { "[${it.id}] ${collapse(it.text)}" }

    private val LINE = Regex("^\\s*\\[(\\d+)]\\s*(.*)$")

    /**
     * The texts of [reply] by id, for the ids in [expected] only. A line without a marker continues the text before it; ids that were not asked
     * for, text before the first marker, code fences and empty texts are dropped; an id that appears twice keeps its first text.
     */
    fun parse(reply: String, expected: Collection<Int>): Map<Int, String> {
        val want = expected.toSet()
        val parts = LinkedHashMap<Int, StringBuilder>()
        var current: StringBuilder? = null
        for (raw in reply.lines()) {
            if (raw.trimStart().startsWith("```")) continue
            val m = LINE.matchEntire(raw)
            if (m != null) {
                val id = m.groupValues[1].toIntOrNull()
                current = if (id != null && id in want && id !in parts) StringBuilder(m.groupValues[2].trim()).also { parts[id] = it } else null
            } else if (raw.isNotBlank()) {
                current?.let { if (it.isNotEmpty()) it.append(' '); it.append(raw.trim()) }
            }
        }
        return parts.mapValues { it.value.toString().trim() }.filterValues { it.isNotEmpty() }
    }

    fun collapse(text: String): String = text.replace(Regex("\\s*[\\r\\n]+\\s*"), " ").trim()

    private fun wordCount(text: String): Int = text.trim().split(Regex("\\s+")).count { it.isNotEmpty() }
}

/**
 * Translates [SegmentBatches.Item]s through [ask] (the model, one fresh conversation per call): batches first, then every text a batch left out
 * is asked for alone, once. [ask] returns null for a call that failed in a way another try may fix and throws for one that must stop the job
 * (engine died, stop pressed). Texts still missing after that are not in [Result.translated]: the caller keeps the original. Never logs.
 */
class BatchTranslator(
    private val ask: (user: String, maxOutputTokens: Int) -> String?,
    private val maxWords: Int,
    /** The longest reply allowed, as a multiple of the request's estimated tokens. */
    private val outputFactor: Int,
    private val checkStop: () -> Unit = {},
) {
    class Result(val translated: Map<Int, String>, val calls: Int, val failedCalls: Int, val fallbacks: Int, val chars: Int)

    fun run(items: List<SegmentBatches.Item>, onBatch: (Map<Int, String>) -> Unit = {}, progress: (done: Int, total: Int) -> Unit = { _, _ -> }): Result {
        val out = LinkedHashMap<Int, String>()
        var calls = 0
        var failed = 0
        var chars = 0
        progress(0, items.size)
        fun call(batch: List<SegmentBatches.Item>): Map<Int, String> {
            checkStop()
            val user = SegmentBatches.request(batch)
            calls++
            chars += user.length
            val reply = ask(user, maxOf(MIN_OUTPUT_TOKENS, PromptBudget.tokens(user) * outputFactor))
            if (reply == null) failed++
            return reply?.let { SegmentBatches.parse(it, batch.map { b -> b.id }) }.orEmpty()
        }
        for (batch in SegmentBatches.batches(items, maxWords)) {
            val got = LinkedHashMap(call(batch))
            for (item in batch) if (item.id !in got) got += call(listOf(item))
            out += got
            onBatch(got)
            progress(out.size, items.size)
        }
        return Result(out, calls, failed, items.count { it.id !in out }, chars)
    }

    companion object {
        const val MIN_OUTPUT_TOKENS = 64
    }
}
