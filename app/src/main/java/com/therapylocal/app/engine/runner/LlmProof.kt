package com.therapylocal.app.engine.runner

import java.text.Normalizer

/**
 * The proof a backend must pass before the ladder keeps it (docs/MEMORY.md, issue #48). A backend that loads but computes garbage (Gemma E2B on
 * the Adreno 650 answered one JSON object with duplicate keys instead of five) used to pass "Reply with OK.". Now the model must reformat a short
 * made-up Czech text as a JSON array, and the reply is checked strictly. Pure Kotlin: no Android, no LiteRT, so JVM tests cover the checker.
 * The check does not depend on exact wording: structure, the keys, no duplicate keys, a key word of every fact and the one allowed category word for it (case and diacritics ignored).
 * The text is made up. It is never patient data, and the reply is never logged (only its length and the failure reason).
 */
internal object LlmProof {
    val FACTS = listOf("Jana má psa Rexe.", "Pracuje jako učitelka v Brně.", "Ráda plave.", "Má dceru Elišku.")

    /** Per fact: the one category word the instruction allows for it. A garbled word (the Adreno 650 wrote "Rodzina", "Pracování") fails. */
    val CATEGORIES = listOf("zvíře", "práce", "koníček", "rodina")

    /** Per fact: every key word must occur in the text of the object that carries the fact (normalised). */
    val KEY_WORDS: List<List<String>> = listOf(listOf("rex"), listOf("ucitel", "brn"), listOf("plav"), listOf("elisk"))

    val KEYS = setOf("kategorie", "text")

    /** Greedy sampling and a short answer: the tablet CPU decodes at about 15 tokens/s. */
    const val MAX_OUTPUT_TOKENS = 160
    const val TOP_K = 1
    const val TEMPERATURE = 0.0

    /** Like the app's real calls (issue #48): the rules in a system instruction, the material in the user message. */
    val SYSTEM: String = "You convert short Czech sentences into JSON. Reply with a JSON array only, no other text. " +
        "The array has exactly one object per sentence, in the same order. Each object has exactly two keys: \"kategorie\" and \"text\". " +
        "\"text\" is the sentence, copied unchanged. \"kategorie\" is exactly one of these words: ${CATEGORIES.joinToString(", ")} " +
        "(zvíře for a pet, práce for a job, koníček for a hobby, rodina for a family member)."

    val USER: String = FACTS.joinToString("\n")

    sealed class Verdict {
        object Ok : Verdict()
        data class Fail(val reason: String) : Verdict()
    }

    fun check(reply: String): Verdict {
        val body = stripFence(reply.trim())
        if (body.isEmpty()) return Verdict.Fail("empty_reply")
        val objects = try {
            Parser(body).parseArray()
        } catch (e: DuplicateKey) {
            return Verdict.Fail("duplicate_key")
        } catch (e: BadJson) {
            return Verdict.Fail("invalid_json")
        }
        if (objects.size != FACTS.size) return Verdict.Fail("wrong_count")
        if (objects.any { it.keys != KEYS }) return Verdict.Fail("wrong_keys")
        if (objects.any { o -> o.values.any { it.isBlank() } }) return Verdict.Fail("empty_value")
        KEY_WORDS.forEachIndexed { i, words ->
            val o = objects.firstOrNull { o -> normalise(o.getValue("text")).let { t -> words.all { t.contains(it) } } } ?: return Verdict.Fail("missing_fact:${i + 1}")
            if (normalise(o.getValue("kategorie")).trim() != normalise(CATEGORIES[i])) return Verdict.Fail("wrong_category:${i + 1}")
        }
        return Verdict.Ok
    }

    internal fun normalise(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()

    private fun stripFence(s: String): String {
        if (!s.startsWith("```")) return s
        val afterOpen = s.substringAfter('\n', "")
        return afterOpen.removeSuffix("```").trim()
    }

    private class DuplicateKey : Exception()
    private class BadJson : Exception()

    /** A strict parser for an array of flat objects with string values. org.json keeps the last of two equal keys silently; this one throws. */
    private class Parser(private val s: String) {
        private var p = 0

        fun parseArray(): List<Map<String, String>> {
            ws(); expect('[')
            val out = ArrayList<Map<String, String>>()
            ws()
            if (peek() == ']') { p++; end(); return out }
            while (true) {
                out += parseObject()
                ws()
                when (next()) {
                    ',' -> continue
                    ']' -> break
                    else -> throw BadJson()
                }
            }
            end()
            return out
        }

        private fun parseObject(): Map<String, String> {
            ws(); expect('{')
            val m = LinkedHashMap<String, String>()
            var dup = false
            ws()
            if (peek() == '}') { p++; return m }
            while (true) {
                ws()
                val k = parseString()
                ws(); expect(':'); ws()
                val v = parseString()
                if (m.put(k, v) != null) dup = true
                ws()
                when (next()) {
                    ',' -> continue
                    '}' -> break
                    else -> throw BadJson()
                }
            }
            // Finish the whole object first: a malformed one is invalid JSON, a well-formed one with a repeated key is the #48 shape.
            if (dup) throw DuplicateKey()
            return m
        }

        private fun parseString(): String {
            expect('"')
            val b = StringBuilder()
            while (true) {
                val c = next()
                when {
                    c == '"' -> return b.toString()
                    c == '\\' -> when (val e = next()) {
                        '"', '\\', '/' -> b.append(e)
                        'n' -> b.append('\n'); 't' -> b.append('\t'); 'r' -> b.append('\r'); 'b' -> b.append('\b'); 'f' -> b.append('\u000c')
                        'u' -> {
                            if (p + 4 > s.length) throw BadJson()
                            b.append(s.substring(p, p + 4).toIntOrNull(16)?.toChar() ?: throw BadJson()); p += 4
                        }
                        else -> throw BadJson()
                    }
                    c < ' ' -> throw BadJson()
                    else -> b.append(c)
                }
            }
        }

        private fun ws() { while (p < s.length && s[p].isWhitespace()) p++ }
        private fun peek(): Char = if (p < s.length) s[p] else throw BadJson()
        private fun next(): Char = if (p < s.length) s[p++] else throw BadJson()
        private fun expect(c: Char) { if (next() != c) throw BadJson() }
        private fun end() { ws(); if (p != s.length) throw BadJson() }
    }
}
