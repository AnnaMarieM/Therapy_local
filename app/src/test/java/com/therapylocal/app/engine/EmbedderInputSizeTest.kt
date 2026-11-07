package com.therapylocal.app.engine

import android.content.Context
import android.content.ContextWrapper
import com.therapylocal.app.config.EmbeddingConfig
import com.therapylocal.app.embed.Embedder
import com.therapylocal.app.models.BackendGuard
import com.therapylocal.app.models.BackendLadder
import com.therapylocal.app.models.SharedStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** The engine is opened at the smallest input size the use needs and reloaded when the use (the Settings lookback) needs another. */
class EmbedderInputSizeTest {
    private val context: Context = ContextWrapper(null)
    private val guard = BackendGuard(SharedStore(), "build")
    private var need = EmbeddingConfig.liveInputTokens(20)

    private fun embedder(host: FakeHost, textOnly: Boolean = false) = Embedder(
        context, null, textOnly, host, ladder = { BackendLadder(guard) }, guard = { guard }, gate = { _ -> null },
        proxy = EngineProxy({ host }, "T", "t", { m, b, p -> guard.phase(m, b, p) }), inputTokens = { need },
    )

    private fun FakeHost.opened() = ops.filterIsInstance<EngineOp.Open>().map { it.maxTokens }

    @Test
    fun theDefaultLookbackOpensAt1024NotAt8192() {
        val host = FakeHost()
        runBlocking { embedder(host).load() }
        assertEquals(listOf(1024), host.opened())
    }

    @Test
    fun aChangedLookbackReloadsAtTheNewSizeOnce() {
        val host = FakeHost()
        val e = embedder(host)
        runBlocking {
            e.load()
            e.ensureInput()
            assertEquals("same size: no reload", listOf(1024), host.opened())
            need = EmbeddingConfig.liveInputTokens(120)
            e.ensureInput()
            e.ensureInput()
        }
        assertEquals(listOf(1024, 4096), host.opened())
    }

    @Test
    fun anUnloadedEmbedderIsNotLoadedByASettingChange() {
        val host = FakeHost()
        runBlocking { embedder(host).ensureInput() }
        assertEquals(emptyList<Int>(), host.opened())
    }

    @Test
    fun theTextOnlyEmbedderUsesItsOwnSizeNotTheLiveOne() {
        val host = FakeHost()
        need = EmbeddingConfig.PIPELINE_INPUT_TOKENS
        runBlocking { embedder(host, textOnly = true).load() }
        assertEquals(listOf(2048), host.opened())
    }
}
