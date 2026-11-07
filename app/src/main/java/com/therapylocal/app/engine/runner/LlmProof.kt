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

    // ---- Second proof (issue #48): the app's own kind of instruction, one JSON object per fact with a quote -------------------------------------
    // The reformat task above passed on the tablet's Adreno 650 with the generic Gemma file although that GPU answered the app's extraction prompt with
    // ONE merged object (invented keys, a repeated key). What separated them was not the length (this prompt is shorter than the first proof) but the
    // instruction: "one JSON object per line with keys heading, content, evidence (an exact quote)". So this proof uses exactly that instruction
    // on three made-up sentences. The check is structural and does not depend on the model's wording or on the key names it picks (the CPU writes
    // Klíč/Obsah/Důkaz): at least three objects, three distinct keys each, no repeated key, no empty value, and the last value (the quote) must occur in the text.

    val SYSTEM2: String = "You are a careful note-taker for a psychotherapist. List the facts the client stated, one JSON object per line with keys heading, content, evidence (an exact quote). Write heading and content in Czech."

    /** Made-up text. Three sentences, so three objects are expected. */
    val SENTENCES: List<String> = listOf(
        "Moje sestra Lucie se přestěhovala do Brna a já mám pocit, že jsme si s ní teď strašně vzdálené.",
        "Usínám až po půlnoci a v pět ráno se budím.",
        "Pila jsem proto víc kávy, tři až čtyři hrnky denně.",
    )
    val USER2: String = "Klient: Petra\n\nPetra: " + SENTENCES.joinToString(" ")

    /** The CPU needs about 230 tokens for three objects. */
    const val MAX_OUTPUT_TOKENS2 = 320

    fun checkFacts(reply: String): Verdict {
        // Fence lines (```json) are not part of the data; objects follow one another, or sit in one array.
        val body = reply.lines().filterNot { it.trim().startsWith("```") }.joinToString("\n").trim()
        if (body.isEmpty()) return Verdict.Fail("empty_reply")
        val objects = try {
            Parser(body).parseObjects()
        } catch (e: DuplicateKey) {
            return Verdict.Fail("duplicate_key")
        } catch (e: BadJson) {
            return Verdict.Fail("invalid_json")
        }
        if (objects.size < SENTENCES.size) return Verdict.Fail("too_few_objects")
        if (objects.any { it.size != 3 }) return Verdict.Fail("wrong_keys")
        if (objects.any { o -> o.values.any { it.isBlank() } }) return Verdict.Fail("empty_value")
        val text = normalise(SENTENCES.joinToString(" "))
        val quotes = objects.map { normalise(it.values.last()).trim() }
        quotes.forEachIndexed { i, q -> if (q.isEmpty() || !text.contains(q)) return Verdict.Fail("evidence_not_quote:${i + 1}") }
        if (quotes.toSet().size != quotes.size) return Verdict.Fail("repeated_evidence")
        return Verdict.Ok
    }

    /** A failed proof strikes an accelerator; the fallback (CPU) is the reference and the last resort, so it is kept (and the failure logged as an error). */
    fun rejects(backendIsFallback: Boolean, verdict: Verdict): Boolean = verdict is Verdict.Fail && !backendIsFallback

    sealed class Verdict {
        object Ok : Verdict() { override fun toString() = "Ok" }
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

        /** Objects one after another (optionally comma separated), or one array of objects. */
        fun parseObjects(): List<Map<String, String>> {
            ws()
            if (p < s.length && s[p] == '[') return parseArray()
            val out = ArrayList<Map<String, String>>()
            while (true) {
                ws()
                if (p >= s.length) break
                out += parseObject()
                ws()
                if (p < s.length && s[p] == ',') p++
            }
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
