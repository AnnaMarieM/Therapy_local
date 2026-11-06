package com.therapylocal.app.jobs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Loaders that run out of memory, fail twice in a row, or cannot even close: the pool never ends up holding two models or a dead one. */
class ModelPoolAdversarialTest {
    private inner class FakeLlm(override val model: String, override val contextTokens: Int) : Llm {
        var closed = false
        var closeThrows = false
        override fun ask(system: String, user: String, schema: String?, maxOutputTokens: Int, temperature: Double, topK: Int) = ""
        override fun close() {
            closed = true
            if (closeThrows) throw IllegalStateException("engine close failed")
        }
    }

    private inner class FakeEmbed : Embed {
        override val model = "embed"
        var closed = false
        override fun embed(text: String) = FloatArray(1)
        override fun close() {
            closed = true
        }
    }

    private inner class FakeAsr : Asr {
        var closed = false
        override fun transcribe(pcm: ShortArray) = ""
        override fun close() {
            closed = true
        }
    }

    private var chosen = "gemma-4-e2b"
    private val llms = ArrayList<FakeLlm>()
    private val embeds = ArrayList<FakeEmbed>()
    private val asrs = ArrayList<FakeAsr>()

    private fun resident(): List<String> = buildList {
        if (llms.any { !it.closed }) add("llm")
        if (embeds.any { !it.closed }) add("embed")
        if (asrs.any { !it.closed }) add("asr")
    }

    private fun pool(
        loadLlm: (Int) -> Llm = { ctx -> FakeLlm(chosen, ctx).also { llms += it } },
        loadAsr: (String) -> Asr = { _ -> FakeAsr().also { asrs += it } },
    ) = ModelPool(
        loadLlm = loadLlm,
        loadEmbed = { FakeEmbed().also { embeds += it } },
        loadAsr = loadAsr,
        llmModel = { chosen },
        baseContext = 6144,
    )

    @Test
    fun aRecogniserThatFailsToLoadLeavesNothingResidentAndCanBeRetried() {
        var asrFails = true
        val p = pool(loadAsr = { _ -> if (asrFails) throw IllegalStateException("No backend could load canary") else FakeAsr().also { asrs += it } })
        p.llm()
        assertThrows(IllegalStateException::class.java) { p.asr("en") }
        assertEquals(emptyList<String>(), resident())
        asrFails = false
        p.asr("en")
        assertEquals(listOf("asr"), resident())
    }

    @Test
    fun repeatedFailuresOfEveryLoaderNeverLeaveAModelResident() {
        val p = pool(
            loadLlm = { _ -> throw IllegalStateException("No backend could load gemma") },
            loadAsr = { _ -> throw IllegalStateException("No backend could load canary") },
        )
        repeat(20) {
            assertThrows(IllegalStateException::class.java) { p.llm() }
            assertThrows(IllegalStateException::class.java) { p.asr("en") }
            p.embed()
            p.refresh(JobGraph.EXTRACT)
            assertTrue(resident().isEmpty())
        }
    }

    @Test
    fun aTextLoadThatFailsHasAlreadyFreedTheOtherModels() {
        var fail = true
        val p = pool(loadLlm = { ctx -> if (fail) throw OutOfMemoryError("gpu") else FakeLlm(chosen, ctx).also { llms += it } })
        p.asr("en")
        assertThrows(OutOfMemoryError::class.java) { p.llm() }
        assertEquals("the recogniser was unloaded before the LLM was tried", emptyList<String>(), resident())
        fail = false
        p.llm()
        assertEquals(listOf("llm"), resident())
    }

    @Test
    fun anEngineWhoseCloseThrowsIsNeverHandedOutAgain() {
        val p = pool()
        val first = p.llm() as FakeLlm
        first.closeThrows = true
        runCatching { p.embed() }
        assertNotSame("a closed engine must not be reused", first, p.llm())
        assertEquals(1, llms.count { !it.closed })
    }

    @Test
    fun aModelSwitchDuringTheLoadClosesWhatWasLoadedAndPropagates() {
        val p = pool(loadLlm = { ctx -> FakeLlm("gemma-4-e4b", ctx).also { llms += it } })
        assertThrows(ModelSwitched::class.java) { p.llm() }
        assertTrue(llms.single().closed)
        assertEquals(emptyList<String>(), resident())
    }
}
