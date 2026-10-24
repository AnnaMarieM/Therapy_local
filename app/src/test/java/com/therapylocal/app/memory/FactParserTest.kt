package com.therapylocal.app.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FactParserTest {
    /** Real output from the phone (chunk 3 of George's first session): every fact on one line, keys repeated, no braces between them. */
    private val runOn = """{"category":"work","subject":"George","fact":"George runs engagement teams.","evidence":"I review their work, I deal with the client, I sign off on the file.","category":"work","subject":"George","fact":"George is expected to fix issues at eleven o'clock on a Sunday night.","evidence":"I'm the one who's expected to fix it at eleven o'clock on a Sunday night.","category":"coping","subject":"George","fact":"George feels he cannot say no despite knowing he can.","evidence":"I don't feel like can say no."""

    @Test
    fun salvagesRunOnOutputWithRepeatedKeys() {
        val facts = FactParser.anyFacts(runOn, "x", "s", 3)
        // The third fact is cut off inside its quote, so it has no usable evidence and is dropped.
        assertEquals(2, facts.size)
        assertEquals("work", facts[1].category)
        assertTrue(facts[1].evidence.startsWith("I'm the one"))
    }

    @Test
    fun readsOneObjectPerLine() {
        val text = "{\"category\":\"health\",\"subject\":\"George\",\"fact\":\"George saw his GP in November.\",\"evidence\":\"I went in in November\"}\n" +
            "{\"category\":\"family\",\"subject\":\"George\",\"fact\":\"George's father is called Richard.\",\"evidence\":\"my father Richard\"}"
        assertEquals(2, FactParser.anyFacts(text, "x", "s", 1).size)
    }

    @Test
    fun readsFactsWrapperAndTruncation() {
        val text = "{\"facts\":[{\"category\":\"work\",\"subject\":\"George\",\"fact\":\"George is a senior auditor.\",\"evidence\":\"I'm a senior auditor\"},{\"category\":\"work\",\"subject\":\"George\",\"fact\":\"George works 58 hours a w"
        assertEquals(1, FactParser.anyFacts(text, "x", "s", 1).size)
    }

    @Test
    fun dropsFactsWithoutAQuote() {
        assertEquals(0, FactParser.anyFacts("{\"category\":\"work\",\"fact\":\"George is a senior auditor.\"}", "x", "s", 1).size)
    }

    @Test
    fun invalidCategoriesAreMapped() {
        assertEquals("sleep_substance", FactParser.category("sleep"))
        assertEquals("other", FactParser.category("banana"))
    }
}
