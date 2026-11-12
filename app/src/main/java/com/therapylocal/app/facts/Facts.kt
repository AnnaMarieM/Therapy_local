package com.therapylocal.app.facts

import com.therapylocal.app.data.SegmentRow
import org.json.JSONObject
import java.security.MessageDigest

/**
 * One thing the client said, as extracted from a transcript window: a short [heading], a few plain sentences of [content], and [evidence],
 * a verbatim quote from the transcript, so a therapist can always check it. [segIdx] is the transcript segment the quote starts in (0 if
 * unknown) and [segEnd] the one it ends in (the same for a quote inside one segment). One sentence can give several facts, each with its own
 * [evidence]; the span, the milliseconds and the speaker are derived from the quote at commit ([FactSpans]).
 */
data class Fact(
    val heading: String,
    val content: String,
    val evidence: String,
    val segIdx: Int = 0,
    val scope: String = SCOPE_PROFILE,
    val category: String = "",
    val segEnd: Int = segIdx,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("heading", heading)
        .put("content", content)
        .put("evidence", evidence)
        .put("seg", segIdx)
        .put("segEnd", segEnd)
        .put("scope", scope)
        .put("category", category)

    companion object {
        const val SCOPE_PROFILE = "profile"
        const val SCOPE_SESSION = "session"

        fun fromJson(o: JSONObject) = Fact(
            o.optString("heading"),
            o.optString("content"),
            o.optString("evidence"),
            o.optInt("seg", 0),
            scope = o.optString("scope", SCOPE_PROFILE).ifBlank { SCOPE_PROFILE },
            category = o.optString("category", "").trim(),
            segEnd = o.optInt("segEnd", o.optInt("seg", 0)),
        )
    }
}

/** The review's answer for one candidate: a duplicate of the known fact at index [of] (0-based), or not a duplicate ([of] null). */
data class Verdict(val duplicate: Boolean, val of: Int?) {
    fun encode(): String = if (duplicate) "dup:$of" else "new"

    companion object {
        fun decode(s: String): Verdict? = when {
            s == "new" -> Verdict(false, null)
            s.startsWith("dup:") -> s.removePrefix("dup:").toIntOrNull()?.let { Verdict(true, it) }
            else -> null
        }
    }
}

object FactSchemas {
    /** Last-resort constrained schema: non-empty strings and a capped list, because a looser schema let the GPU decoder loop on whitespace. */
    val EXTRACT_STRICT = """
    {"type":"object","properties":{"facts":{"type":"array","maxItems":20,"items":{"type":"object","properties":{
      "heading":{"type":"string","minLength":2,"maxLength":60},
      "content":{"type":"string","minLength":10,"maxLength":300},
      "evidence":{"type":"string","minLength":5,"maxLength":160},
      "scope":{"type":"string","maxLength":20},
      "category":{"type":"string","maxLength":60}},
      "required":["heading","content","evidence"],"additionalProperties":true}}},"required":["facts"],"additionalProperties":false}
    """.trimIndent()
}

object FactParser {
    /** Tolerant: strips code fences, takes the outermost {...}. Returns null when nothing usable was found. */
    fun json(raw: String): JSONObject? {
        val s = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val a = s.indexOf('{')
        val b = s.lastIndexOf('}')
        if (a < 0 || b <= a) return null
        return try {
            JSONObject(s.substring(a, b + 1))
        } catch (_: Exception) {
            repairTruncated(s.substring(a))
        }
    }

    /** Output cut off at the token cap: keep every complete array item and close the brackets. */
    private fun repairTruncated(s: String): JSONObject? {
        val cut = s.lastIndexOf("},")
        if (cut < 0) return null
        return try {
            JSONObject(s.substring(0, cut + 1) + "]}")
        } catch (_: Exception) {
            null
        }
    }

    private val PAIR = Regex("\"(heading|content|evidence|scope|category|rozsah|kategorie)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")

    private fun unescape(s: String) = s.replace("\\\"", "\"").replace("\\n", " ").replace("\\\\", "\\")

    /**
     * Field-by-field salvage, so it works whatever the model did around the fields: one JSON object per line (asked for), all facts run
     * together on one line with repeated keys (seen on the phone), {"facts":[...]}, or output cut off mid-way. A new fact starts whenever a key
     * repeats. Pure Kotlin, so it is unit-tested on the JVM.
     */
    fun records(text: String): List<Map<String, String>> {
        val out = ArrayList<Map<String, String>>()
        var cur = LinkedHashMap<String, String>()
        for (m in PAIR.findAll(text)) {
            val key = when (val k = m.groupValues[1]) {
                "rozsah" -> "scope"
                "kategorie" -> "category"
                else -> k
            }
            if (key in cur) {
                out += cur
                cur = LinkedHashMap()
            }
            cur[key] = unescape(m.groupValues[2])
        }
        if (cur.isNotEmpty()) out += cur
        return out
    }

    /** A fact is usable only with a heading, a real sentence and a quote to check it against. */
    fun valid(f: Fact): Boolean = f.heading.isNotBlank() && f.content.length >= 10 && f.evidence.length >= 5

