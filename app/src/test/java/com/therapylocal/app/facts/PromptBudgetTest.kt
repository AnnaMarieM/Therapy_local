package com.therapylocal.app.facts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptBudgetTest {
    // Measured on the phone with Gemma 4: 714 Czech words of the made-up transcript plus the app's extraction prompts were 1,793 tokens.
    private val czech = "Moje sestra Lucie se přestěhovala do Brna a já mám pocit, že jsme si s ní teď strašně vzdálené. ".repeat(40)

    @Test
    fun czechIsEstimatedHigherThanEnglishForTheSameLength() {
        val english = "My sister Lucie moved to Brno and I feel that we are now terribly distant from each other. ".repeat(40)
        assertTrue(PromptBudget.tokens(czech) > PromptBudget.tokens(english))
    }

    @Test
    fun theEstimateIsNeverBelowTheMeasuredTokenCount() {
        // 5,401 characters measured as 1,793 tokens (3.0 per token overall, 2.7 for the Czech text): the estimate must not be below.
        val text = czech.repeat(2).take(5401)
        assertEquals(5401, text.length)
        assertTrue(PromptBudget.tokens(text) >= 1793)
    }

    @Test
    fun theReplyAndTheTemplateAreCounted() {
        val needed = PromptBudget.required("system", "user", 1600)
        assertTrue(needed > 1600 + 96)
    }

    @Test
    fun contextIsRoundedUpAndNeverBelowTheFloor() {
        assertEquals(6144, PromptBudget.contextFor(3000, 6144))
        assertEquals(6656, PromptBudget.contextFor(6200, 6144))
        assertEquals(6144, PromptBudget.contextFor(6144, 6144))
    }

    @Test
    fun contextIsCappedAtTheLargestTheAppAsksFor() {
        assertEquals(PromptBudget.MAX_CONTEXT, PromptBudget.contextFor(1_000_000, 6144))
    }

    @Test
    fun aRealWindowOfCzechFitsTheFloorWithItsReply() {
        // 1000 window tokens at the app's 1.4 per word are 714 words, and Czech runs about 2 tokens per word: the real prompt is close to 1,800 tokens.
        val window = "ě".repeat(714 * 7)
        val needed = PromptBudget.required("s".repeat(1200), window, 1600)
        assertTrue("needs $needed", needed > 3500)
        assertTrue("needs $needed", needed < 6144)
    }
}
