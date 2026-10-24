package com.therapylocal.app.memory

import org.json.JSONArray
import org.json.JSONObject

/** One thing known about the client. [evidence] is a verbatim quote from the transcript, so a therapist can always check it. */
data class Fact(
    val id: String,
    val category: String,
    val subject: String,
    val fact: String,
    val evidence: String,
    val time: String?,
    val session: String = "",
    /** 1-based extraction window the fact came from, 0 if unknown. */
    val chunk: Int = 0,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("category", category).put("subject", subject).put("fact", fact)
        .put("evidence", evidence).put("time", time ?: JSONObject.NULL).put("session", session).put("chunk", chunk)

    companion object {
        fun fromJson(o: JSONObject) = Fact(
            o.optString("id"), o.optString("category", "other"), o.optString("subject", "client"), o.optString("fact"),
            o.optString("evidence"), if (o.isNull("time")) null else o.optString("time"), o.optString("session"), o.optInt("chunk", 0),
        )
    }
}

object FactSchemas {
    val CATEGORIES = listOf(
        "family", "relationship", "work", "health", "sleep_substance", "event", "history", "emotion", "coping", "goal", "risk", "other",
    )

    /** JSON schema for constrained decoding of the extraction stage. */
    val EXTRACT = """
    {"type":"object","properties":{"facts":{"type":"array","items":{"type":"object","properties":{
      "category":{"type":"string","enum":[${CATEGORIES.joinToString(",") { "\"$it\"" }}]},
      "subject":{"type":"string"},
      "fact":{"type":"string"},
      "evidence":{"type":"string"},
      "time":{"type":"string"}},
      "required":["category","subject","fact","evidence"]}}},"required":["facts"]}
    """.trimIndent()

    /** Last-resort constrained schema: non-empty strings and a capped list, because the plain schema let the GPU decoder loop on whitespace. */
    val EXTRACT_STRICT = """
    {"type":"object","properties":{"facts":{"type":"array","maxItems":20,"items":{"type":"object","properties":{
      "category":{"type":"string","enum":[${CATEGORIES.joinToString(",") { "\"$it\"" }}]},
      "subject":{"type":"string","minLength":2,"maxLength":40},
      "fact":{"type":"string","minLength":10,"maxLength":200},
      "evidence":{"type":"string","minLength":5,"maxLength":160}},
      "required":["category","subject","fact","evidence"],"additionalProperties":false}}},"required":["facts"],"additionalProperties":false}
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

    private val PAIR = Regex("\"(category|subject|fact|evidence|time)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")

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

    fun factsFromLines(text: String, idPrefix: String, session: String, chunk: Int = 0): List<Fact> {
        val out = ArrayList<Fact>()
        for (r in records(text)) {
            val fact = r["fact"]?.trim().orEmpty()
            if (fact.isEmpty()) continue
            out += Fact("$idPrefix${out.size + 1}", category(r["category"].orEmpty()), (r["subject"] ?: "client").ifBlank { "client" }, fact, r["evidence"].orEmpty().trim(), null, session, chunk)
        }
        return out
    }

    /** A fact is usable only with a real sentence and a quote to check it against. */
    fun valid(f: Fact): Boolean = f.fact.length >= 10 && f.evidence.length >= 5

    /**
     * Any of the shapes the model produces: one JSON object per line (asked for), a {"facts":[...]} object, or a bare array.
     * Truncated output is salvaged item by item. Only valid facts are returned.
     */
    fun anyFacts(text: String, idPrefix: String, session: String, chunk: Int): List<Fact> = factsFromLines(text, idPrefix, session, chunk).filter { valid(it) }

    /** The small model sometimes invents categories ("sleep"); map the near misses and fall back to "other". */
    fun category(raw: String): String {
        val c = raw.trim().lowercase().replace(' ', '_')
        return when {
            c in FactSchemas.CATEGORIES -> c
            c.startsWith("sleep") || c.startsWith("alcohol") || c.startsWith("substance") -> "sleep_substance"
            c.startsWith("famil") -> "family"
            c.startsWith("relat") -> "relationship"
            c.startsWith("emotion") || c.startsWith("feel") -> "emotion"
            else -> "other"
        }
    }

    fun cleanTime(raw: String): String? = raw.trim().takeIf { it.isNotEmpty() && it.lowercase() !in setOf("n/a", "na", "none", "null", "unknown", "-") }

    fun facts(o: JSONObject, idPrefix: String, session: String): List<Fact> {
        val arr = o.optJSONArray("facts") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val f = arr.optJSONObject(i) ?: return@mapNotNull null
            val text = f.optString("fact").trim()
            if (text.isEmpty()) return@mapNotNull null
            Fact(
                "$idPrefix${i + 1}", f.optString("category", "other"), f.optString("subject", "client").ifBlank { "client" }, text,
                f.optString("evidence").trim(), f.optString("time").takeIf { it.isNotBlank() }, session,
            )
        }
    }

    fun ids(a: JSONArray?): List<String> = if (a == null) emptyList() else (0 until a.length()).map { a.optString(it) }

    private fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

    /** Hallucination filter: the quoted evidence must really occur in the source window (after dropping punctuation and case). */
    fun evidenceHolds(f: Fact, source: String): Boolean = f.evidence.isNotBlank() && norm(source).contains(norm(f.evidence))
}
