package com.therapylocal.app.workflow

import com.therapylocal.app.data.Piece
import com.therapylocal.app.data.UtteranceBuilder
import com.therapylocal.app.jobs.Llm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Fast workflow's listening step: the reply format, the retry, and the English that travels with the pieces (docs/AD/AD-22.md). */
class TurnListenerTest {
    private class Hearing(private val replies: List<String>) : Llm {
        var calls = 0
        val heard = ArrayList<Int>()
        override val model = "gemma-4-e2b"
        override val hearing = true
        override fun ask(system: String, user: String, schema: String?, maxOutputTokens: Int, temperature: Double, topK: Int) = error("text call")
        override fun listen(system: String, user: String, pcm: ShortArray, maxOutputTokens: Int, temperature: Double, topK: Int): String {
            heard += pcm.size
            return replies[minOf(calls++, replies.lastIndex)]
        }
        override fun close() {}
    }

    @Test
    fun aCzechReplyHasTheTranscriptAndTheEnglish() {
        val t = TurnListener.parse("CZECH: Že jí a dítěti ublížím.\nENGLISH: That I will hurt her and the child.", "cs")
        assertEquals(TurnText("Že jí a dítěti ublížím.", "That I will hurt her and the child."), t)
    }

    @Test
    fun theLabelsMayBeBoldOrInLowerCaseAndTheTextMayRunOverLines() {
        val t = TurnListener.parse("**Czech:** Dobrý den,\npojďte dál.\n**English:** Good day,\ncome in.", "cs")
        assertEquals(TurnText("Dobrý den,\npojďte dál.", "Good day,\ncome in."), t)
        assertNotNull(TurnListener.parse("czech: ahoj english: hello", "cs"))
    }

    @Test
    fun aReplyWithoutBothLabelsOrWithAnEmptyHalfIsNotAnAnswer() {
        assertNull(TurnListener.parse("Dobrý den", "cs"))
        assertNull(TurnListener.parse("CZECH: Dobrý den", "cs"))
        assertNull(TurnListener.parse("CZECH: \nENGLISH: Hello", "cs"))
        assertNull(TurnListener.parse("CZECH: Ahoj\nENGLISH:   ", "cs"))
        assertNull(TurnListener.parse("   ", "cs"))
    }

    @Test
    fun anEnglishClientGetsThePlainTranscriptAndNoTranslation() {
        assertEquals(TurnText("My father is seventy three.", null), TurnListener.parse("  My father is seventy three. ", "en"))
        assertEquals(TurnText("Hello.", null), TurnListener.parse("TRANSCRIPT: Hello.", "en"))
        assertNull(TurnListener.parse("", "en"))
    }

    @Test
    fun theInstructionNamesTheLanguageAndAsksForTheTwoLines() {
        val user = TurnListener.user("cs")
        assertTrue(user.contains("CZECH:") && user.contains("ENGLISH:"))
        assertTrue(TurnListener.system("cs").contains("Czech"))
        assertTrue(!TurnListener.user("en").contains("ENGLISH:"))
    }

    @Test
    fun aGoodFirstReplyIsTheOnlyCall() {
        val llm = Hearing(listOf("CZECH: Ahoj\nENGLISH: Hello"))
        val t = TurnListener.listen(llm, ShortArray(16_000), "cs")
        assertEquals(TurnText("Ahoj", "Hello"), t)
        assertEquals(1, llm.calls)
        assertEquals(listOf(16_000), llm.heard)
    }

    @Test
    fun aBadFirstReplyIsAskedForOnceMore() {
        val llm = Hearing(listOf("Ahoj", "CZECH: Ahoj\nENGLISH: Hello"))
        assertEquals(TurnText("Ahoj", "Hello"), TurnListener.listen(llm, ShortArray(8), "cs"))
        assertEquals(2, llm.calls)
    }

    @Test
    fun aModelThatNeverKeepsTheFormatLeavesTheTextAndNoEnglishForTheTranslateJob() {
        val llm = Hearing(listOf("Czech: Dobrý den, jak se máte"))
        val t = TurnListener.listen(llm, ShortArray(8), "cs")
        assertEquals(TurnListener.MAX_ATTEMPTS, llm.calls)
        assertEquals(TurnText("Dobrý den, jak se máte", null), t)
    }

    @Test
    fun theEnglishOfPiecesFollowsTheUtterancesTheyMergeInto() {
        val pieces = listOf(
            Piece(0, 1000, "Dobrý den", "S0", "Good day"),
            Piece(1000, 2000, "pojďte dál", "S0", "come in"),
            Piece(2000, 3000, "", "S1", "dropped: no source text"),
            Piece(3000, 4000, "Nervózní", "S1", "Nervous"),
            Piece(4000, 5000, "Ano", "S0", null),
        )
        val segments = UtteranceBuilder.build(pieces)
        val english = UtteranceBuilder.english(pieces)
        assertEquals(listOf("Dobrý den pojďte dál", "Nervózní", "Ano"), segments.map { it.text })
        assertEquals(mapOf(1 to "Good day come in", 2 to "Nervous"), english)
    }
}
