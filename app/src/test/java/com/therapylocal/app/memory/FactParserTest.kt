package com.therapylocal.app.memory

import com.therapylocal.app.data.SegmentRow
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** All fixtures are synthetic placeholders ("Client", neutral sentences); no real transcripts. */
class FactParserTest {
    private data class Exp(val heading: String, val content: String, val evidence: String)

    private val e1 = Exp("Manages a team", "Client manages a small team at the office.", "I manage a small team here")
    private val e2 = Exp("Walks when stressed", "Client goes for a walk when stressed.", "I go for a walk when it gets loud")
    private val e3 = Exp("Sibling lives nearby", "Sibling lives in another city nearby.", "my sibling lives across town")

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
    private fun obj(e: Exp) = "{\"heading\":\"${esc(e.heading)}\",\"content\":\"${esc(e.content)}\",\"evidence\":\"${esc(e.evidence)}\"}"
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
        return Exp("Head " + phrase(1 + rnd.nextInt(3)), "Client reports " + phrase(4 + rnd.nextInt(4), extra), "I said " + phrase(3 + rnd.nextInt(3), extra))
    }

    private fun tuple(f: Fact) = Exp(f.heading, f.content, f.evidence)

    private fun checkAllPrefixes(r: Rendered, expected: List<Exp>) {
        val full = FactParser.anyFacts(r.text).map { tuple(it) }
        assertEquals("full text must parse completely", expected, full)
        for (n in 0..r.text.length) {
            val got = FactParser.anyFacts(r.text.substring(0, n))
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
        val alphabet = "{}[]\",:\\ \nabcheadingcontentevidence`\u201C\u201D"
        repeat(2000) {
            val s = String(CharArray(rnd.nextInt(200)) { alphabet[rnd.nextInt(alphabet.length)] })
            FactParser.anyFacts(s) // must not throw
            FactParser.json(s)
            FactParser.records(s)
        }
    }

    @Test
    fun unicodeAndEscapedQuotesSurvive() {
        val f = Exp("Says \"fine\"", "Client said \"fine\" but looked tired \u2014 caf\u00E9 \u201Cquiet\u201D.", "I said \"fine\" \u2019cause it's easier")
        assertEquals(listOf(f), FactParser.anyFacts(obj(f)).map { tuple(it) })
    }

    /** Documents current behaviour, not a requirement; see follow-up. Curly quotes used as JSON delimiters are not salvaged. */
    @Test
    fun currentBehaviour_curlyQuotesAsDelimitersYieldNothing() {
        val text = "{\u201Cheading\u201D:\u201CLong day\u201D,\u201Ccontent\u201D:\u201CClient has a long day.\u201D,\u201Cevidence\u201D:\u201Clong day indeed\u201D}"
        assertEquals(0, FactParser.anyFacts(text).size)
    }

    @Test
    fun runOnKeysSplitIntoSeparateFactsEvenWhenCutMidQuote() {
        val second = pairs(e2)
        val text = pairs(e1) + "," + second.substring(0, second.length - 8) // second evidence cut inside its quote
        assertEquals(listOf(e1), FactParser.anyFacts(text).map { tuple(it) })
    }

    @Test
    fun dropsFactsWithoutAHeadingAQuoteOrWithTooShortFields() {
        assertEquals(0, FactParser.anyFacts("{\"heading\":\"Role\",\"content\":\"Client has a senior role.\"}").size)
        assertEquals(0, FactParser.anyFacts("{\"heading\":\"Role\",\"content\":\"Client has a senior role.\",\"evidence\":\"    \"}").size)
        assertEquals(0, FactParser.anyFacts("{\"heading\":\"  \",\"content\":\"Client has a senior role.\",\"evidence\":\"a senior role\"}").size)
        assertEquals(0, FactParser.anyFacts("{\"heading\":\"Role\",\"content\":\"short\",\"evidence\":\"long enough quote\"}").size)
    }

    @Test
    fun validBoundaries() {
        fun f(heading: String, content: String, ev: String) = Fact(heading, content, ev)
        assertTrue(FactParser.valid(f("h", "x".repeat(10), "y".repeat(5))))
        assertFalse(FactParser.valid(f("h", "x".repeat(9), "y".repeat(5))))
        assertFalse(FactParser.valid(f("h", "x".repeat(10), "y".repeat(4))))
        assertFalse(FactParser.valid(f(" ", "x".repeat(10), "y".repeat(5))))
    }

    @Test
    fun factsAreTrimmedAndBlankOnesSkipped() {
        val text = "{\"heading\":\"  Steady job \",\"content\":\"  Client has a steady job.  \",\"evidence\":\"  steady job here  \"}\n" +
            "{\"heading\":\"Nothing\",\"content\":\"   \",\"evidence\":\"ignored because the content is blank\"}\n" +
            "{\"heading\":\"Sleep earlier\",\"content\":\"Client wants to sleep earlier.\",\"evidence\":\"sleep earlier\"}"
        val got = FactParser.anyFacts(text)
        assertEquals(listOf(Fact("Steady job", "Client has a steady job.", "steady job here"), Fact("Sleep earlier", "Client wants to sleep earlier.", "sleep earlier")), got)
        assertTrue(got.all { it.segIdx == 0 })
    }

    @Test
    fun jsonStripsFencesAndRepairsTruncation() {
        val inner = "{\"facts\":[${obj(e1)},${obj(e2)}]}"
        assertEquals(2, FactParser.json("```json\n$inner\n```")!!.getJSONArray("facts").length())
        assertEquals(2, FactParser.json("Here you go: $inner trailing words")!!.getJSONArray("facts").length())
        val cut = "{\"facts\":[${obj(e1)},{\"heading\":\"Walks\",\"content\":\"Cl"
        assertEquals(1, FactParser.json(cut)!!.getJSONArray("facts").length())
        assertNull(FactParser.json("no braces at all"))
        assertNull(FactParser.json("{\"facts\":[{\"heading\":\"wo"))
        assertNull(FactParser.json(""))
    }

    @Test
    fun evidenceHolds() {
        fun f(ev: String) = Fact("h", "some fact text", ev)
        val src = "Well, I go for a walk -- when it gets LOUD, you know."
        assertTrue(FactParser.evidenceHolds(f("I go for a walk when it gets loud"), src))
        assertFalse(FactParser.evidenceHolds(f("I go for a run when it gets loud"), src))
        assertFalse(FactParser.evidenceHolds(f("   "), src))
    }

    @Test
    fun evidenceMapsToTheSegmentOfItsFirstWord() {
        val segs = listOf(SegmentRow(4, 0, 0, "We moved in May."), SegmentRow(5, 0, 0, "My sister, Ann, stayed"), SegmentRow(6, 0, 0, "in Leeds -- she LOVES it."))
        assertEquals(4, FactParser.segmentOf("we moved in may", segs))
        assertEquals(6, FactParser.segmentOf("she loves it", segs))
        // Same normalisation as evidenceHolds: punctuation and case do not matter.
        assertEquals(5, FactParser.segmentOf("My sister Ann stayed!", segs))
        // A quote over a segment boundary belongs to the segment it starts in.
        assertEquals(5, FactParser.segmentOf("Ann, stayed in Leeds", segs))
        assertEquals(4, FactParser.segmentOf("in May. My sister", segs))
        assertNull(FactParser.segmentOf("not in the transcript", segs))
        assertNull(FactParser.segmentOf(" -- ", segs))
        assertNull(FactParser.segmentOf("we moved", emptyList()))
    }

    @Test
    fun verdictReadsTheShapesTheModelProduces() {
        assertEquals(Verdict(false, null), FactParser.verdict("{\"duplicate\": false, \"of\": \"\"}", 3))
        assertEquals(Verdict(true, 1), FactParser.verdict("{\"duplicate\": true, \"of\": \"K2\"}", 3))
        assertEquals(Verdict(true, 2), FactParser.verdict("```json\n{\"duplicate\":\"TRUE\",\"of\":\"3\"}\n```", 3))
        // A duplicate without a usable reference is taken to be of the nearest known fact.
        assertEquals(Verdict(true, 0), FactParser.verdict("{\"duplicate\": true, \"of\": \"K9\"}", 3))
        assertEquals(Verdict(true, 0), FactParser.verdict("{\"duplicate\": true}", 3))
        // No JSON: a bare true/false word still counts.
        assertEquals(Verdict(true, 0), FactParser.verdict("I think this is true.", 2))
        assertNull(FactParser.verdict("{\"duplicate\": \"maybe\"}", 2))
        assertNull(FactParser.verdict("no idea", 2))
        assertNull(FactParser.verdict("", 2))
    }

    @Test
    fun verdictEncodingRoundTripsAndRejectsGarbage() {
        for (v in listOf(Verdict(false, null), Verdict(true, 0), Verdict(true, 7))) assertEquals(v, Verdict.decode(v.encode()))
        assertNull(Verdict.decode("dup:x"))
        assertNull(Verdict.decode(""))
    }

    @Test
    fun factJsonRoundTrip() {
        val f = Fact("Has a plan", "Client has a plan.", "a quote", 3)
        assertEquals(f, Fact.fromJson(JSONObject(f.toJson().toString())))
    }

    @Test
    fun promptVersionFollowsTheTextAndSplitsNeverCollide() {
        assertEquals(Prompts.version("a", "b"), Prompts.version("a", "b"))
        assertNotEquals(Prompts.version("a", "b"), Prompts.version("a", "c"))
        assertNotEquals(Prompts.hash("ab", "c"), Prompts.hash("a", "bc"))
        assertEquals(12, Prompts.version("x").length)
        assertEquals("Hi Ann, part 2", Prompts.fill("Hi {{CLIENT}}, part {{PART}}", mapOf("CLIENT" to "Ann", "PART" to "2")))
    }
}
