package com.therapylocal.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The chunk texts below are the real Gemma output observed on the phone for the 56 s test recording
 * (public-domain LibriSpeech sentences from https://huggingface.co/csukuangfj/sherpa-onnx-whisper-base/tree/main/test_wavs),
 * including the mistakes that broke the first merge: "deserted" against "dishonored" inside an overlap, a chunk cut mid-sentence,
 * and the instruction echoed into the transcript.
 *
 * The recording says sentence A ("after early nightfall ...") at the start and the end, and sentence B ("god as a direct
 * consequence ...") twice in the middle. So a correct merge keeps each of those phrases exactly twice: once per time it was spoken,
 * and not a third time because a 5 s overlap repeated it.
 */
class TranscriptMergeTest {
    private val c0 = "After early nightfall, the yellow lamps would light up here and there, the squalid quarter of the Brofles. " +
        "God, as a direct consequence of the sin which man thus punished, had given her a lovely child, whose place was on that " +
        "same deserted bosom to connect her parent forever with the race and Transcribe the speech in this audio verbatim."
    private val c1 = "on that same dishonored bosom to connect her parent forever with the race and descent of mortals and to be " +
        "finally a blessed soul in heaven. yet these thoughts affected hester prine less with hope than apprehension. " +
        "god as a direct consequence of the sin which she"
    private val c2 = "God, as a direct consequence of the sin which man thus punished, had given her a lovely child whose place was " +
        "on that same dishonored bosom to connect her parent forever with the race and descent of mortals and to be find a " +
        "blessed soul in heaven."
    private val c3 = "and to be finally a blessed soul in heaven after early nightfall the yellow lamps would light up there the " +
        "squalid quarter of the broffles"

    private fun words(s: String) = s.lowercase().filter { it.isLetterOrDigit() || it == ' ' }.split(' ').filter { it.isNotEmpty() }

    private fun count(text: String, phrase: String): Int {
        val p = words(phrase)
        return words(text).windowed(p.size).count { it == p }
    }

    @Test
    fun stripsEchoedPrompt() {
        val clean = TranscriptClean.stripPromptEcho(c0)
        assertFalse(clean.contains("Transcribe", ignoreCase = true))
        assertTrue(clean.endsWith("race and"))
    }

    @Test
    fun overlapWordsAreNotRepeatedAcrossTheSeams() {
        val merged = TranscriptMerge.merge(listOf(TranscriptClean.stripPromptEcho(c0), c1, c2, c3))
        assertEquals("sentence A is spoken twice", 2, count(merged.text, "yellow lamps would light up"))
        assertEquals("sentence B is spoken twice", 2, count(merged.text, "a direct consequence of the sin which"))
        assertEquals("the end of sentence B is spoken twice", 2, count(merged.text, "blessed soul in heaven"))
        assertTrue("every seam found an overlap: ${merged.removedWords}", merged.removedWords.drop(1).all { it > 0 })
    }

    @Test
    fun stitchesInTheMiddleSoRaggedChunkEdgesDoNotSurvive() {
        val merged = TranscriptMerge.merge(listOf(TranscriptClean.stripPromptEcho(c0), c1, c2, c3))
        // chunk 1 ends on the garbled "...the sin which she" and chunk 2 has the correct "...the sin which man thus punished".
        assertFalse("ragged end of chunk 1 survived: ${merged.text}", words(merged.text).windowed(3).any { it == listOf("which", "she", "man") })
        // sentence B is spoken twice, so the correct phrase appears exactly twice (once per utterance)
        assertEquals(2, count(merged.text, "of the sin which man thus punished"))
    }

    @Test
    fun unrelatedTextsAreJustJoined() {
        val r = TranscriptMerge.merge(listOf("the cat sat on the mat", "a completely different sentence about rockets"))
        assertEquals(listOf(0, 0), r.removedWords)
        assertEquals("the cat sat on the mat a completely different sentence about rockets", r.text)
    }

    @Test
    fun identicalOverlapIsRemoved() {
        val r = TranscriptMerge.merge(listOf("we walked to the old stone bridge and", "to the old stone bridge and then crossed the river"))
        assertEquals("we walked to the old stone bridge and then crossed the river", r.text)
    }

    // ---------------------------------------------------------------------------------------------------------------------
    // Edge cases and invariants. norm() ignores case and punctuation, like the production alignment does.
    // ---------------------------------------------------------------------------------------------------------------------

    private fun norm(s: String) = s.split(Regex("\\s+")).filter { it.isNotEmpty() }.map { w -> w.lowercase().filter { it.isLetterOrDigit() } }

    private fun wordCount(s: String) = s.split(Regex("\\s+")).count { it.isNotEmpty() }

    /** Distinct pseudo-words (no two within one edit of each other), so the only overlaps are the ones the test builds. */
    private fun vocabulary(rnd: Random, n: Int): List<String> {
        val out = ArrayList<String>()
        while (out.size < n) {
            val w = String(CharArray(rnd.nextInt(6, 10)) { 'a' + rnd.nextInt(26) })
            if (out.none { withinOneEdit(it, w) }) out += w
        }
        return out
    }

    private fun withinOneEdit(a: String, b: String): Boolean {
        if (a == b) return true
        if (a.length == b.length) return a.indices.count { a[it] != b[it] } <= 1
        val (s, l) = if (a.length < b.length) a to b else b to a
        return l.length - s.length == 1 && l.indices.any { l.removeRange(it, it + 1) == s }
    }

    @Test
    fun emptyAndBlankChunksAreHarmless() {
        assertEquals("", TranscriptMerge.merge(emptyList()).text)
        assertEquals(emptyList<Int>(), TranscriptMerge.merge(emptyList()).removedWords)
        assertEquals("", TranscriptMerge.merge(listOf("")).text)
        val r = TranscriptMerge.merge(listOf("", "   \n\t ", ""))
        assertEquals("", r.text)
        assertEquals(listOf(0, 0, 0), r.removedWords)
        assertEquals("a b c", TranscriptMerge.merge(listOf("", "a b c")).text)
        assertEquals("a b c", TranscriptMerge.merge(listOf("a b c", "")).text)
        assertEquals("a b c d e f", TranscriptMerge.merge(listOf("a b c", " ", "", "d e f")).text)
    }

    @Test
    fun whitespaceIsNormalisedToSingleSpaces() {
        assertEquals("one two three", TranscriptMerge.merge(listOf("  one \n two\t\tthree  ")).text)
    }

    @Test
    fun chunkAfterAnEmptyFirstChunkStillStartsTheText() {
        val r = TranscriptMerge.merge(listOf("", "the quick brown fox jumps over", "brown fox jumps over the lazy dog"))
        assertEquals(listOf("the", "quick", "brown", "fox", "jumps", "over", "the", "lazy", "dog"), norm(r.text))
    }

    @Test
    fun exactOverlapIsRemovedWholeAndCountedInRemovedWords() {
        val a = "alpha bravo charlie delta echo foxtrot golf hotel"
        val b = "echo foxtrot golf hotel india juliet kilo"
        val r = TranscriptMerge.merge(listOf(a, b))
        assertEquals("alpha bravo charlie delta echo foxtrot golf hotel india juliet kilo", r.text)
        assertEquals(listOf(0, 4), r.removedWords)
    }

    @Test
    fun caseAndPunctuationDifferencesDoNotPreventDeduplication() {
        val r = TranscriptMerge.merge(listOf("He said: \"Well, the QUICK brown fox!\"", "the quick, brown fox... jumped over it"))
        assertEquals(listOf("he", "said", "well", "the", "quick", "brown", "fox", "jumped", "over", "it"), norm(r.text))
    }

    @Test
    fun overlapOfThreeWordsIsTheMinimumThatIsRemoved() {
        val r = TranscriptMerge.merge(listOf("we met near the old harbour wall", "old harbour wall and then left"))
        assertEquals(listOf("we", "met", "near", "the", "old", "harbour", "wall", "and", "then", "left"), norm(r.text))
        assertEquals(3, r.removedWords[1])
    }

    @Test
    fun overlapOfOneOrTwoWordsIsNotTrustedAndTextIsJustJoined() {
        val one = TranscriptMerge.merge(listOf("we met near the harbour", "harbour and then left"))
        assertEquals("we met near the harbour harbour and then left", one.text)
        assertEquals(listOf(0, 0), one.removedWords)
        val two = TranscriptMerge.merge(listOf("we met near the old harbour", "old harbour and then left"))
        assertEquals("we met near the old harbour old harbour and then left", two.text)
        assertEquals(listOf(0, 0), two.removedWords)
    }

    @Test
    fun theSeamIsStitchedInTheMiddleOfTheOverlap() {
        // Contract (class doc): keep the text so far up to the middle of the aligned region and the next chunk from there.
        // Case differences let us see which side each overlap word came from: chunk A is lower case, chunk B upper case.
        val rnd = Random(2)
        for (k in 3..14) {
            val ov = vocabulary(rnd, k)
            val head = listOf("intro", "words", "before", "here")
            val tail = listOf("outro", "words", "after", "there")
            val a = (head + ov).joinToString(" ")
            val b = (ov.map { it.uppercase() } + tail).joinToString(" ")
            val merged = TranscriptMerge.merge(listOf(a, b)).text.split(" ")
            val expected = head + ov.take(k / 2) + ov.drop(k / 2).map { it.uppercase() } + tail
            assertEquals("overlap of $k words", expected, merged)
        }
    }

    @Test
    fun aWeakAlignmentIsTreatedAsNoOverlap() {
        // 3 equal words and 1 different word align with a score below the threshold ("too weak, the texts are simply joined").
        val r = TranscriptMerge.merge(listOf("we met at the old harbour wall", "the old harbour gate was closed"))
        assertEquals("we met at the old harbour wall the old harbour gate was closed", r.text)
        assertEquals(listOf(0, 0), r.removedWords)
    }

    @Test
    fun aOneEditVariantInAShortOverlapStillCountsAsAMatch() {
        // only 3 overlap words; "harbour"/"harbor" must count as equal or the alignment is too weak to be used
        val r = TranscriptMerge.merge(listOf("we met at the old harbour wall", "the old harbor wall was closed"))
        assertEquals(listOf("we", "met", "at", "the", "old"), norm(r.text).take(5))
        assertEquals(listOf("wall", "was", "closed"), norm(r.text).takeLast(3))
        assertEquals(1, norm(r.text).count { it == "wall" })
        assertEquals(1, norm(r.text).count { it == "old" })
    }

    @Test
    fun identicalChunksCollapseToOneCopy() {
        val t = "the committee reviewed every proposal carefully before lunch"
        val r = TranscriptMerge.merge(listOf(t, t))
        assertEquals(norm(t), norm(r.text))
        assertEquals(wordCount(t), r.removedWords[1])
    }

    @Test
    fun aWordWithOneEditStillAlignsAndNoWordIsLost() {
        val r = TranscriptMerge.merge(listOf("they walked into the quarter of the broffles", "the quarter of the brofles and kept going"))
        val words = norm(r.text)
        assertEquals(1, words.count { it == "quarter" })
        assertTrue(words.first() == "they" && words.last() == "going")
        assertEquals(1, words.count { it.startsWith("brof") })
    }

    @Test
    fun unicodeWordsAlignLikeAsciiOnes() {
        val r = TranscriptMerge.merge(listOf("Wir gingen früh über die große Brücke", "ÜBER die Große brücke und dann nach Hause"))
        assertEquals(listOf("wir", "gingen", "früh", "über", "die", "große", "brücke", "und", "dann", "nach", "hause"), norm(r.text))
        val ru = TranscriptMerge.merge(listOf("мы пошли через старый каменный мост", "через старый каменный мост и дальше"))
        assertEquals(norm("мы пошли через старый каменный мост и дальше"), norm(ru.text))
        val cjk = TranscriptMerge.merge(listOf("今日は 天気が いい です ね", "天気が いい です ね 本当に"))
        assertEquals(norm("今日は 天気が いい です ね 本当に"), norm(cjk.text))
    }

    @Test
    fun tokensWithoutLettersNeverCountAsMatches() {
        // punctuation-only and emoji-only tokens normalise to "" and must not be treated as equal
        val a = "well ... - - - 😀 😀 😀"
        val b = "- - - 😀 😀 😀 then"
        val r = TranscriptMerge.merge(listOf(a, b))
        assertEquals(listOf(0, 0), r.removedWords)
        assertEquals(wordCount(a) + wordCount(b), wordCount(r.text))
    }

    @Test
    fun outOfOrderChunksAreJoinedInTheGivenOrderWithoutLosingWords() {
        val first = "alpha bravo charlie delta echo foxtrot golf"
        val second = "echo foxtrot golf hotel india juliet kilo"
        val inOrder = TranscriptMerge.merge(listOf(first, second))
        val reversed = TranscriptMerge.merge(listOf(second, first))
        assertEquals(norm("alpha bravo charlie delta echo foxtrot golf hotel india juliet kilo"), norm(inOrder.text))
        // Reversed: the end of `second` does not continue into `first`, so the texts are joined as-is (nothing dropped, nothing reordered).
        assertEquals(norm(second) + norm(first), norm(reversed.text))
        assertEquals(listOf(0, 0), reversed.removedWords)
    }

    @Test
    fun aPhraseSpokenTwiceAwayFromTheSeamSurvives() {
        val a = "i said good morning to everyone and then good morning again to everyone at the door"
        val b = "again to everyone at the door she smiled"
        val r = norm(TranscriptMerge.merge(listOf(a, b)).text)
        assertEquals(2, r.windowed(2).count { it == listOf("good", "morning") })
        assertEquals(norm("i said good morning to everyone and then good morning again to everyone at the door she smiled"), r)
    }

    @Test
    fun exactOverlapsOfRandomLengthReproduceTheOriginalSequence() {
        val rnd = Random(12345)
        repeat(200) { trial ->
            val total = rnd.nextInt(40, 120)
            val vocab = vocabulary(rnd, total)
            val chunks = ArrayList<List<String>>()
            val overlaps = ArrayList<Int>()
            var start = 0
            while (true) {
                val end = minOf(total, start + rnd.nextInt(15, 40))
                chunks += vocab.subList(start, end)
                if (end == total) break
                val ov = rnd.nextInt(4, 12)
                overlaps += ov
                start = end - ov
            }
            val r = TranscriptMerge.merge(chunks.map { it.joinToString(" ") })
            assertEquals("trial $trial", vocab, r.text.split(" "))
            assertEquals("trial $trial: removed words are exactly the duplicated ones", overlaps, r.removedWords.drop(1))
            assertEquals(0, r.removedWords[0])
        }
    }

    @Test
    fun noisyOverlapsNeverLoseWordsOutsideTheOverlapOrDuplicateTheOverlap() {
        val rnd = Random(777)
        repeat(300) { trial ->
            val vocab = vocabulary(rnd, 60)
            val ov = rnd.nextInt(6, 14)
            val aEnd = rnd.nextInt(25, 40)
            val a = vocab.subList(0, aEnd)
            val b = vocab.subList(aEnd - ov, 60).toMutableList()
            // recogniser noise confined to the overlap (never its first or last word)
            repeat(rnd.nextInt(0, 3)) { b[rnd.nextInt(1, ov - 1)] = "zz" + vocab[rnd.nextInt(60)] + "qq" }
            val aText = a.joinToString(" ")
            val bText = b.joinToString(" ")
            val r = TranscriptMerge.merge(listOf(aText, bText))
            val merged = r.text.split(" ")
            val before = vocab.subList(0, aEnd - ov) // spoken only in chunk a
            val after = vocab.subList(aEnd, 60) // spoken only in chunk b
            assertEquals("trial $trial starts with the a-only words", before, merged.take(before.size))
            assertEquals("trial $trial ends with the b-only words", after, merged.takeLast(after.size))
            val region = merged.subList(before.size, merged.size - after.size)
            assertTrue("trial $trial: the overlap appears about once, region=$region", region.size in (ov - 2)..(ov + 1))
            assertEquals(wordCount(aText) + wordCount(bText) - r.removedWords.sum(), merged.size)
        }
    }

    @Test
    fun wordBookkeepingHoldsForArbitraryTexts() {
        val rnd = Random(31337)
        val small = listOf("the", "a", "of", "and", "to", "river", "bridge", "walked", "Grüße", "...", "!", "stone", "old", "house", "morning")
        repeat(500) { trial ->
            val texts = List(rnd.nextInt(0, 6)) {
                List(rnd.nextInt(0, 30)) { small[rnd.nextInt(small.size)] }.joinToString(if (rnd.nextBoolean()) " " else "  \n")
            }
            val r = TranscriptMerge.merge(texts)
            assertEquals("trial $trial", texts.size, r.removedWords.size)
            assertTrue(r.removedWords.all { it >= 0 })
            assertEquals("trial $trial", texts.sumOf { wordCount(it) } - r.removedWords.sum(), wordCount(r.text))
            assertFalse(r.text.startsWith(" ") || r.text.endsWith(" ") || r.text.contains("  "))
        }
    }

    @Test
    fun resultIsTheConcatenationWhenNoSeamOverlaps() {
        val rnd = Random(5)
        repeat(100) {
            val v = vocabulary(rnd, 60)
            val chunks = listOf(v.subList(0, 20), v.subList(20, 40), v.subList(40, 60))
            val r = TranscriptMerge.merge(chunks.map { it.joinToString(" ") })
            assertEquals(v.joinToString(" "), r.text)
            assertEquals(listOf(0, 0, 0), r.removedWords)
        }
    }

    @Test
    fun veryLongInputsAreHandled() {
        val v = vocabulary(Random(8), 1500)
        val chunks = (0 until 1500 step 100).map { s -> v.subList(maxOf(0, s - 8), minOf(1500, s + 100)).joinToString(" ") }
        assertEquals(v, TranscriptMerge.merge(chunks).text.split(" "))
    }

    @Test
    fun singleChunkIsUnchanged() {
        assertEquals("hello there", TranscriptMerge.merge(listOf("hello there")).text)
    }
}
