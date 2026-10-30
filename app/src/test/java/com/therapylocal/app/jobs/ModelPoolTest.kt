package com.therapylocal.app.jobs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelPoolTest {
    private class FakeLlm(override val model: String) : Llm {
        var closed = false
        override fun ask(system: String, user: String, schema: String?, maxOutputTokens: Int, temperature: Double, topK: Int) = ""
        override fun close() {
            closed = true
        }
    }

    private class FakeAsr : Asr {
        var closed = false
        override fun transcribe(pcm: ShortArray) = ""
        override fun close() {
            closed = true
        }
    }

    private var chosen = "gemma-4-e2b"
    private val loaded = ArrayList<FakeLlm>()
    private val pool = ModelPool(
        loadLlm = { FakeLlm(chosen).also { loaded += it } },
        loadEmbed = { error("not used") },
        loadAsr = { llm -> llm(); FakeAsr() },
        llmModel = { chosen },
    )

    @Test
    fun theLlmIsKeptAcrossCallsWhileTheChoiceStands() {
        val first = pool.llm()
        assertSame(first, pool.llm())
        assertEquals(1, loaded.size)
    }

    @Test
    fun switchingTheBuildMidJobKeepsTheLoadedLlmUntilTheNextRefresh() {
        val oldLlm = pool.llm() as FakeLlm
        val oldAsr = pool.asr() as FakeAsr
        chosen = "gemma-4-e4b"

        assertSame(oldLlm, pool.llm())
        assertSame(oldAsr, pool.asr())
        assertEquals("gemma-4-e2b", pool.llmModel())
        assertFalse(oldLlm.closed)
        assertEquals(1, loaded.size)
    }

    @Test
    fun refreshUnloadsTheOldLlmAndItsSpeechRecogniser() {
        val oldLlm = pool.llm() as FakeLlm
        val oldAsr = pool.asr() as FakeAsr
        chosen = "gemma-4-e4b"

        pool.refresh()
        val asr = pool.asr()

        assertTrue(oldLlm.closed)
        assertTrue(oldAsr.closed)
        assertTrue(asr !== oldAsr)
        assertEquals(listOf("gemma-4-e2b", "gemma-4-e4b"), loaded.map { it.model })
        assertEquals("gemma-4-e4b", pool.llm().model)
        assertEquals("gemma-4-e4b", pool.llmModel())
    }

    @Test
    fun refreshKeepsTheLlmWhileTheChoiceStands() {
        val first = pool.llm() as FakeLlm
        pool.refresh()
        assertSame(first, pool.llm())
        assertFalse(first.closed)
    }

    @Test
    fun aSwitchBeforeTheJobLoadedTheLlmStopsTheJobInsteadOfMixingBuilds() {
        assertEquals("gemma-4-e2b", pool.llmModel())
        chosen = "gemma-4-e4b"

        assertThrows(ModelSwitched::class.java) { pool.llm() }
        assertTrue(loaded.isEmpty())

        pool.refresh()
        assertEquals("gemma-4-e4b", pool.llm().model)
    }
}
