package com.therapylocal.app.jobs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelPoolTest {
    private class FakeLlm(override val model: String, override val hasAudio: Boolean = false) : Llm {
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

    private class FakeAsr : Asr {
        var closed = false
        override fun transcribe(pcm: ShortArray) = ""
        override fun close() {
            closed = true
        }
    }

    private var chosen = "gemma-4-e2b"
    private val loaded = ArrayList<FakeLlm>()
    private val embeds = ArrayList<FakeEmbed>()
    private val pool = ModelPool(
        loadLlm = { audio -> FakeLlm(chosen, audio).also { loaded += it } },
        loadEmbed = { FakeEmbed().also { embeds += it } },
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
        val oldAsr = pool.asr() as FakeAsr
        val oldLlm = loaded.single()
        chosen = "gemma-4-e4b"

        assertSame(oldLlm, pool.llm())
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

    @Test
    fun theLlmIsLoadedAsTextOnly() {
        assertFalse(pool.llm().hasAudio)
    }

    @Test
    fun speechRecognitionLoadsTheAudioEngineAndTextJobsReuseIt() {
        val asr = pool.asr() as FakeAsr
        val llm = loaded.single()
        assertTrue(llm.hasAudio)
        assertSame(llm, pool.llm())
        assertFalse(asr.closed)
    }

    @Test
    fun speechRecognitionReplacesAnLlmThatWasLoadedAsTextOnly() {
        val text = pool.llm() as FakeLlm
        pool.asr()
        assertTrue(text.closed)
        assertEquals(listOf(false, true), loaded.map { it.hasAudio })
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
        pool.embed()
        val llm = loaded.single()
        val embed = embeds.single()

        pool.refresh(JobGraph.EXTRACT)
        assertTrue(embed.closed)
        assertFalse(llm.closed)

        pool.refresh(JobGraph.WINDOW)
        assertTrue(llm.closed)
    }

    @Test
    fun commitKeepsTheLlmAndTheEmbedderAndExtractToWindowToExtractReloadsOnlyWhatWasDropped() {
        val llm = pool.llm() as FakeLlm
        pool.embed()
        pool.refresh(JobGraph.COMMIT)
        assertFalse(llm.closed)
        assertFalse(embeds.single().closed)
        assertSame(llm, pool.llm())
        assertEquals(1, embeds.size)

        pool.refresh(JobGraph.TRANSCRIBE)
        assertTrue(llm.closed)
        assertTrue(embeds.single().closed)
    }

    @Test
    fun cleanupHoldsNoModel() {
        pool.llm()
        pool.embed()
        pool.refresh(JobGraph.CLEANUP)
        assertTrue(loaded.single().closed)
        assertTrue(embeds.single().closed)
    }
}
