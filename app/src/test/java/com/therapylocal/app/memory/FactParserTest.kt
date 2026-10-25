package com.therapylocal.app.memory

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** All fixtures are synthetic placeholders ("Client", neutral sentences); no real transcripts. */
class FactParserTest {
    private data class Exp(val category: String, val subject: String, val fact: String, val evidence: String)

    private val e1 = Exp("work", "Client", "Client manages a small team at the office.", "I manage a small team here")
    private val e2 = Exp("coping", "Client", "Client goes for a walk when stressed.", "I go for a walk when it gets loud")
    private val e3 = Exp("family", "Sibling", "Sibling lives in another city nearby.", "my sibling lives across town")

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
    private fun obj(e: Exp) = "{\"category\":\"${e.category}\",\"subject\":\"${esc(e.subject)}\",\"fact\":\"${esc(e.fact)}\",\"evidence\":\"${esc(e.evidence)}\"}"
    private fun pairs(e: Exp) = obj(e).removePrefix("{").removeSuffix("}")

    /** Rendered text plus, per fact, the offset just after its last character (the closing quote of "evidence"). */
    private class Rendered(val text: String, val ends: List<Int>)

    private fun render(facts: List<Exp>, shape: Int, rnd: Random): Rendered {
        val sb = StringBuilder()
        val ends = ArrayList<Int>()
        when (shape) {
            0 -> { // one object per line
                facts.forEach { sb.append(obj(it)); ends += sb.length - 1; sb.append("\n") }
            }
            1 -> { // run-on, repeated keys, no braces between facts
                sb.append("{")
                facts.forEachIndexed { i, f -> if (i > 0) sb.append(","); sb.append(pairs(f)); ends += sb.length }
            }
            2 -> { // wrapper, optional trailing comma
                sb.append("{\"facts\":[")
                facts.forEachIndexed { i, f -> if (i > 0) sb.append(","); sb.append(obj(f)); ends += sb.length - 1 }
                sb.append(if (rnd.nextBoolean()) ",]}" else "]}")
            }
            else -> { // markdown fence
                sb.append("```json\n")
                facts.forEach { sb.append(obj(it)); ends += sb.length - 1; sb.append("\n") }
                sb.append("```")
            }
        }
        return Rendered(sb.toString(), ends)
    }

    private val words = listOf("alpha", "beta", "gamma", "delta", "walk", "team", "river", "plan", "sleep", "kitchen", "quiet", "morning")

    private fun randomFact(rnd: Random): Exp {
        fun phrase(n: Int, extra: String = "") = (1..n).joinToString(" ") { words[rnd.nextInt(words.size)] } + extra
        val extra = when (rnd.nextInt(4)) {
            0 -> " said \"ok\""
            1 -> " back\\slash"
            2 -> " \u201Ccurly\u201D \u2019s caf\u00E9"
            else -> ""
        }
        return Exp(
            FactSchemas.CATEGORIES[rnd.nextInt(FactSchemas.CATEGORIES.size)], "Subj " + phrase(1),
            "Client reports " + phrase(4 + rnd.nextInt(4), extra), "I said " + phrase(3 + rnd.nextInt(3), extra),
        )
    }

    private fun tuple(f: Fact) = Exp(f.category, f.subject, f.fact, f.evidence)

    private fun checkAllPrefixes(r: Rendered, expected: List<Exp>) {
        val full = FactParser.anyFacts(r.text, "x", "s", 1).map { tuple(it) }
        assertEquals("full text must parse completely", expected, full)
        for (n in 0..r.text.length) {
            val got = FactParser.anyFacts(r.text.substring(0, n), "x", "s", 1)
            assertTrue("blank evidence at $n", got.all { it.evidence.isNotBlank() && it.fact.isNotBlank() })
            val tuples = got.map { tuple(it) }
            // Nothing is ever invented: a prefix yields the leading facts of the full result.
            assertEquals("prefix $n", full.take(tuples.size), tuples)
            // A prefix that reaches fact k's last character must yield every fact up to k.
            val complete = r.ends.count { it <= n }
            assertTrue("prefix $n lost complete facts: got ${tuples.size}, need $complete", tuples.size >= complete)
        }
    }

    @Test
    fun everyPrefixOfRealisticOutputsIsSafeAndMonotone() {
        val rnd = Random(42)
        for (shape in 0..3) checkAllPrefixes(render(listOf(e1, e2, e3), shape, rnd), listOf(e1, e2, e3))
    }

