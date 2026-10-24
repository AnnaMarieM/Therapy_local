package com.therapylocal.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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

    @Test
    fun singleChunkIsUnchanged() {
        assertEquals("hello there", TranscriptMerge.merge(listOf("hello there")).text)
    }
}
