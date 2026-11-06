package com.therapylocal.app.engine

import android.content.Context
import android.content.ContextWrapper
import com.therapylocal.app.asr.SpeechRecogniser
import com.therapylocal.app.config.EmbeddingConfig
import com.therapylocal.app.config.LiveCueConfig
import com.therapylocal.app.embed.Embedder
import com.therapylocal.app.log.LogCtx
import com.therapylocal.app.memory.MemoryLlm
import com.therapylocal.app.models.BackendGuard
import com.therapylocal.app.models.BackendLadder
import com.therapylocal.app.models.ModelRegistry
import com.therapylocal.app.models.SharedStore
import com.therapylocal.app.models.SpeechLanguage
import com.therapylocal.app.perf.LlmCall
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class EngineProxyTest {
    private val context: Context = ContextWrapper(null)
    private val store = SharedStore()
    private val guard = BackendGuard(store, "build")
    private val phases = CopyOnWriteArrayList<Triple<String, String, String>>()

    private fun proxy(host: FakeHost) = EngineProxy({ host }, "T", "t", { m, b, p -> phases += Triple(m, b, p); guard.phase(m, b, p) })
    private fun llm(host: FakeHost) = MemoryLlm(
        context, 4096, ModelRegistry.GEMMA_E2B, host, ladder = { BackendLadder(guard) }, guard = { guard }, gate = { null }, proxy = proxy(host),
    )
    private fun embedder(host: FakeHost, textOnly: Boolean = true) = Embedder(
        context, null, textOnly, host, ladder = { BackendLadder(guard) }, guard = { guard }, gate = { _, _ -> null }, proxy = proxy(host),
    )

    @Test fun ladderOverTheHostGpuThrowsCpuOpens() {
        val host = FakeHost().apply { failBackends["GPU"] = IllegalStateException("gpu broke") }
        val m = llm(host)
        m.load(listOf("GPU", "CPU"))
        assertEquals("CPU", m.backend)
        assertEquals(listOf("GPU", "CPU"), host.ops.filterIsInstance<EngineOp.Open>().map { it.backend })
        assertTrue(host.ops.filterIsInstance<EngineOp.Open>().all { it.textOnly && it.kind == EngineKind.LLM && it.maxTokens == 4096 })
        // the failed GPU attempt is recorded in the guard, main side
        assertEquals(BackendGuard.State.FAILED, guard.record(ModelRegistry.GEMMA_E2B.id, "GPU")?.state)
        assertEquals(BackendGuard.State.PROVEN, guard.record(ModelRegistry.GEMMA_E2B.id, "CPU")?.state)
    }

    @Test fun phasesReachTheGuardBeforeTheFailure() {
        val host = FakeHost().apply { failBackends["GPU"] = IllegalStateException("boom") }
        llm(host).load(listOf("GPU", "CPU"))
        val gpu = phases.filter { it.second == "GPU" }.map { it.third }
        assertEquals(listOf("create"), gpu)
        assertEquals(listOf("create", "test"), phases.filter { it.second == "CPU" }.map { it.third })
        assertTrue(phases.all { it.first == ModelRegistry.GEMMA_E2B.id })
    }

    @Test fun askReturnsTheTextAndKeepsTheOriginalErrorName() {
        val host = FakeHost()
        val m = llm(host)
        m.load(listOf("CPU"))
        assertEquals("ok", m.ask("s", "u", null, 100, 0.1, 20))
        host.ask = { throw RemoteEngineException("LiteRtLmJniException", "x") }
        try { m.ask("s", "u", null, 100, 0.1, 20); fail() } catch (e: RemoteEngineException) { assertEquals("LiteRtLmJniException", e.typeName) }
    }

    @Test fun failingAskTwiceStrikesAndTurnsTheProxyUnhealthy() {
        val host = FakeHost()
        val m = llm(host)
        m.load(listOf("GPU", "CPU"))
        assertTrue(m.healthy)
        host.ask = { throw IllegalStateException("bad") }
        repeat(BackendGuard.INFERENCE_STRIKE) { try { m.ask("s", "u", null, 100, 0.1, 20) } catch (_: IllegalStateException) {} }
        assertFalse(m.healthy)
    }

    @Test fun exceedsMaximumSurvivesTheBoundary() {
        val host = FakeHost()
        val m = llm(host)
        m.load(listOf("CPU"))
        host.ask = { throw EngineErrors.rebuild(EngineErrors.capture(IllegalStateException("Input token ids exceeds maximum number of tokens"), redact = false)) }
        try { m.ask("s", "u", null, 100, 0.1, 20); fail() } catch (e: IllegalStateException) { assertTrue(e.message!!.contains("exceeds maximum")) }
    }

    @Test fun askBeforeLoadFailsAndCloseSendsTheHandle() {
        val host = FakeHost()
        val m = llm(host)
        try { m.ask("s", "u", null, 1, 0.0, 1); fail() } catch (_: IllegalStateException) {}
        m.load(listOf("CPU"))
        m.close()
        assertEquals(listOf(1), host.closed)
        assertEquals("none", m.backend)
    }

    @Test fun embedderLoadsTakesMaxTokensFromOpenedAndEmbeds() = runBlocking {
        val host = FakeHost(EngineRole.BATCH).apply { maxTokens = 300 }
        val e = embedder(host)
        e.load()
        assertEquals(300, e.maxTokens)
        assertEquals(EmbeddingConfig.DIM, e.embedText("hi", true, LogCtx.NONE).size)
        assertTrue(host.ops.filterIsInstance<EngineOp.Open>().all { it.kind == EngineKind.EMBED && it.textOnly })
    }

    // N38 bad embedding turns the embedder unhealthy

    @Test fun wrongDimOrNanThrowsBadEmbeddingAndTurnsUnhealthyThenReloads() = runBlocking {
        val host = FakeHost()
        val e = embedder(host)
        e.load()
        host.vector = { FloatArray(EmbeddingConfig.DIM - 1) }
        try { e.embedText("x", false, LogCtx.NONE); fail() } catch (t: IllegalStateException) { assertEquals("bad embedding", t.message) }
        assertFalse(e.healthy)
        host.vector = { FloatArray(EmbeddingConfig.DIM).also { it[3] = Float.NaN } }
        try { e.embedText("x", false, LogCtx.NONE); fail() } catch (t: IllegalStateException) { assertEquals("bad embedding", t.message) }
        // every use after a bad reply closes the old handle and opens a new one (initial, after 1st bad, after 2nd bad)
        host.vector = { FloatArray(EmbeddingConfig.DIM) { 0.5f } }
        e.embedText("x", false, LogCtx.NONE)
        assertTrue(e.healthy)
        assertEquals(3, host.ops.count { it is EngineOp.Open })
        assertTrue(host.closed.isNotEmpty())
    }

    // N38 oversize PCM throws before call

    @Test fun oversizePcmThrowsBeforeAnyCall() = runBlocking {
        val host = FakeHost(EngineRole.LIVE)
        val e = embedder(host, textOnly = false)
        e.load()
        val before = host.ops.size
        try { e.embedAudio(ShortArray(LiveCueConfig.MAX_LOOKBACK_SAMPLES + 1), LogCtx.NONE); fail() } catch (_: IllegalArgumentException) {}
        assertEquals(before, host.ops.size)
        assertEquals(EmbeddingConfig.DIM, e.embedAudio(ShortArray(16000), LogCtx.NONE, "query").size)

        val asr = SpeechRecogniser(context, SpeechLanguage.of("cs"), FakeHost(), "cpu", ladder = { BackendLadder(guard) }, guard = { guard }, proxy = proxy(host))
        try { asr.transcribe(ShortArray(LiveCueConfig.MAX_LOOKBACK_SAMPLES + 1), LogCtx.NONE); fail() } catch (_: IllegalArgumentException) {}
        assertEquals(before + 1, host.ops.size) // only the one valid EmbedAudio above
    }

    @Test fun closeDuringACallIsDeferredUntilTheCallEnds() = runBlocking {
        val host = FakeHost().apply { embedGate = CountDownLatch(1); embedEntered = CountDownLatch(1) }
        val e = embedder(host)
        e.load()
        val t = Thread { runBlocking { e.embedText("x", false, LogCtx.NONE) } }.apply { start() }
        assertTrue(host.embedEntered.await(5, TimeUnit.SECONDS))
        e.close()
        assertTrue("not closed under a running call", host.closed.isEmpty())
        host.embedGate!!.countDown()
        t.join(5000)
        assertEquals(1, host.closed.size)
    }

    @Test fun closeGoesThroughCloseAsyncNeverASynchronousCall() {
        // Not blocking is the host's duty (RemoteEngineHost bounds it); the proxy must only ever call closeAsync and never wait for a reply.
        val host = FakeHost()
        val m = llm(host)
        m.load(listOf("CPU"))
        val t0 = System.nanoTime()
        m.close()
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 1000)
        assertTrue(host.ops.none { it is EngineOp.Close }) // no synchronous Close call, only closeAsync
    }

    @Test fun proxyCallsTranscribeAndStatus() = runBlocking {
        val host = FakeHost()
        val proxy = proxy(host)
        val reply = proxy.call<EngineReply.Text>(EngineOp.Transcribe(proxy.newTag("transcribe"), 1, ShortArray(10)))
        assertEquals("hello world", reply.text)
        assertNull(proxy.status()?.handles?.firstOrNull())
    }
}
