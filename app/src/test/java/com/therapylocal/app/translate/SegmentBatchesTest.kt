package com.therapylocal.app.translate

import com.therapylocal.app.translate.SegmentBatches.Item
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SegmentBatchesTest {
    private fun item(id: Int, words: Int) = Item(id, List(words) { "w$it" }.joinToString(" "))

    @Test
    fun batchesAreConsecutiveAndHoldAboutTheWordLimit() {
        val items = listOf(item(1, 150), item(2, 150), item(3, 150), item(4, 50), item(5, 300))
        val batches = SegmentBatches.batches(items, 400)
        assertEquals(listOf(listOf(1, 2), listOf(3, 4), listOf(5)), batches.map { b -> b.map { it.id } })
    }

    @Test
    fun aTextLongerThanTheLimitIsABatchOfItsOwn() {
        val batches = SegmentBatches.batches(listOf(item(1, 10), item(2, 900), item(3, 10)), 400)
        assertEquals(listOf(listOf(1), listOf(2), listOf(3)), batches.map { b -> b.map { it.id } })
        assertTrue(SegmentBatches.batches(emptyList(), 400).isEmpty())
    }

    @Test
    fun theRequestIsOneMarkedLinePerTextWithLineBreaksCollapsed() {
        val request = SegmentBatches.request(listOf(Item(3, "ahoj\nsvete"), Item(4, "  druhy \r\n\r\n radek  ")))
        assertEquals("[3] ahoj svete\n[4] druhy radek", request)
    }

    @Test
    fun aReplyInOrderIsParsed() {
        assertEquals(mapOf(1 to "Hello", 2 to "World"), SegmentBatches.parse("[1] Hello\n[2] World", listOf(1, 2)))
    }

    @Test
    fun aReorderedReplyIsParsedById() {
        assertEquals(mapOf(1 to "a", 2 to "b", 3 to "c"), SegmentBatches.parse("[3] c\n[1] a\n[2] b", listOf(1, 2, 3)))
    }

    @Test
    fun missingItemsAreLeftOut() {
        assertEquals(mapOf(1 to "a", 3 to "c"), SegmentBatches.parse("[1] a\n[3] c", listOf(1, 2, 3)))
    }

    @Test
    fun linesWithoutAMarkerContinueTheTextBeforeIt() {
        assertEquals(mapOf(1 to "first part second part", 2 to "b"), SegmentBatches.parse("[1] first part\nsecond part\n[2] b", listOf(1, 2)))
    }

    @Test
    fun junkIsIgnored() {
        val reply = "Sure, here is the translation:\n```\n[1] a\n```\n\n[2]\n[3] c\n"
        // Text before the first marker and the fences go; an id with no text is missing.
        assertEquals(mapOf(1 to "a", 3 to "c"), SegmentBatches.parse(reply, listOf(1, 2, 3)))
    }

    @Test
    fun anIdThatWasNotAskedForIsIgnoredWithItsContinuationLines() {
        assertEquals(mapOf(1 to "a"), SegmentBatches.parse("[1] a\n[9] stray\nmore stray", listOf(1, 2)))
    }

    @Test
    fun aRepeatedIdKeepsTheFirstText() {
        assertEquals(mapOf(1 to "first"), SegmentBatches.parse("[1] first\n[1] second", listOf(1)))
    }

    @Test
    fun markersWithSpacesAreAccepted() {
        assertEquals(mapOf(12 to "x"), SegmentBatches.parse("  [12]   x  ", listOf(12)))
        assertTrue(SegmentBatches.parse("", listOf(1)).isEmpty())
    }
}

class BatchTranslatorTest {
    private val items = (1..4).map { Item(it, "veta $it") }

    /** A fake model: answers every [n] line of the request with "en n" except the ids in [drop], and counts calls. */
    private class Fake(val drop: Set<Int> = emptySet(), val reply: ((String) -> String?)? = null) {
        val requests = ArrayList<String>()
        fun ask(user: String, @Suppress("UNUSED_PARAMETER") maxOut: Int): String? {
            requests += user
            reply?.let { return it(user) }
            return user.lines().mapNotNull { Regex("^\\[(\\d+)]").find(it)?.groupValues?.get(1)?.toInt() }.filter { it !in drop }.joinToString("\n") { "[$it] en $it" }
        }
    }

    @Test
    fun aGoodModelTranslatesEverythingInOneCall() {
        val fake = Fake()
        val r = BatchTranslator(fake::ask, 400, 2).run(items)
        assertEquals(1, r.calls)
        assertEquals(0, r.fallbacks)
        assertEquals((1..4).associateWith { "en $it" }, r.translated)
    }

    @Test
    fun missingItemsAreAskedAgainOneByOneOnce() {
        val fake = Fake(drop = setOf(2, 3)).let { f ->
            var single = 0
            Fake(reply = { user ->
                if (user.lines().size > 1) f.ask(user, 0) else { single++; "[${Regex("\\[(\\d+)]").find(user)!!.groupValues[1]}] again" }
            })
        }
        val r = BatchTranslator(fake::ask, 400, 2).run(items)
        assertEquals(1 + 2, r.calls)
        assertEquals(0, r.fallbacks)
        assertEquals("again", r.translated[2])
        assertEquals("en 1", r.translated[1])
    }

