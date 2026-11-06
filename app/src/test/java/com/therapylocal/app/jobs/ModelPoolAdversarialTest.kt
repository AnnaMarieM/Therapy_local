package com.therapylocal.app.jobs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Loaders that run out of memory, fail twice in a row, or cannot even close: the pool never ends up holding two models or a dead one. */
class ModelPoolAdversarialTest {
    private inner class FakeLlm(override val model: String, override val hasAudio: Boolean, override val contextTokens: Int) : Llm {
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

    private inner class FakeAsr(val onLlm: Boolean) : Asr {
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
    private val asrLlmFailures = ArrayList<Throwable>()

    private fun resident(): List<String> = buildList {
        if (llms.any { !it.closed }) add("llm")
        if (embeds.any { !it.closed }) add("embed")
        if (asrs.any { !it.closed && !it.onLlm }) add("whisper")
    }

    private fun pool(
        loadLlm: (Boolean, Int) -> Llm = { audio, ctx -> FakeLlm(chosen, audio, ctx).also { llms += it } },
        loadAsr: (() -> Llm?, String) -> Asr = { llm, _ -> FakeAsr(llm() != null).also { asrs += it } },
        gemmaAsr: Boolean = true,
    ) = ModelPool(
        loadLlm = loadLlm,
        loadEmbed = { FakeEmbed().also { embeds += it } },
        loadAsr = loadAsr,
        llmModel = { chosen },
        asrOnLlm = { gemmaAsr },
        baseContext = 6144,
        onAsrLlmFailed = { asrLlmFailures += it },
    )

    @Test
    fun anOutOfMemoryLoadingGemmaWithAudioFallsBackToTheOwnModelRecogniser() {
        val p = pool(loadLlm = { audio, ctx -> if (audio) throw OutOfMemoryError("audio encoder") else FakeLlm(chosen, false, ctx).also { llms += it } })
        p.embed()
        val asr = p.asr("en") as FakeAsr
        assertFalse(asr.onLlm)
        assertTrue(asrLlmFailures.single() is OutOfMemoryError)
        assertEquals(listOf("whisper"), resident())
    }

    @Test
    fun aRecogniserThatAlsoFailsAfterTheLlmFailedLeavesNothingResidentAndCanBeRetried() {
        var asrFails = true
        val p = pool(
            loadLlm = { audio, ctx -> if (audio) throw IllegalStateException("No backend could load gemma") else FakeLlm(chosen, false, ctx).also { llms += it } },
            loadAsr = { llm, _ -> if (asrFails) throw IllegalStateException("No backend could load whisper") else FakeAsr(llm() != null).also { asrs += it } },
        )
        p.llm()
        assertThrows(IllegalStateException::class.java) { p.asr("en") }
        assertEquals(emptyList<String>(), resident())
        asrFails = false
        assertFalse((p.asr("en") as FakeAsr).onLlm)
        assertEquals(listOf("whisper"), resident())
    }

    @Test
    fun repeatedFailuresOfEveryLoaderNeverLeaveAModelResident() {
        val p = pool(
            loadLlm = { audio, _ -> throw if (audio) OutOfMemoryError("oom") else IllegalStateException("No backend could load gemma") },
            loadAsr = { _, _ -> throw IllegalStateException("No backend could load whisper") },
        )
        repeat(20) {
            assertThrows(IllegalStateException::class.java) { p.llm() }
            assertThrows(IllegalStateException::class.java) { p.asr("en") }
            p.embed()
            p.refresh(JobGraph.EXTRACT)
            assertTrue(resident().isEmpty())
        }
        assertEquals(20, asrLlmFailures.size)
    }

    @Test
    fun aTextLoadThatFailsHasAlreadyFreedTheOtherModels() {
        var fail = true
        val p = pool(loadLlm = { audio, ctx -> if (fail) throw OutOfMemoryError("gpu") else FakeLlm(chosen, audio, ctx).also { llms += it } }, gemmaAsr = false)
        p.asr("en")
        assertThrows(OutOfMemoryError::class.java) { p.llm() }
        assertEquals("the recogniser was unloaded before the LLM was tried", emptyList<String>(), resident())
        fail = false
        assertFalse(p.llm().hasAudio)
        assertEquals(listOf("llm"), resident())
    }

    @Test
    fun anEngineWhoseCloseThrowsIsNeverHandedOutAgain() {
        val p = pool(gemmaAsr = false)
        val first = p.llm() as FakeLlm
        first.closeThrows = true
        runCatching { p.embed() }
        assertNotSame("a closed engine must not be reused", first, p.llm())
        assertEquals(1, llms.count { !it.closed })
    }

    @Test
    fun aModelSwitchDuringTheAudioLoadClosesWhatWasLoadedAndPropagates() {
        val p = pool(loadLlm = { audio, ctx -> FakeLlm("gemma-4-e4b", audio, ctx).also { llms += it } })
        assertThrows(ModelSwitched::class.java) { p.asr("en") }
        assertTrue(llms.single().closed)
        assertTrue(asrLlmFailures.isEmpty())
        assertEquals(emptyList<String>(), resident())
    }
}