    @Test
    fun everyPrefixOfRandomOutputsIsSafeAndMonotone() {
        val rnd = Random(20240607)
        repeat(60) {
            val facts = List(1 + rnd.nextInt(4)) { randomFact(rnd) }
            checkAllPrefixes(render(facts, rnd.nextInt(4), rnd), facts)
        }
    }

    @Test
    fun parserNeverThrowsOnRandomGarbage() {
        val rnd = Random(7)
        val alphabet = "{}[]\",:\\ \nabcfactevidencecategory`\u201C\u201D"
        repeat(2000) {
            val s = String(CharArray(rnd.nextInt(200)) { alphabet[rnd.nextInt(alphabet.length)] })
            assertTrue(FactParser.anyFacts(s, "x", "s", 1).all { it.evidence.isNotBlank() })
            FactParser.json(s)
            FactParser.records(s)
        }
    }

    @Test
    fun unicodeAndEscapedQuotesSurvive() {
        val f = Exp("emotion", "Client", "Client said \"fine\" but looked tired \u2014 caf\u00E9 \u201Cquiet\u201D.", "I said \"fine\" \u2019cause it's easier")
        assertEquals(listOf(f), FactParser.anyFacts(obj(f), "x", "s", 1).map { tuple(it) })
    }

    @Test
    fun curlyQuotesAsDelimitersYieldNothingRatherThanGarbage() {
        val text = "{\u201Ccategory\u201D:\u201Cwork\u201D,\u201Cfact\u201D:\u201CClient has a long day.\u201D,\u201Cevidence\u201D:\u201Clong day indeed\u201D}"
        assertEquals(0, FactParser.anyFacts(text, "x", "s", 1).size)
    }

    @Test
    fun runOnKeysSplitIntoSeparateFactsEvenWhenCutMidQuote() {
        val second = pairs(e2)
        val text = pairs(e1) + "," + second.substring(0, second.length - 8) // second evidence cut inside its quote
        assertEquals(listOf(e1), FactParser.anyFacts(text, "x", "s", 3).map { tuple(it) })
    }

    @Test
    fun dropsFactsWithoutAQuoteOrWithTooShortFields() {
        assertEquals(0, FactParser.anyFacts("{\"category\":\"work\",\"fact\":\"Client has a senior role.\"}", "x", "s", 1).size)
        assertEquals(0, FactParser.anyFacts("{\"fact\":\"Client has a senior role.\",\"evidence\":\"    \"}", "x", "s", 1).size)
        assertEquals(0, FactParser.anyFacts("{\"fact\":\"short\",\"evidence\":\"long enough quote\"}", "x", "s", 1).size)
    }

    @Test
    fun validBoundaries() {
        fun f(fact: String, ev: String) = Fact("i", "other", "client", fact, ev, null)
        assertTrue(FactParser.valid(f("x".repeat(10), "y".repeat(5))))
        assertFalse(FactParser.valid(f("x".repeat(9), "y".repeat(5))))
        assertFalse(FactParser.valid(f("x".repeat(10), "y".repeat(4))))
    }

    @Test
    fun allCategoriesAndAliasesMapToValidCategories() {
        for (c in FactSchemas.CATEGORIES) {
            assertEquals(c, FactParser.category(c))
            assertEquals(c, FactParser.category(c.uppercase()))
            assertEquals(c, FactParser.category("  $c\t"))
        }
        assertEquals("sleep_substance", FactParser.category("Sleep Substance"))
        val aliases = mapOf(
            "sleep" to "sleep_substance", "Sleeping" to "sleep_substance", "alcohol" to "sleep_substance", "Alcohol use" to "sleep_substance",
            "substance_use" to "sleep_substance", "family_member" to "family", "Families" to "family", "relationships" to "relationship",
            "Relational" to "relationship", "emotions" to "emotion", "feelings" to "emotion", "feel" to "emotion",
        )
        for ((raw, want) in aliases) assertEquals(raw, want, FactParser.category(raw))
    }

    @Test
    fun unknownBlankAndRandomCategoriesFallBackToOther() {
        for (raw in listOf("", "   ", "\n", "banana", "xwork", "?", "null", "\u00E9motion")) assertEquals(raw, "other", FactParser.category(raw))
        val rnd = Random(99)
        repeat(1000) {
            val raw = String(CharArray(rnd.nextInt(12)) { (rnd.nextInt(95) + 32).toChar() })
            val c = FactParser.category(raw)
            assertTrue("'$raw' -> '$c'", c in FactSchemas.CATEGORIES)
            assertEquals(c, FactParser.category(c))
        }
    }

