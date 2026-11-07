package com.therapylocal.app.engine

import android.content.Context
import android.content.ContextWrapper
import com.therapylocal.app.config.EmbeddingConfig
import com.therapylocal.app.embed.Embedder
import com.therapylocal.app.jobs.JobFailure
import com.therapylocal.app.log.LogCtx
import com.therapylocal.app.memory.MemoryLlm
import com.therapylocal.app.models.BackendGuard
import com.therapylocal.app.models.BackendGuard.ExitKind
import com.therapylocal.app.models.BackendLadder
import com.therapylocal.app.models.ModelRegistry
import com.therapylocal.app.models.SharedStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the main-side proxies do when the host ends under a loaded handle, and when the model cannot run on this phone at all (S10). */
class EngineDeathProxyTest {
    private val context: Context = ContextWrapper(null)
    private val store = SharedStore()
    private val guard = BackendGuard(store, "build").also { it.recover(null) }
    private val model = ModelRegistry.GEMMA_E2B.id

    private fun exit(kind: ExitKind, foreground: Boolean = true) = BackendGuard.LastExit(kind, foreground, "test")
    private fun died(kind: ExitKind = ExitKind.NATIVE_CRASH, op: String = "ask") = EngineDied(EngineRole.BATCH, 1, exit(kind), null, op)

    private fun proxy(host: FakeHost) = EngineProxy({ host }, "T", "t", { m, b, p -> guard.phase(m, b, p) })
    private fun llm(host: FakeHost) = MemoryLlm(
        context, 4096, ModelRegistry.GEMMA_E2B, host, ladder = { BackendLadder(guard, role = "batch") }, guard = { guard }, gate = { null }, proxy = proxy(host),
    )

    private fun embedder(host: FakeHost) = Embedder(
        context, null, true, host, ladder = { BackendLadder(guard, role = "batch") }, guard = { guard }, gate = { _ -> null }, proxy = proxy(host), inputTokens = { EmbeddingConfig.MAX_TOKENS },
    )

    @Test
    fun aDeathWhileLoadedMakesTheLlmUnhealthyWithoutCountingAPromptFailureOrClosingTwice() {
        val host = FakeHost()
        val m = llm(host)
        m.load(listOf("GPU", "CPU"))
        host.ask = { throw died() }
        assertThrows(EngineDied::class.java) { m.ask("s", "u", null, 10, 0.1, 20) }
        assertFalse(m.healthy)
        // the strike for a death is the connection's (registry), not an inference failure of the proxy
        assertEquals(0, guard.record(model, "GPU")!!.strikes)
        m.close()
        assertTrue("the dead handle is never closed on the new generation", host.closed.isEmpty())
    }

    @Test
    fun theReloadAfterRepeatedDeathsGoesToTheNextBackend() {
        val host = FakeHost()
        val m = llm(host)
        m.load(listOf("GPU", "CPU"))
        // the GPU proved itself once, so it takes BLOCK_PROVEN strikes from the connection's registry to block it
        repeat(BackendGuard.BLOCK_PROVEN) { guard.diedWhileLoaded(model, "GPU", exit(ExitKind.NATIVE_CRASH)) }
        assertTrue(guard.blocked(model, "GPU"))
        m.close()
        m.load(listOf("GPU", "CPU"))
        assertEquals("CPU", m.backend)
    }

    @Test
    fun anUnavailableHostIsNotAPromptFailure() {
        val host = FakeHost()
        val m = llm(host)
        m.load(listOf("GPU", "CPU"))
        host.ask = { throw EngineUnavailable(EngineRole.BATCH, "transport", 500) }
        repeat(BackendGuard.INFERENCE_STRIKE + 1) { assertThrows(EngineUnavailable::class.java) { m.ask("s", "u", null, 10, 0.1, 20) } }
        assertTrue(m.healthy)
        assertEquals(0, guard.record(model, "GPU")!!.strikes)
    }

    @Test
    fun aModelKilledOnTheCpuAgainAndAgainFailsTheJobForGoodWithNoFurtherAttempt() {
        val host = FakeHost()
        host.failBackends["CPU"] = died(ExitKind.KILLED_FOR_MEMORY, "open")
        val m = llm(host)
        // two kills: the second one makes it clear
        repeat(BackendGuard.BLOCK_UNPROVEN - 1) { assertThrows(BackendLadder.NoBackend::class.java) { m.load(listOf("CPU")) } }
        val e = assertThrows(JobFailure::class.java) { m.load(listOf("CPU")) }
        assertEquals("model_too_large_for_device", e.code)
        assertTrue(e.permanent)
        val opens = host.ops.count { it is EngineOp.Open }
        // the next load fails before the ladder: no attempt reaches the host
        assertThrows(JobFailure::class.java) { m.load(listOf("CPU")) }
        assertEquals(opens, host.ops.count { it is EngineOp.Open })
        // a person forcing the load (QA probe) is not judged
        host.failBackends.clear()
        m.load(listOf("CPU"), respectBlocks = false)
        assertEquals("CPU", m.backend)
    }

    @Test
    fun theEmbedderReloadsAfterADeathAndKeepsTheErrorForTheCaller() = runBlocking {
        val host = FakeHost()
        val e = embedder(host)
        e.load()
        host.vector = { throw died(op = "embed_text") }
        assertThrows(EngineDied::class.java) { runBlocking { e.embedText("q", true, LogCtx.NONE) } }
        assertFalse(e.healthy)
        host.vector = { FloatArray(EmbeddingConfig.DIM) { 0.01f } }
        assertEquals(EmbeddingConfig.DIM, e.embedText("q", true, LogCtx.NONE).size)
        assertTrue(e.healthy)
        assertEquals(2, host.ops.count { it is EngineOp.Open })
        assertTrue("the dead handle was not closed", host.closed.isEmpty())
    }

    @Test
    fun aBenignEndWhileSuspendedIsRethrownSoCallersCanPauseQuietly() = runBlocking {
        val host = FakeHost()
        val e = embedder(host)
        e.load()
        // Processing starts during the call: the :live host is shut down for :batch on purpose, and the stale handle answers with a benign end.
        host.vector = { e.suspendNow(); throw died(ExitKind.BENIGN, "embed_text") }
        val thrown = assertThrows(EngineDied::class.java) { runBlocking { e.embedText("q", true, LogCtx.NONE) } }
        assertEquals(ExitKind.BENIGN, thrown.exit.kind)
        assertTrue(e.suspended)
        // no reload behind processing's back
        assertEquals(1, host.ops.count { it is EngineOp.Open })
    }
}
