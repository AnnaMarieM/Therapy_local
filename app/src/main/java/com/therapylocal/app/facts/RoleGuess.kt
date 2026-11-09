package com.therapylocal.app.facts

import com.therapylocal.app.data.Role
import com.therapylocal.app.data.SegmentRow
import com.therapylocal.app.data.TranscriptFormat
import org.json.JSONObject

/**
 * Asking the LLM who the therapist is: the transcript it sees, the schema of its answer, and a strict check of the reply. A reply that fails the
 * check is no guess at all ([parse] returns null); the caller retries once and then uses [fallback]. Pure Kotlin apart from org.json.
 */
object RoleGuess {
    /** [map] has every real speaker; [evidence] is at most [MAX_EVIDENCE] quotes that really occur in the input. */
    data class Guess(val map: Map<String, Role>, val confidence: Double, val evidence: List<String>)

    const val MAX_EVIDENCE = 3

    /** For constrained decoding. */
    const val SCHEMA =
        """{"type":"object","properties":{"therapist":{"type":"string"},"patient":{"type":"string"},"confidence":{"type":"number"},"evidence":{"type":"array","items":{"type":"string"}}},"required":["therapist","patient","confidence","evidence"]}"""

    /**
     * The start of the transcript with the raw ids as labels (`S0: ...`), cut after the last utterance that fits into [maxTokens]. The model's
     * tokenizer is not reachable, so tokens are converted to words as [TranscriptWindows.wordsFor] does. The first utterance is always kept.
     */
    fun input(segments: List<SegmentRow>, maxTokens: Int = 4000): String {
        val budget = TranscriptWindows.wordsFor(maxTokens)
        var words = 0
        val kept = ArrayList<SegmentRow>()
        for (s in segments) {
            words += s.text.split(Regex("\\s+")).count { it.isNotEmpty() }
            if (kept.isNotEmpty() && words > budget) break
            kept += s
        }
        return TranscriptFormat.format(kept) { it ?: "?" }
    }

    /**
     * Reads the reply, which may sit in a code fence or between sentences. Null when it holds no JSON object, or when the therapist or the
     * patient is missing, not one of [realSpeakers], or the same speaker. Every other real speaker is [Role.OTHER]. Quotes that do not occur in
     * [input] (compared without case and extra whitespace) are dropped.
     */
    fun parse(reply: String, realSpeakers: Collection<String>, input: String): Guess? {
        val o = jsonObject(reply) ?: return null
        val therapist = speaker(o.opt("therapist"), realSpeakers) ?: return null
        val patient = speaker(o.opt("patient"), realSpeakers) ?: return null
        if (therapist == patient) return null
        val confidence = o.optDouble("confidence", 0.0).let { if (it.isNaN()) 0.0 else it.coerceIn(0.0, 1.0) }
        val haystack = normalise(input)
        val evidence = ArrayList<String>()
        val arr = o.optJSONArray("evidence")
        if (arr != null) for (i in 0 until arr.length()) {
            val q = (arr.opt(i) as? String)?.trim() ?: continue
            val n = normalise(q)
            if (n.isNotEmpty() && n in haystack && q !in evidence) evidence += q
            if (evidence.size == MAX_EVIDENCE) break
        }
        val map = realSpeakers.associateWith { Role.OTHER } + (therapist to Role.THERAPIST) + (patient to Role.PATIENT)
        return Guess(map, confidence, evidence)
    }

    /** First speaker therapist, second patient, the rest other; confidence 0 says nothing was learnt. */
    fun fallback(speakersInOrderOfAppearance: List<String>): Guess {
        val ids = speakersInOrderOfAppearance.distinct()
        val map = ids.withIndex().associate { (i, id) -> id to (when (i) { 0 -> Role.THERAPIST; 1 -> Role.PATIENT; else -> Role.OTHER }) }
        return Guess(map, 0.0, emptyList())
    }

    private fun normalise(s: String) = s.trim().lowercase().replace(Regex("\\s+"), " ")

    /** The real id the model named, matched exactly or else ignoring case. */
    private fun speaker(v: Any?, real: Collection<String>): String? {
        val s = (v as? String)?.trim() ?: return null
        return real.firstOrNull { it == s } ?: real.firstOrNull { it.equals(s, ignoreCase = true) }
    }

    private fun jsonObject(reply: String): JSONObject? {
        val end = reply.lastIndexOf('}')
        var start = reply.indexOf('{')
        while (start in 0 until end) {
            try {
                return JSONObject(reply.substring(start, end + 1))
            } catch (_: Exception) {
                start = reply.indexOf('{', start + 1)
            }
        }
        return null
    }
}
