package com.therapylocal.app.facts

import com.therapylocal.app.data.SegmentRow
import org.json.JSONObject
import java.security.MessageDigest

/**
 * One thing the client said, as extracted from a transcript window: a short [heading], a few plain sentences of [content], and [evidence],
 * a verbatim quote from the transcript, so a therapist can always check it. [segIdx] is the transcript segment of the quote, 0 if unknown.
 */
data class Fact(val heading: String, val content: String, val evidence: String, val segIdx: Int = 0) {
    fun toJson(): JSONObject = JSONObject().put("heading", heading).put("content", content).put("evidence", evidence).put("seg", segIdx)

    companion object {
        fun fromJson(o: JSONObject) = Fact(o.optString("heading"), o.optString("content"), o.optString("evidence"), o.optInt("seg", 0))
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
      "evidence":{"type":"string","minLength":5,"maxLength":160}},
      "required":["heading","content","evidence"],"additionalProperties":false}}},"required":["facts"],"additionalProperties":false}
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

    private val PAIR = Regex("\"(heading|content|evidence)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")

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
            val key = m.groupValues[1]
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
        .map { r -> Fact(r["heading"].orEmpty().trim(), r["content"].orEmpty().trim(), r["evidence"].orEmpty().trim()) }
        .filter(::valid)

    private fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

    /** Hallucination filter: the quoted evidence must really occur in the source window (after dropping punctuation and case). */
    fun evidenceHolds(f: Fact, source: String): Boolean = f.evidence.isNotBlank() && norm(source).contains(norm(f.evidence))

    /**
     * The segment the quote is in, with the same normalisation as [evidenceHolds]; a quote that runs over a segment boundary belongs to the
     * segment of its first word. Null if the quote is in none of [segments].
     */
    fun segmentOf(evidence: String, segments: List<SegmentRow>): Int? {
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
        return segments[ends.indexOfFirst { at < it }].idx
    }

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