    @Test
    fun factsAreTrimmedIdsSequentialAndContextPropagated() {
        val text = "{\"category\":\"Work\",\"subject\":\"  \",\"fact\":\"  Client has a steady job.  \",\"evidence\":\"  steady job here  \"}\n" +
            "{\"category\":\"nonsense\",\"fact\":\"   \",\"evidence\":\"ignored because the fact is blank\"}\n" +
            "{\"category\":\"goal\",\"subject\":\"Client\",\"fact\":\"Client wants to sleep earlier.\",\"evidence\":\"sleep earlier\"}"
        val got = FactParser.anyFacts(text, "p-", "sess9", 4)
        assertEquals(2, got.size)
        assertEquals("Client has a steady job.", got[0].fact)
        assertEquals("steady job here", got[0].evidence)
        assertEquals("client", got[0].subject) // blank subject defaults
        assertEquals("work", got[0].category)
        assertEquals(listOf("p-1", "p-2"), got.map { it.id }) // the blank-fact entry does not consume an id
        assertTrue(got.all { it.session == "sess9" && it.chunk == 4 && it.time == null })
        assertEquals("client", FactParser.anyFacts("{\"fact\":\"Client has a steady job.\",\"evidence\":\"steady job\"}", "a", "s", 1)[0].subject)
    }

    @Test
    fun jsonStripsFencesAndRepairsTruncation() {
        val inner = "{\"facts\":[${obj(e1)},${obj(e2)}]}"
        assertEquals(2, FactParser.json("```json\n$inner\n```")!!.getJSONArray("facts").length())
        assertEquals(2, FactParser.json("Here you go: $inner trailing words")!!.getJSONArray("facts").length())
        val cut = "{\"facts\":[${obj(e1)},{\"category\":\"coping\",\"subject\":\"Cl"
        assertEquals(1, FactParser.json(cut)!!.getJSONArray("facts").length())
        assertNull(FactParser.json("no braces at all"))
        assertNull(FactParser.json("{\"facts\":[{\"category\":\"wo"))
        assertNull(FactParser.json(""))
    }

    @Test
    fun factsFromJsonObject() {
        val o = JSONObject(
            "{\"facts\":[{\"category\":\"work\",\"subject\":\"\",\"fact\":\" Client works nights. \",\"evidence\":\" nights \",\"time\":\"last year\"}," +
                "{\"fact\":\"\"},{\"fact\":\"Client has a dog.\",\"evidence\":\"my dog\",\"time\":\"\"}]}",
        )
        val got = FactParser.facts(o, "j", "s1")
        assertEquals(listOf("j1", "j3"), got.map { it.id }) // id is the array position
        assertEquals("Client works nights.", got[0].fact)
        assertEquals("nights", got[0].evidence)
        assertEquals("client", got[0].subject)
        assertEquals("last year", got[0].time)
        assertNull(got[1].time)
        assertTrue(got.all { it.session == "s1" })
        assertEquals(emptyList<Fact>(), FactParser.facts(JSONObject("{}"), "j", "s"))
    }

    @Test
    fun cleanTimeAndEvidenceHolds() {
        assertNull(FactParser.cleanTime("  "))
        for (n in listOf("N/A", "na", "None", "NULL", " unknown ", "-")) assertNull(n, FactParser.cleanTime(n))
        assertEquals("March 2020", FactParser.cleanTime("  March 2020 "))
        fun f(ev: String) = Fact("i", "other", "c", "some fact text", ev, null)
        val src = "Well, I go for a walk -- when it gets LOUD, you know."
        assertTrue(FactParser.evidenceHolds(f("I go for a walk when it gets loud"), src))
        assertFalse(FactParser.evidenceHolds(f("I go for a run when it gets loud"), src))
        assertFalse(FactParser.evidenceHolds(f("   "), src))
    }

    @Test
    fun factJsonRoundTrip() {
        val f = Fact("a1", "risk", "Client", "Client has a plan.", "a quote", null, "s", 3)
        assertEquals(f, Fact.fromJson(JSONObject(f.toJson().toString())))
        val g = f.copy(time = "2020")
        assertEquals(g, Fact.fromJson(JSONObject(g.toJson().toString())))
    }
}
