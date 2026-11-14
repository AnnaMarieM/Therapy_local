package com.therapylocal.app.translate

import android.content.Context
import com.therapylocal.app.config.ProcessingLanguageSetting
import com.therapylocal.app.facts.Prompts
import com.therapylocal.app.i18n.LocaleStore

/**
 * What is shown is looked up here (docs/AD/AD-17.md): facts, the summary and live cues are stored in English and shown in the app's language
 * when the LOCALIZE job has translated them. A text without a translation (not done yet, or it failed) is shown in English. Pure Kotlin.
 */
class Display(private val found: Map<String, String>) {
    /** [english] as it is shown: its translation, or itself. */
    fun text(english: String): String = found[sha(english)] ?: english

    /** True when something was translated: a screen need not look any further for a language the user does not read. */
    val isEmpty: Boolean get() = found.isEmpty()

    companion object {
        val NONE = Display(emptyMap())

        /** The key of a text in the display translations: a hash of the English text, so the same text found again needs no new translation. */
        fun sha(text: String): String = Prompts.hash(text)

        /** The display of [texts] in [lang] through [lookup] (a bulk read of the stored translations, hash to text); [NONE] when there is no target language. */
        fun of(lang: String?, texts: Collection<String>, lookup: (Collection<String>, String) -> Map<String, String>): Display =
            if (lang == null || texts.isEmpty()) NONE else Display(lookup(texts.filter { it.isNotBlank() }.map(::sha), lang))
    }
}

/** The language what is shown has to be translated into, or null when it needs no translation. */
object DisplayLanguage {
    fun target(context: Context): String? {
        val app = LocaleStore.current(context).tag
        return if (Pivot.translatesOutput(ProcessingLanguageSetting.get(context), app)) app else null
    }
}

/**
 * The texts the LOCALIZE job has to translate, as the numbered lines of [BatchTranslator]: a text with several lines (a summary section) is
 * translated line by line, so its list structure survives. Pure Kotlin.
 */
class LocalizePlan private constructor(val items: List<SegmentBatches.Item>, private val sources: List<Source>) {
    private class Source(val sha: String, val lines: List<Pair<Int?, String>>)

    val isEmpty: Boolean get() = items.isEmpty()

    /** Display text by hash for every source whose lines were all translated ([translated] is by item id); a source with a line missing is left out and shown in English. */
    fun assemble(translated: Map<Int, String>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (s in sources) {
            if (s.lines.any { (id, _) -> id != null && id !in translated }) continue
            out[s.sha] = s.lines.joinToString("\n") { (id, text) -> if (id == null) text else translated.getValue(id) }
        }
        return out
    }

    companion object {
        /** A plan for the distinct, non-blank [texts] (blank lines inside a text are kept as they are). */
        fun of(texts: Collection<String>): LocalizePlan {
            val items = ArrayList<SegmentBatches.Item>()
            val sources = ArrayList<Source>()
            for (text in texts.filter { it.isNotBlank() }.distinct()) {
                val lines = text.lines().map { line -> if (line.isBlank()) null to "" else (items.size + 1).also { items += SegmentBatches.Item(it, line.trim()) } to line }
                sources += Source(Display.sha(text), lines)
            }
            return LocalizePlan(items, sources)
        }
    }
}
