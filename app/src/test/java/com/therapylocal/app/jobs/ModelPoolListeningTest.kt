package com.therapylocal.app.jobs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Fast workflow's pool rules (docs/AD/AD-22.md): the LLM that hears the audio serves every stage, and one that cannot hear is replaced. */
class ModelPoolListeningTest {
    private class Llm2(val ears: Boolean) : Llm {
        var closed = false
        override val model = "gemma-4-e2b"
        override val hearing = ears
        override fun ask(system: String, user: String, schema: String?, maxOutputTokens: Int, temperature: Double, topK: Int) = ""
        override fun close() { closed = true }
    }

    private var listening = true
    private val loaded = ArrayList<Llm2>()
    private val pool = ModelPool(
        loadLlm = { Llm2(ears = listening).also { loaded += it } },
        loadEmbed = { error("no embed") },
        loadAsr = { error("no asr in Fast") },
        llmModel = { "gemma-4-e2b" },
        listening = { listening },
    )

    @Test
    fun aHearingLlmIsHandedOutAgain() {
        val a = pool.llm()
        assertTrue(a.hearing)
        assertSame(a, pool.llm())
        assertEquals(1, loaded.size)
    }

    @Test
    fun anLlmThatCannotHearIsReplacedWhenTheWorkflowNeedsEars() {
        listening = false
        val deaf = pool.llm()
        assertFalse(deaf.hearing)
        listening = true
        val hearing = pool.llm()
        assertTrue(hearing.hearing)
        assertTrue((deaf as Llm2).closed)
        assertEquals(2, loaded.size)
    }

    @Test
    fun transcribeKeepsTheLlmInFastAndDropsItOtherwise() {
        val a = pool.llm()
        pool.refresh(JobGraph.TRANSCRIBE)
        assertFalse((a as Llm2).closed)
        // Translate, roles and extract read with the same engine: it stays loaded.
        pool.refresh(JobGraph.ROLES)
        assertFalse(a.closed)
    }

    @Test
    fun withoutFastTranscribeUnloadsTheLlm() {
        val plain = ModelPool({ Llm2(false).also { loaded += it } }, { error("x") }, { error("x") }, { "gemma-4-e2b" })
        val a = plain.llm() as Llm2
        plain.refresh(JobGraph.TRANSCRIBE)
        assertTrue(a.closed)
    }
}
