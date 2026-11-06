package com.therapylocal.app.jobs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelPoolTest {
    private class FakeLlm(override val model: String, override val hasAudio: Boolean, override val contextTokens: Int) : Llm {
        var closed = false
        override fun ask(system: String, user: String, schema: String?, maxOutputTokens: Int, temperature: Double, topK: Int) = ""
        override fun close() {
            closed = true
        }
    }

    private class FakeEmbed : Embed {
        override val model = "embed"
        var closed = false
        override fun embed(text: String) = FloatArray(1)
        override fun close() {
            closed = true
        }
    }

    private class FakeAsr(val onLlm: Boolean) : Asr {
        var closed = false
        override fun transcribe(pcm: ShortArray) = ""
        override fun close() {
            closed = true
        }
    }

    private var chosen = "gemma-4-e2b"
    private var gemmaAsr = true
    private var needed = 6144
    private val loaded = ArrayList<FakeLlm>()
    private val embeds = ArrayList<FakeEmbed>()
    private val asrs = ArrayList<FakeAsr>()

    /** Everything alive at this moment: the one-model-at-a-time rule says at most one of the three kinds. */
    private fun resident(): List<String> = buildList {
        if (loaded.any { !it.closed }) add("llm")
        if (embeds.any { !it.closed }) add("embed")
        if (asrs.any { !it.closed && !it.onLlm }) add("whisper")
    }

    private val pool = ModelPool(
        loadLlm = { audio, ctx -> FakeLlm(chosen, audio, ctx).also { loaded += it } },
        loadEmbed = { FakeEmbed().also { embeds += it } },
        loadAsr = { llm, _ -> val onLlm = llm() != null; FakeAsr(onLlm).also { asrs += it } },
        llmModel = { chosen },
        asrOnLlm = { gemmaAsr },
        contextNeeded = { needed },
        baseContext = 6144,
    )

    @Test
    fun theSpeechRecogniserIsKeptPerLanguageAndSwappedWhenTheLanguageChanges() {
        gemmaAsr = false
        val english = pool.asr("en") as FakeAsr
        assertSame(english, pool.asr("en"))
        assertFalse(english.closed)

        val czech = pool.asr("cs") as FakeAsr
        assertTrue(english.closed)
        assertFalse(czech.closed)
        assertSame(czech, pool.asr("cs"))
        assertEquals(listOf("whisper"), resident())
    }

    @Test
    fun theLlmIsKeptAcrossCallsWhileTheChoiceStands() {
        val first = pool.llm()
        assertSame(first, pool.llm())
        assertEquals(1, loaded.size)
    }

    @Test
    fun theLlmIsLoadedAsTextOnly() {
        assertFalse(pool.llm().hasAudio)
    }

    @Test
    fun speechRecognitionOnGemmaLoadsTheAudioEngineAndNothingElse() {
        pool.asr()
        assertTrue(loaded.single().hasAudio)
        assertEquals(listOf("llm"), resident())
    }

    @Test
    fun whisperLoadsNoLlmAndUnloadsTheOneThatIsThere() {
        gemmaAsr = false
        val llm = pool.llm() as FakeLlm
        pool.asr()
        assertTrue(llm.closed)
        assertEquals(listOf("whisper"), resident())
    }

    @Test
    fun anLlmWithoutAnAudioEncoderIsNotKeptNextToTheWhisperThatReplacesGemmaSpeech() {
        // The Gemma build has no audio input: asr() falls back to Whisper, and the engine that could not hear must not stay loaded beside it.
        val noAudio = ModelPool(
            loadLlm = { _, ctx -> FakeLlm(chosen, false, ctx).also { loaded += it } },
            loadEmbed = { error("not used") },
            loadAsr = { llm, _ -> assertTrue(llm() == null); FakeAsr(false).also { asrs += it } },
            llmModel = { chosen },
            asrOnLlm = { true },
            baseContext = 6144,
        )
        noAudio.asr()
        assertEquals(listOf("whisper"), resident())
    }

    @Test
    fun theThreeModelsAreNeverLoadedTogether() {
        pool.llm()
        assertEquals(listOf("llm"), resident())
        pool.embed()
        assertEquals(listOf("embed"), resident())
        pool.llm()
        assertEquals(listOf("llm"), resident())
        gemmaAsr = false
        pool.asr()
        assertEquals(listOf("whisper"), resident())
        pool.embed()
        assertEquals(listOf("embed"), resident())
        pool.asr()
        assertEquals(listOf("whisper"), resident())
    }

    @Test
    fun aTextJobDropsTheAudioEncoderAndTheSpeechRecogniser() {
        val asr = pool.asr() as FakeAsr
        val withAudio = loaded.single()

        pool.refresh(JobGraph.EXTRACT)

        assertTrue(asr.closed)
        assertTrue(withAudio.closed)
        assertFalse(pool.llm().hasAudio)
        assertEquals(2, loaded.size)
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
        val oldAsr = pool.asr() as FakeAsr
        val oldLlm = loaded.single()
        chosen = "gemma-4-e4b"

        assertSame(oldAsr, pool.asr())
        assertEquals("gemma-4-e2b", pool.llmModel())
        assertFalse(oldLlm.closed)
        assertEquals(1, loaded.size)
    }

    @Test
    fun refreshUnloadsTheOldLlmAndItsSpeechRecogniser() {
        val oldAsr = pool.asr() as FakeAsr
        val oldLlm = loaded.single()
        chosen = "gemma-4-e4b"

        pool.refresh()
        val asr = pool.asr()

        assertTrue(oldLlm.closed)
        assertTrue(oldAsr.closed)
        assertTrue(asr !== oldAsr)
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

    @Test
    fun gemmaSpeechRecognitionThatCannotLoadFallsBackToTheRecogniserWithItsOwnModel() {
        val failures = ArrayList<Throwable>()
        val failing = ModelPool(
            loadLlm = { audio, ctx -> if (audio) throw IllegalStateException("No backend could load gemma") else FakeLlm(chosen, false, ctx).also { loaded += it } },
            loadEmbed = { FakeEmbed().also { embeds += it } },
            loadAsr = { llm, _ -> FakeAsr(llm() != null).also { asrs += it } },
            llmModel = { chosen },
            asrOnLlm = { true },
            baseContext = 6144,
            onAsrLlmFailed = { failures += it },
        )
        val asr = failing.asr("cs") as FakeAsr
        assertFalse(asr.onLlm)
        assertEquals(1, failures.size)
        assertEquals(listOf("whisper"), resident())
    }

    @Test
    fun aModelSwitchDuringGemmaSpeechRecognitionIsNotSwallowedByTheFallback() {
        gemmaAsr = true
        pool.llmModel()
        chosen = "gemma-4-e4b"
        assertThrows(ModelSwitched::class.java) { pool.asr("en") }
    }
}
