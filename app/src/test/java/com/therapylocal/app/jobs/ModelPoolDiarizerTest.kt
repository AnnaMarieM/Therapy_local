package com.therapylocal.app.jobs

import com.therapylocal.app.asr.DiarTurn
import com.therapylocal.app.asr.Diarizer
import com.therapylocal.app.log.LogCtx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The speaker diarizer follows the pool's one-model-at-a-time rule (docs/AD/AD-06.md). */
class ModelPoolDiarizerTest {
    private class FakeDiarizer : Diarizer {
        override var healthy = true
        var closed = false
        override suspend fun load() {}
        override suspend fun diarize(pcm: ShortArray, numSpeakersHint: Int, ctx: LogCtx) = emptyList<DiarTurn>()
        override fun close() {
            closed = true
        }
    }

    private class FakeLlm : Llm {
        override val model = "gemma-4-e2b"
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

    private val diarizers = ArrayList<FakeDiarizer>()
    private val llms = ArrayList<FakeLlm>()
    private val asrs = ArrayList<FakeAsr>()
    private val pool = ModelPool(
        loadLlm = { FakeLlm().also { llms += it } },
        loadEmbed = { error("unused") },
        loadAsr = { FakeAsr().also { asrs += it } },
        llmModel = { "gemma-4-e2b" },
        loadDiarizer = { FakeDiarizer().also { diarizers += it } },
    )

    @Test
    fun aHealthyDiarizerIsReused() {
        assertSame(pool.diarizer(), pool.diarizer())
        assertEquals(1, diarizers.size)
    }

    @Test
    fun anUnhealthyOneIsClosedAndLoadedAgain() {
        val first = pool.diarizer() as FakeDiarizer
        first.healthy = false
        assertFalse(pool.diarizer() === first)
        assertTrue(first.closed)
    }

    @Test
    fun itNeverSharesMemoryWithTheLlmOrCanary() {
        pool.llm()
        pool.diarizer()
        assertTrue(llms.single().closed)
        pool.asr("cs")
        assertTrue(diarizers.single().closed)
        pool.diarizer()
        assertTrue(asrs.single().closed)
        pool.llm()
        assertTrue(diarizers.last().closed)
        assertFalse(llms.last().closed)
    }

    @Test
    fun betweenJobsOnlyTranscribeKeepsIt() {
        pool.diarizer()
        pool.refresh(JobGraph.TRANSCRIBE)
        assertFalse(diarizers.single().closed)
        pool.refresh(JobGraph.EXTRACT)
        assertTrue(diarizers.single().closed)
    }

    @Test
    fun closeUnloadsIt() {
        pool.diarizer()
        pool.close()
        assertTrue(diarizers.single().closed)
    }
}