    /**
     * Any of the shapes the model produces: one JSON object per line (asked for), a {"facts":[...]} object, or a bare array.
     * Truncated output is salvaged item by item. Only valid facts are returned, trimmed.
     */
    fun anyFacts(text: String): List<Fact> = records(text)
        .map { r ->
            val rawScope = r["scope"]?.lowercase()?.trim() ?: Fact.SCOPE_PROFILE
            val scope = if (rawScope.contains("session") || rawScope.contains("sezení") || rawScope.contains("sezeni")) {
                Fact.SCOPE_SESSION
            } else {
                Fact.SCOPE_PROFILE
            }
            Fact(
                heading = r["heading"].orEmpty().trim(),
                content = r["content"].orEmpty().trim(),
                evidence = r["evidence"].orEmpty().trim(),
                scope = scope,
                category = r["category"].orEmpty().trim(),
            )
        }
        .filter(::valid)

    private fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

    /** Hallucination filter: the quoted evidence must really occur in the source window (after dropping punctuation and case). */
    fun evidenceHolds(f: Fact, source: String): Boolean = f.evidence.isNotBlank() && norm(source).contains(norm(f.evidence))

    /**
     * The segments the quote runs over, first to last, with the same normalisation as [evidenceHolds]: a quote inside one segment gives that
     * segment twice, one that crosses a boundary gives the segment of its first word and the one of its last. Null if the quote is in none of
     * [segments].
     */
    fun segmentSpan(evidence: String, segments: List<SegmentRow>): Pair<Int, Int>? {
        val quote = norm(evidence)
        if (quote.isEmpty()) return null
        val joined = StringBuilder()
        val ends = IntArray(segments.size)
        segments.forEachIndexed { i, s ->
            if (joined.isNotEmpty()) joined.append(' ')
            joined.append(norm(s.text))
            ends[i] = joined.length
        }
        val at = joined.indexOf(quote)
        if (at < 0) return null
        val first = ends.indexOfFirst { at < it }
        val last = ends.indexOfFirst { at + quote.length - 1 < it }.let { if (it < first) first else it }
        return segments[first].idx to segments[last].idx
    }

    /** The segment the quote starts in; a quote that runs over a segment boundary belongs to the segment of its first word. */
    fun segmentOf(evidence: String, segments: List<SegmentRow>): Int? = segmentSpan(evidence, segments)?.first

    private fun tokens(s: String): Set<String> = Regex("[\\p{L}\\p{N}]+").findAll(s.lowercase()).map { it.value }.toSet()

    /**
     * Drops repeats from one extraction, first one wins. Two facts are the same when their content is the same after dropping case and
     * punctuation, or when they rest on the same quote and their content shares at least [SAME_CONTENT] of its words (the model saying one
     * fact twice, or overlapping windows). Facts that share a quote or a sentence but say different things are all kept: one sentence can
     * hold several facts.
     */
    fun dedupe(facts: List<Fact>): List<Fact> {
        val kept = ArrayList<Fact>()
        for (f in facts) {
            val c = tokens(f.content)
            val same = kept.any { k ->
                val kc = tokens(k.content)
                kc == c || (norm(k.evidence) == norm(f.evidence) && jaccard(kc, c) >= SAME_CONTENT)
            }
            if (!same) kept += f
        }
        return kept
    }

    private fun jaccard(a: Set<String>, b: Set<String>): Double =
        if (a.isEmpty() && b.isEmpty()) 1.0 else a.intersect(b).size.toDouble() / a.union(b).size

    /** Share of words two contents on one quote must have in common to count as one fact. */
    const val SAME_CONTENT = 0.85

    /**
     * The duplicate verdict of one review reply against [known] facts listed as K1..Kn, or null if the reply is unreadable. A duplicate
     * without a usable "of" is taken to be of K1, the nearest known fact.
     */
    fun verdict(raw: String, known: Int): Verdict? {
        val o = json(raw)
        val flag = when (val dup = o?.opt("duplicate")) {
            is Boolean -> dup
            is String -> if (dup.equals("true", true)) true else if (dup.equals("false", true)) false else null
            else -> Regex("(?i)\\b(true|false)\\b").find(raw)?.value?.equals("true", true)
        } ?: return null
        if (!flag) return Verdict(false, null)
        val of = o?.optString("of").orEmpty().trim().removePrefix("K").toIntOrNull()?.minus(1)?.takeIf { it in 0 until known } ?: 0
        return Verdict(true, of)
    }
}

/** Prompt templates: `{{NAME}}` placeholders, and a short version that changes whenever a prompt's text does (cache keys, fact provenance). */
object Prompts {
    const val EXTRACT_SYSTEM = "extract.system.txt"
    const val EXTRACT_USER = "extract.user.txt"
    const val REVIEW_SYSTEM = "review.system.txt"
    const val REVIEW_USER = "review.user.txt"

    /** Who is the therapist: the transcript itself is the user message, so there is no user template. */
    const val ROLES_SYSTEM = "roles.system.txt"

    /** The prompt file [name] for [language]: Czech ones are in the cs/ folder with the same names and placeholders. */
    fun named(name: String, language: String): String = if (language == PromptLanguage.CS) "${PromptLanguage.CS_DIR}/$name" else name

    fun fill(template: String, vars: Map<String, String>) = vars.entries.fold(template) { acc, (k, v) -> acc.replace("{{$k}}", v) }

    fun version(vararg texts: String): String = hash(*texts).take(12)

    /** SHA-256 in hex over [parts], each length-prefixed so different splits never collide. */
    fun hash(vararg parts: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        for (p in parts) {
            val b = p.toByteArray(Charsets.UTF_8)
            md.update("${b.size}:".toByteArray(Charsets.UTF_8))
            md.update(b)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
