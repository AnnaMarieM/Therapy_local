package com.therapylocal.app.facts

import com.therapylocal.app.data.SegmentRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Spans, the several-facts-per-sentence rules and the dedupe. The corpus tests read the planted facts of e2e/sittings/scripts (not edited). */
class FactSpansTest {
    private fun seg(idx: Int, text: String, speaker: String?, start: Long = idx * 1000L) = SegmentRow(idx, start, start + 900, text, speaker)

    private val segments = listOf(
        seg(1, "Good morning, how was your week?", "S0"),
        seg(2, "My brother Tom lost his job in March, and since then I send him money every month.", "S1"),
        seg(3, "That sounds heavy. Does he know", "S0"),
        seg(4, "how much it costs you?", "S0"),
        seg(5, "No. I never told him.", "S1"),
    )

    private fun f(heading: String, content: String, evidence: String) = Fact(heading, content, evidence)

    @Test
    fun twoFactsFromOneSentenceKeepTheirOwnQuotesAndTheSameSegment() {
        val reply = """
            {"heading":"Brother Tom lost job","content":"Marek's brother Tom lost his job in March.","evidence":"My brother Tom lost his job in March","scope":"profile","category":"Family"}
            {"heading":"Sends brother money","content":"Marek sends his brother money every month.","evidence":"since then I send him money every month","scope":"profile","category":"Family"}
        """.trimIndent()
        val facts = FactParser.dedupe(FactParser.anyFacts(reply).filter { FactParser.evidenceHolds(it, segments.joinToString(" ") { s -> s.text }) })
        assertEquals(2, facts.size)
        val spans = facts.map { FactParser.segmentSpan(it.evidence, segments) }
        assertEquals(listOf(2 to 2, 2 to 2), spans)
        assertTrue(facts[0].evidence != facts[1].evidence)
    }

    @Test
    fun theSameFactSaidTwiceIsDroppedButDifferentFactsOnOneQuoteAreKept() {
        val a = f("Lost job", "Tom lost his job in March.", "lost his job in March")
        val again = f("Tom lost job", "Tom lost his job in March", "lost his job in March")
        val other = f("Sends money", "Marek sends Tom money every month.", "lost his job in March")
        assertEquals(listOf(a, other), FactParser.dedupe(listOf(a, again, other)))
        // Same content on another quote is still a repeat (overlapping windows can give it twice).
        assertEquals(listOf(a), FactParser.dedupe(listOf(a, a.copy(evidence = "Tom lost his job"))))
    }

    @Test
    fun dedupeComparesCzechWordsNotJustAscii() {
        val a = f("Spí špatně", "Eva se budí ve tři ráno.", "budím se ve tři")
        val b = f("Budí se brzy", "Eva se budí ve čtyři ráno.", "budím se ve tři")
        assertEquals(2, FactParser.dedupe(listOf(a, b)).size)
    }

    @Test
    fun aQuoteInsideOneSegmentGivesThatSegmentTwiceAndACrossingQuoteGivesFirstAndLast() {
        assertEquals(2 to 2, FactParser.segmentSpan("I send him money every month", segments))
        assertEquals(3 to 4, FactParser.segmentSpan("Does he know how much it costs you", segments))
        assertEquals(3, FactParser.segmentOf("Does he know how much it costs you", segments))
        assertNull(FactParser.segmentSpan("not in the transcript", segments))
    }

    @Test
    fun theSpanBecomesTheTimeRangeAndTheRawSpeakerOfItsFirstSegment() {
        val one = FactSpans.of(2, 2, segments)!!
        assertEquals(FactSpan(2, 2, 2000, 2900, "S1"), one)
        val across = FactSpans.of(3, 4, segments)!!
        assertEquals(FactSpan(3, 4, 3000, 4900, "S0"), across)
        assertNull(FactSpans.of(99, 99, segments))
        // A segment without a diarization id has no speaker.
        assertNull(FactSpans.of(1, 1, listOf(seg(1, "x", null)))!!.speaker)
    }

