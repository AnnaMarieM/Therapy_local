package com.therapylocal.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SegmentBuilderTest {
    private fun chunk(idx: Int, text: String?) = ChunkRow(1, idx, idx * 15_000L, idx * 15_000L + 20_000L, if (idx == 0) 0 else 5_000L, text)

    /** Overlapping chunks as a recogniser hears them: each repeats the last few words of the one before, with a mishearing at the seam. */
    private val chunks = listOf(
        chunk(0, "we moved to the coast in may and my sister ann stayed behind in leeds"),
        chunk(1, "ann stayed behind in leads she says she loves the new flat but misses us"),
        chunk(2, "but misses us a lot and calls every sunday evening after dinner"),
    )

    @Test
    fun segmentsReadInOrderAreTheMergedTranscript() {
        val segs = SegmentBuilder.build(chunks)
        val merged = TranscriptMerge.merge(chunks.map { it.text!! })
        assertEquals(merged.text, segs.joinToString(" ") { it.text })
        assertEquals(listOf(1, 2, 3), segs.map { it.idx })
        // Every seam found its overlap, so no segment repeats the words the chunk before it already has.
        assertTrue(merged.removedWords.drop(1).all { it > 0 })
        assertEquals(1, Regex("ann stayed behind").findAll(segs.joinToString(" ") { it.text }).count())
    }

    @Test
    fun timesStayInsideTheirChunkAndNarrowToTheKeptWords() {
        val segs = SegmentBuilder.build(chunks)
        segs.forEachIndexed { i, s ->
            val c = chunks[i]
            assertTrue("segment $i: $s", s.startMs >= c.startMs && s.endMs <= c.endMs && s.startMs < s.endMs)
        }
        // The first chunk keeps its start; a later one drops its repeated opening words, so its segment starts after the chunk does.
        assertEquals(chunks[0].startMs, segs[0].startMs)
        assertTrue(segs[1].startMs > chunks[1].startMs)
        assertEquals(chunks[2].endMs, segs[2].endMs)
    }

    @Test
    fun rebuildingGivesTheSameSegments() {
        assertEquals(SegmentBuilder.build(chunks), SegmentBuilder.build(chunks.map { it.copy() }))
    }

    @Test
    fun chunksWithoutWordsGiveNoSegmentAndIndicesStayContiguous() {
        val segs = SegmentBuilder.build(listOf(chunk(0, null), chunks[0].copy(idx = 1), chunk(2, "   "), chunk(3, "a completely different topic now")))
        assertEquals(listOf(1, 2), segs.map { it.idx })
        assertEquals("a completely different topic now", segs[1].text)
        assertEquals(emptyList<SegmentRow>(), SegmentBuilder.build(emptyList()))
        assertEquals(emptyList<SegmentRow>(), SegmentBuilder.build(listOf(chunk(0, null), chunk(1, ""))))
    }

    @Test
    fun anEchoedPromptIsNotPartOfTheTranscript() {
        val segs = SegmentBuilder.build(listOf(chunk(0, "my sister lives in leeds Transcribe the speech in this audio verbatim.")))
        assertEquals("my sister lives in leeds", segs.single().text)
    }

    /** The kept ranges are what segments are built on: each chunk keeps one contiguous run of its words, and in order they are the text. */
    @Test
    fun keptRangesOfRandomOverlappingChunksRebuildTheMergedText() {
        val rnd = Random(77)
        val vocab = listOf("we", "went", "to", "the", "sea", "and", "my", "sister", "said", "it", "was", "cold", "but", "fine", "really")
        repeat(400) {
            val story = List(40 + rnd.nextInt(80)) { vocab[rnd.nextInt(vocab.size)] + rnd.nextInt(3) }
            val texts = ArrayList<String>()
            var start = 0
            while (start < story.size) {
                val end = minOf(story.size, start + 10 + rnd.nextInt(15))
                texts += story.subList(start, end).joinToString(" ")
                if (end == story.size) break
                start = end - rnd.nextInt(6) // 0-5 words of overlap
            }
            val r = TranscriptMerge.merge(texts)
            val rebuilt = texts.indices.flatMap { i -> val w = TranscriptMerge.words(texts[i]); if (r.kept[i].isEmpty()) emptyList() else w.subList(r.kept[i].first, r.kept[i].last + 1) }
            assertEquals(r.text, rebuilt.joinToString(" "))
            texts.indices.forEach { i -> assertTrue(r.kept[i].isEmpty() || r.kept[i].last < TranscriptMerge.words(texts[i]).size) }
        }
    }
}
