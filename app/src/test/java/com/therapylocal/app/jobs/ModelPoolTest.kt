package com.therapylocal.app.jobs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelPoolTest {
    private class FakeLlm(override val model: String, override val contextTokens: Int) : Llm {
        var closed = false
        override fun ask(system: String, user: String, schema: String?, maxOutputTokens: Int, temperature: Double, topK: Int) = ""
        override fun close() {
            closed = true
        }
    }

    private class FakeEmbed : Embed {
        override val model = "embed"
        override var healthy = true
        var closed = false
        override fun embed(text: String) = FloatArray(1)
        override fun close() {
            closed = true
        }
    }

    private class FakeAsr(val language: String) : Asr {
        override var healthy = true
        var closed = false
        override fun transcribe(pcm: ShortArray) = ""
        override fun close() {
            closed = true
        }
    }

    private var chosen = "gemma-4-e2b"
    private var needed = 6144
    private val loaded = ArrayList<FakeLlm>()
    private val embeds = ArrayList<FakeEmbed>()
    private val asrs = ArrayList<FakeAsr>()

    /** Everything alive at this moment: the one-model-at-a-time rule says at most one of the three kinds. */
    private fun resident(): List<String> = buildList {
        if (loaded.any { !it.closed }) add("llm")
        if (embeds.any { !it.closed }) add("embed")
        if (asrs.any { !it.closed }) add("asr")
    }

    private val pool = ModelPool(
        loadLlm = { ctx -> FakeLlm(chosen, ctx).also { loaded += it } },
        loadEmbed = { FakeEmbed().also { embeds += it } },
        loadAsr = { language -> FakeAsr(language).also { asrs += it } },
        llmModel = { chosen },
        contextNeeded = { needed },
        baseContext = 6144,
    )

    @Test
    fun theSpeechRecogniserIsKeptPerLanguageAndSwappedWhenTheLanguageChanges() {
        val english = pool.asr("en") as FakeAsr
        assertSame(english, pool.asr("en"))
        assertFalse(english.closed)

        val czech = pool.asr("cs") as FakeAsr
        assertTrue(english.closed)
        assertFalse(czech.closed)
        assertEquals("cs", czech.language)
        assertSame(czech, pool.asr("cs"))
        assertEquals(listOf("asr"), resident())
    }

    @Test
    fun theLlmIsKeptAcrossCallsWhileTheChoiceStands() {
        val first = pool.llm()
        assertSame(first, pool.llm())
        assertEquals(1, loaded.size)
    }

    @Test
    fun speechRecognitionLoadsNoLlmAndUnloadsTheOneThatIsThere() {
        val llm = pool.llm() as FakeLlm
        pool.asr()
        assertTrue(llm.closed)
        assertEquals(listOf("asr"), resident())
    }

    @Test
    fun theThreeModelsAreNeverLoadedTogether() {
        pool.llm()
        assertEquals(listOf("llm"), resident())
        pool.embed()
        assertEquals(listOf("embed"), resident())
        pool.llm()
        assertEquals(listOf("llm"), resident())
        pool.asr()
        assertEquals(listOf("asr"), resident())
        pool.embed()
        assertEquals(listOf("embed"), resident())
        pool.asr()
        assertEquals(listOf("asr"), resident())
        pool.llm()
        assertEquals(listOf("llm"), resident())
    }

    @Test
    fun anUnhealthyEmbedderIsClosedAndLoadedAgain() {
        val first = pool.embed() as FakeEmbed
        first.healthy = false
        val second = pool.embed()
        assertTrue(first.closed)
        assertTrue(second !== first)
        assertEquals(listOf("embed"), resident())
    }

    @Test
    fun anUnhealthySpeechRecogniserIsClosedAndLoadedAgain() {
        val first = pool.asr("cs") as FakeAsr
        first.healthy = false
        val second = pool.asr("cs") as FakeAsr
        assertTrue(first.closed)
        assertTrue(second !== first)
        assertEquals("cs", second.language)
        assertEquals(listOf("asr"), resident())
    }

    @Test
    fun aTextJobDropsTheSpeechRecogniser() {
        val asr = pool.asr() as FakeAsr
        pool.refresh(JobGraph.EXTRACT)
        assertTrue(asr.closed)
        pool.llm()
        assertEquals(listOf("llm"), resident())
    }

    @Test
    fun aJobKeepsOnlyTheModelsItUses() {
        pool.llm()
        val llm = loaded.single()
        pool.refresh(JobGraph.EXTRACT)
        assertFalse(llm.closed)

        pool.refresh(JobGraph.WINDOW)
        assertTrue(llm.closed)

        pool.embed()
        pool.refresh(JobGraph.EXTRACT)
        assertTrue(embeds.single().closed)
    }

    @Test
    fun cleanupHoldsNoModel() {
        pool.llm()
        pool.refresh(JobGraph.CLEANUP)
        assertTrue(loaded.single().closed)
        assertEquals(emptyList<String>(), resident())
    }

    @Test
    fun anUnknownKindLeavesTheModelsAlone() {
        val llm = pool.llm() as FakeLlm
        pool.refresh("work")
        assertFalse(llm.closed)
    }

    @Test
    fun theLlmIsLoadedWithTheContextTheOpenWorkNeeds() {
        needed = 9000
        pool.refresh(JobGraph.EXTRACT)
        assertEquals(9000, pool.llm().contextTokens)
    }

    @Test
    fun theLlmNeverGetsLessThanTheBaseContext() {
        needed = 2000
        pool.refresh(JobGraph.EXTRACT)
        assertEquals(6144, pool.llm().contextTokens)
    }

    @Test
    fun anEngineTheOpenWorkOutgrewIsReloadedWithMoreBeforeTheNextJob() {
        pool.refresh(JobGraph.EXTRACT)
        val small = pool.llm() as FakeLlm
        assertEquals(6144, small.contextTokens)

        needed = 11000
        pool.refresh(JobGraph.EXTRACT)

        assertTrue(small.closed)
        assertEquals(11000, pool.llm().contextTokens)
        assertEquals(1, loaded.count { !it.closed })
    }

    @Test
    fun anEngineThatIsBigEnoughIsKept() {
        needed = 9000
        pool.refresh(JobGraph.EXTRACT)
        val first = pool.llm()
        needed = 7000
        pool.refresh(JobGraph.EXTRACT)
        assertSame(first, pool.llm())
    }

    @Test
    fun switchingTheBuildMidJobKeepsTheLoadedLlmUntilTheNextRefresh() {
        val oldLlm = pool.llm()
        chosen = "gemma-4-e4b"

        assertSame(oldLlm, pool.llm())
        assertEquals("gemma-4-e2b", pool.llmModel())
        assertEquals(1, loaded.size)
    }

    @Test
    fun refreshUnloadsTheOldLlm() {
        val oldLlm = pool.llm() as FakeLlm
        chosen = "gemma-4-e4b"

        pool.refresh()
        val llm = pool.llm()

        assertTrue(oldLlm.closed)
        assertTrue(llm !== oldLlm)
        assertEquals(listOf("gemma-4-e2b", "gemma-4-e4b"), loaded.map { it.model })
        assertEquals("gemma-4-e4b", pool.llmModel())
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

    @Test
    fun closeUnloadsEverything() {
        pool.llm()
        pool.close()
        assertEquals(emptyList<String>(), resident())
    }
}