    @Test
    fun theSegmentRangeSurvivesTheExtractCache() {
        val fact = Fact("Lost job", "Tom lost his job in March.", "q", segIdx = 3, segEnd = 4)
        val back = Fact.fromJson(fact.toJson())
        assertEquals(fact, back)
        assertEquals(3 to 4, back.segIdx to back.segEnd)
        // A cached fact without segEnd (written before) reads as a one-segment span.
        assertEquals(5, Fact.fromJson(org.json.JSONObject("""{"heading":"h","content":"c","evidence":"e","seg":5}""")).segEnd)
    }

    // ---- the e2e sittings corpus: planted facts, never edited here ----------------------------------------------------

    private data class Planted(val id: String, val sentence: String, val segIdx: Int, val patient: Boolean)

    private fun load(file: File): Pair<List<SegmentRow>, List<Planted>> {
        val marker = Regex("""\[\[([^|\]]+)\|[^|]*\|(.*?)]]""")
        val segs = ArrayList<SegmentRow>()
        val planted = ArrayList<Planted>()
        var body = false
        for (line in file.readLines()) {
            if (line == "---") { body = true; continue }
            if (!body) continue
            val m = Regex("^([TP]): (.*)$").matchEntire(line) ?: continue
            val idx = segs.size + 1
            val speaker = if (m.groupValues[1] == "T") "S0" else "S1"
            var text = m.groupValues[2]
            for (p in marker.findAll(text)) planted += Planted(p.groupValues[1], p.groupValues[2], idx, speaker == "S1")
            text = marker.replace(text) { it.groupValues[2] }
            segs += SegmentRow(idx, idx * 4000L, idx * 4000L + 3500, text, speaker)
        }
        return segs to planted
    }

    private fun scripts(): List<File> {
        val dir = File("../e2e/sittings/scripts")
        assertTrue("corpus not found at ${dir.absolutePath}", dir.isDirectory)
        return dir.listFiles { f -> f.name.endsWith(".txt") }!!.sortedBy { it.name }.filter { f -> f.readLines().none { it.startsWith("D: ") } }
    }

    @Test
    fun everyPlantedSentenceIsFoundInItsOwnSegmentWithItsTimesAndSpeaker() {
        var checked = 0
        for (file in scripts()) {
            val (segs, planted) = load(file)
            for (p in planted) {
                val evidence = p.sentence.split(' ').take(15).joinToString(" ")
                assertEquals("${file.name} ${p.id}", p.segIdx to p.segIdx, FactParser.segmentSpan(evidence, segs))
                val span = FactSpans.of(p.segIdx, p.segIdx, segs)!!
                assertEquals(p.segIdx * 4000L, span.startMs)
                assertEquals(p.segIdx * 4000L + 3500, span.endMs)
                assertEquals(if (p.patient) "S1" else "S0", span.speaker)
                checked++
            }
        }
        assertTrue("planted facts seen: $checked", checked >= 30)
    }

    @Test
    fun aPlantedSentenceSplitInTwoGivesTwoFactsWithDistinctQuotesInOneSegment() {
        var multi = 0
        for (file in scripts()) {
            val (segs, planted) = load(file)
            for (p in planted) {
                val parts = p.sentence.split(Regex(",\\s+(and|but|a|ale)\\s+|,\\s+")).filter { it.split(' ').size >= 4 }
                if (parts.size < 2) continue
                val facts = parts.take(2).mapIndexed { i, q -> f("h$i", "Fact $i of ${p.id} said plainly.", q.trim().trimEnd('.', ',')) }
                val kept = FactParser.dedupe(facts)
                assertEquals("${file.name} ${p.id}", 2, kept.size)
                assertEquals(kept.map { p.segIdx to p.segIdx }, kept.map { FactParser.segmentSpan(it.evidence, segs) })
                multi++
            }
        }
        assertTrue("sentences with two parts: $multi", multi >= 10)
    }
}