    @Test
    fun whatStillFailsIsCountedAsAFallbackAndLeftOut() {
        val fake = Fake(drop = setOf(2))
        val r = BatchTranslator(fake::ask, 400, 2).run(items)
        assertEquals(1, r.fallbacks)
        assertTrue(2 !in r.translated)
        assertEquals(3, r.translated.size)
        // The batch, then the single retry of item 2 (which the fake drops again).
        assertEquals(2, r.calls)
    }

    @Test
    fun aFailedCallIsCountedAndEveryItemOfItIsTriedAlone() {
        val r = BatchTranslator({ _, _ -> null }, 400, 2).run(items)
        assertEquals(1 + 4, r.calls)
        assertEquals(5, r.failedCalls)
        assertEquals(4, r.fallbacks)
        assertTrue(r.translated.isEmpty())
    }

    @Test
    fun batchesAreSentSeparatelyAndProgressAndStopAreCalled() {
        val long = (1..3).map { Item(it, List(300) { "w" }.joinToString(" ")) }
        var stops = 0
        val seen = ArrayList<Int>()
        val fake = Fake()
        val r = BatchTranslator(fake::ask, 400, 2, checkStop = { stops++ }).run(long, progress = { done, _ -> seen += done })
        assertEquals(3, r.calls)
        assertEquals(3, stops)
        assertEquals(listOf(0, 1, 2, 3), seen)
    }

    @Test
    fun aStopThrownByTheCheckEndsTheRun() {
        var thrown = false
        try {
            BatchTranslator({ _, _ -> "[1] x" }, 400, 2, checkStop = { throw IllegalStateException("stop") }).run(items)
        } catch (_: IllegalStateException) { thrown = true }
        assertTrue(thrown)
    }

    @Test
    fun theReplyLimitFollowsTheRequestSize() {
        val limits = ArrayList<Int>()
        BatchTranslator({ _, maxOut -> limits += maxOut; "[1] x" }, 400, 2).run(listOf(Item(1, "kratky")))
        BatchTranslator({ _, maxOut -> limits += maxOut; "[1] x" }, 400, 2).run(listOf(Item(1, List(300) { "slovo" }.joinToString(" "))))
        assertEquals(BatchTranslator.MIN_OUTPUT_TOKENS, limits[0])
        assertTrue(limits[1] > 500)
    }
}

class LocalizePlanTest {
    @Test
    fun aSingleLineTextIsOneItemAndComesBackWhole() {
        val plan = LocalizePlan.of(listOf("Heading one", "Content one"))
        assertEquals(listOf(1 to "Heading one", 2 to "Content one"), plan.items.map { it.id to it.text })
        val out = plan.assemble(mapOf(1 to "Nadpis", 2 to "Obsah"))
        assertEquals("Nadpis", out[Display.sha("Heading one")])
        assertEquals("Obsah", out[Display.sha("Content one")])
    }

    @Test
    fun aListIsTranslatedLineByLineAndKeepsItsShape() {
        val text = "- first\n\n- second"
        val plan = LocalizePlan.of(listOf(text))
        assertEquals(2, plan.items.size)
        val out = plan.assemble(mapOf(1 to "- prvni", 2 to "- druhy"))
        assertEquals("- prvni\n\n- druhy", out[Display.sha(text)])
    }

    @Test
    fun aTextWithALineMissingIsNotStoredAndShowsInEnglish() {
        val plan = LocalizePlan.of(listOf("a\nb", "c"))
        val out = plan.assemble(mapOf(1 to "A", 3 to "C"))
        assertTrue(Display.sha("a\nb") !in out)
        assertEquals("C", out[Display.sha("c")])
    }

    @Test
    fun blankAndRepeatedTextsAreLeftOut() {
        val plan = LocalizePlan.of(listOf("x", " ", "x", ""))
        assertEquals(1, plan.items.size)
        assertTrue(LocalizePlan.of(emptyList()).isEmpty)
    }
}

class DisplayTest {
    @Test
    fun aTranslatedTextIsShownAndAnyOtherIsShownAsStored() {
        val d = Display(mapOf(Display.sha("Sleep") to "Spanek"))
        assertEquals("Spanek", d.text("Sleep"))
        assertEquals("Work", d.text("Work"))
    }

    @Test
    fun noTargetLanguageMeansNoLookup() {
        var asked = false
        val d = Display.of(null, listOf("a")) { _, _ -> asked = true; emptyMap() }
        assertTrue(!asked)
        assertEquals("a", d.text("a"))
        assertTrue(d.isEmpty)
    }

    @Test
    fun theLookupGetsTheHashesOfTheNonBlankTexts() {
        var got: Collection<String> = emptyList()
        var lang = ""
        Display.of("cs", listOf("a", " ", "b")) { shas, l -> got = shas; lang = l; emptyMap() }
        assertEquals(listOf(Display.sha("a"), Display.sha("b")), got.toList())
        assertEquals("cs", lang)
    }
}
