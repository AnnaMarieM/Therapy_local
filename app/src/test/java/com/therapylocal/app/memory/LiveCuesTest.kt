package com.therapylocal.app.memory

import com.therapylocal.app.config.ChunkConfig
import com.therapylocal.app.config.LiveCueSettings
import com.therapylocal.app.data.FactRow
import com.therapylocal.app.embed.CueEmbedder
import com.therapylocal.app.engine.EngineDied
import com.therapylocal.app.engine.EngineRole
import com.therapylocal.app.engine.EngineUnavailable
import com.therapylocal.app.log.LogCtx
import com.therapylocal.app.models.BackendGuard
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** N40: a dying or unavailable engine pauses the cues quietly. The loop runs on its real dispatcher, so the tests wait for state, not for time. */
class LiveCuesTest {
    private class FakeEmbedder : CueEmbedder {
        @Volatile var failure: Throwable? = null
        @Volatile override var usable = true
        override val maxAudioSeconds = 30
        val calls = AtomicInteger()
        @Volatile var onCall: () -> Unit = {}
        override suspend fun embedAudio(pcm: ShortArray, ctx: LogCtx, role: String): FloatArray {
            calls.incrementAndGet()
            onCall()
            failure?.let { throw it }
            return FloatArray(4) { 0.5f }
        }
    }

    private val embedder = FakeEmbedder()
    private val fact = FactRow(1, "c1", "s1", "Heading", "content", "quote", 0, 0L, 1)
    private var cues: LiveCues? = null

    private fun live(): LiveCues = LiveCues(embedder, { _, _ -> listOf(fact to 0.9f) }, { LiveCueSettings.of(intervalMs = 1_000) }).also { cues = it }

    @After
    fun tearDown() {
        cues?.stop()
    }

    private fun LiveCues.feedSpeech() {
        val loud = ShortArray(ChunkConfig.SAMPLE_RATE * 4) { (if (it % 2 == 0) 8000 else -8000).toShort() }
        feed(loud, loud.size)
    }

    private fun waitFor(what: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!cond()) {
            check(System.currentTimeMillis() < end) { "timed out waiting for $what" }
            Thread.sleep(20)
        }
    }

    @Test
    fun aDeadEnginePausesQuietlyKeepsTheCuesAndTheNextGoodTickClearsIt() {
        val c = live()
        c.start("c1")
        c.feedSpeech()
        waitFor("first cue") { c.cues.value.isNotEmpty() }
        val before = c.cues.value

        embedder.failure = EngineDied(EngineRole.LIVE, 1, BackendGuard.LastExit(BackendGuard.ExitKind.NATIVE_CRASH, true, "crash_native"), "embed_audio", "embed_audio")
        waitFor("paused") { c.paused.value }
        assertNull("no exception text reaches the screen", c.error.value)
        assertEquals("the last cues stay", before, c.cues.value)
        assertTrue("the session is not stopped or restarted", c.running.value)

        embedder.failure = null
        waitFor("cleared") { !c.paused.value }
        assertNull(c.error.value)
        assertTrue(c.running.value)
    }

    @Test
    fun anUnavailableEnginePausesToo() {
        val c = live()
        c.start("c1")
        c.feedSpeech()
        embedder.failure = EngineUnavailable(EngineRole.LIVE, "storm", 30_000)
        waitFor("paused") { c.paused.value }
        assertNull(c.error.value)
        assertTrue(c.running.value)
    }

    @Test
    fun anOrdinaryErrorStillShowsAsAnError() {
        val c = live()
        c.start("c1")
        c.feedSpeech()
        embedder.failure = IllegalStateException("bad embedding")
        waitFor("error") { c.error.value != null }
        assertEquals("bad embedding", c.error.value)
        assertFalse(c.paused.value)
    }

    @Test
    fun anEmbedderSuspendedForProcessingDuringATickIsAQuietPauseNotAnError() {
        val c = live()
        embedder.onCall = { embedder.usable = false; throw IllegalStateException("Embedder suspended while sessions are processed") }
        c.start("c1")
        c.feedSpeech()
        waitFor("paused") { c.paused.value }
        assertNull(c.error.value)
    }
}
