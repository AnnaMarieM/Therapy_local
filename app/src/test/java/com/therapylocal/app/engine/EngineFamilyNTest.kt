package com.therapylocal.app.engine

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.therapylocal.app.config.BackendPolicy
import com.therapylocal.app.config.EngineConfig
import com.therapylocal.app.config.LiveCueConfig
import com.therapylocal.app.engine.wire.ShmMath
import com.therapylocal.app.memory.MemoryLlm
import com.therapylocal.app.models.BackendGuard
import com.therapylocal.app.models.BackendLadder
import com.therapylocal.app.models.LogFileTape
import com.therapylocal.app.models.LogTape
import com.therapylocal.app.models.ModelRegistry
import com.therapylocal.app.models.SharedStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** The N rows that the connection, proxy and error suites did not pin yet: N19 edges, N20, N21 and N23. */
class EngineFamilyNTest {
    /** A connection seen as the [EngineHost] a proxy talks to (what `RemoteEngineHost` is, without Android). */
    private class ConnHost(val conn: EngineConnection, override val role: EngineRole) : EngineHost {
        override fun call(op: EngineOp, phases: PhaseSink): EngineReply = conn.call(op, phases)
        override fun closeAsync(op: EngineOp.Close) = conn.closeAsync(op)
    }

    // ---------------------------------------------------------------------------------------------------- N19

    @Test fun n19_shmSizeMathAtTheEdgesForShortsAndBytes() {
        // 0 and odd
        assertEquals(2, ShmMath.regionBytes(0, 1))
        assertEquals(2, ShmMath.regionBytes(0, 2))
        assertEquals(3, ShmMath.regionBytes(3, 1))
        assertEquals(10, ShmMath.regionBytes(5, 2))
        assertEquals(2, ShmMath.regionBytes(1, 1)) // never below 2
        // max: the longest PCM the live path may send, and the largest sizes that still fit an Int
        assertEquals(LiveCueConfig.MAX_LOOKBACK_SAMPLES * 2, ShmMath.regionBytes(LiveCueConfig.MAX_LOOKBACK_SAMPLES, 2))
        assertEquals(Int.MAX_VALUE, ShmMath.regionBytes(Int.MAX_VALUE, 1))
        assertEquals(Int.MAX_VALUE - 1, ShmMath.regionBytes(Int.MAX_VALUE / 2, 2))
        // max + 1: an error, never a negative or wrapped size
        try { ShmMath.regionBytes(Int.MAX_VALUE / 2 + 1, 2); fail("shorts max+1") } catch (_: ArithmeticException) {}
        try { ShmMath.regionBytes(Int.MAX_VALUE, 2); fail("shorts Int.MAX") } catch (_: ArithmeticException) {}
    }

    @Test fun n19_aStringGoesInlineAtTheLimitAndThroughShmOneByteAbove() {
        assertTrue(ShmMath.goesInline(EngineConfig.INLINE_MAX_BYTES))
        assertFalse(ShmMath.goesInline(EngineConfig.INLINE_MAX_BYTES + 1))
        assertTrue(ShmMath.goesInline(0))
    }

    // ---------------------------------------------------------------------------------------------------- N20

    private class Timeline(val engine: ListLog) {
        class Entry(val engineBefore: Int, val level: BackendLadder.Level, val evt: String, val fields: Map<String, Any?>)
        val ladder = CopyOnWriteArrayList<Entry>()
        val log = BackendLadder.Log { level, evt, fields, _ -> ladder += Entry(engine.lines.size, level, evt, fields.toMap()) }
    }

    @Test fun n20_aDeathDuringOpenReadsAsOneStoryWithEngineDiedAndEngineRestartUnderTheSameLoadId() {
        val rig = Rig()
        val host = FakeHost()
        rig.binding.useHost(host)
        var killed = false
        host.onOpen = { if (it.backend == "GPU" && !killed) { killed = true; host.die() } }
        val store = SharedStore()
        val guard = BackendGuard(store, "build").also { it.recover(null) }
        val tl = Timeline(rig.log)
        val conn = ConnHost(rig.conn, EngineRole.BATCH)
        val proxy = EngineProxy({ conn }, "T", "t", { m, b, p -> guard.phase(m, b, p) })
        val llm = MemoryLlm(
            ContextWrapper(null), 4096, ModelRegistry.GEMMA_E2B, conn,
            ladder = { BackendLadder(guard, tl.log, role = "batch") }, guard = { guard }, gate = { null }, proxy = proxy,
        )
        llm.load(listOf("GPU", "CPU"))
        assertEquals("CPU", llm.backend)

        // 1. the ladder's own tape is a dead-clear story (engine_died=true on the failed GPU line)
        val tape = LogTape()
        tl.ladder.forEach { tape.write(it.level, it.evt, it.fields.map { (k, v) -> k to v }.toTypedArray(), null) }
        val gpu = tape.of("backend_failed").single()
        assertEquals(true, gpu["engine_died"])
        val loadId = gpu["load_id"] as String

        // 2. interleave the connection's lines by the order they were written, and read the story the way the file reader does
        val merged = ArrayList<LogFileTape.FileLine>()
        var enginePos = 0
        fun engineLine(l: ListLog.Line) = LogFileTape.FileLine(
            "%06d".format(merged.size), "batch", 1, "engine-batch.log",
            LogTape.Line(
                when (l.level) { 'e' -> BackendLadder.Level.ERROR; 'w' -> BackendLadder.Level.WARN; 'd' -> BackendLadder.Level.DEBUG; else -> BackendLadder.Level.INFO },
                l.event, l.f.filterValues { it != null }.mapValues { it.value.toString() }, null,
            ),
        )
        val engineLines = rig.log.lines.toList()
        for (e in tl.ladder) {
            while (enginePos < e.engineBefore) merged += engineLine(engineLines[enginePos++])
            merged += LogFileTape.FileLine(
                "%06d".format(merged.size), "main", 1, "tl.log",
                LogTape.Line(e.level, e.evt, e.fields.filterValues { it != null }.mapValues { it.value.toString() }, null),
            )
        }
        while (enginePos < engineLines.size) merged += engineLine(engineLines[enginePos++])
        val story = LogFileTape.assertEngineDeadClear(merged, loadId, role = "batch")
        assertNotNull(story)

        // exactly one death and one restart for the one kill, on the one load
        assertEquals(1, rig.log.count(EngineEvents.DIED))
        assertEquals(1, rig.log.count(EngineEvents.RESTART))
        assertEquals(loadId, rig.log.all(EngineEvents.DIED).single().f["load_id"])
    }

    // ---------------------------------------------------------------------------------------------------- N21

    /** A context that fails the test when anything asks for preferences (a host must not read main's prefs; AD-02 left no speech-engine pref). */
    private class NoPrefsContext : ContextWrapper(null) {
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences =
            throw AssertionError("the engine path asked for SharedPreferences '$name'")
    }

    @Test fun n21_theOpenRequestCarriesTheBackendOverrideAndNoSpeechEngineField() {
        val saved = BackendPolicy.override
        try {
            BackendPolicy.override = "GPU"
            val host = FakeHost()
            val guard = BackendGuard(SharedStore(), "build").also { it.recover(null) }
            val ctx: Context = NoPrefsContext()
            val proxy = EngineProxy({ host }, "T", "t", { m, b, p -> guard.phase(m, b, p) })
            MemoryLlm(ctx, 4096, ModelRegistry.GEMMA_E2B, host, ladder = { BackendLadder(guard) }, guard = { guard }, gate = { null }, proxy = proxy)
                .load(listOf("NPU", "GPU", "CPU"))
            val opens = host.ops.filterIsInstance<EngineOp.Open>()
            assertEquals("the override is the first and only request while it proves", listOf("GPU"), opens.map { it.backend })
            assertEquals(null, opens.single().language)
        } finally {
            BackendPolicy.override = saved
        }
        // AD-02: the request has no speech-engine field and the kinds name no speech engine
        val fields = EngineOp.Open::class.java.declaredFields.map { it.name.lowercase() }
        assertTrue("no speech-engine field in $fields", fields.none { "speech" in it || "whisper" in it || "parakeet" in it || "gemma" in it && it != "guardmodel" })
        assertEquals(listOf("LLM", "EMBED", "ASR", "DIARIZE"), EngineKind.values().map { it.name })
    }

    @Test fun n21_aCanaryOpenCarriesTheClientLanguage() {
        val host = FakeHost()
        val proxy = EngineProxy({ host }, "T", "t", { _, _, _ -> })
        proxy.open(EngineKind.ASR, ModelRegistry.CANARY_1B_V2.id, ModelRegistry.CANARY_1B_V2.id, "CPU", loadId = "L1", language = "cs")
        val open = host.ops.filterIsInstance<EngineOp.Open>().single()
        assertEquals(EngineKind.ASR, open.kind)
        assertEquals("cs", open.language)
        assertEquals("L1", open.tag.loadId)
        assertEquals("CPU", open.backend)
    }

    // ---------------------------------------------------------------------------------------------------- N23

    /** The J10 script: open, ask x3, embed, transcribe, throw, close. Returns what the caller saw, as strings. */
    private fun j10(host: EngineHost, throwing: () -> Unit): List<String> {
        val out = ArrayList<String>()
        var n = 0
        fun tag(op: String) = CallTag("r${n++}", "L", op)
        val opened = host.call(EngineOp.Open(tag("open"), EngineKind.LLM, "m", "g", "CPU", 512, true)) as EngineReply.Opened
        out += "open backend=${opened.backend} handle=${opened.handle} max=${opened.maxTokens}"
        repeat(3) { i ->
            val r = host.call(EngineOp.Ask(tag("ask"), opened.handle, "s", "u$i", null, 16, 0.0, 1)) as EngineReply.Text
            out += "ask ${r.text}"
        }
        val v = host.call(EngineOp.EmbedText(tag("embed_text"), opened.handle, "t", false)) as EngineReply.Vector
        out += "embed ${v.v.size} ${v.v.first()}"
        val t = host.call(EngineOp.Transcribe(tag("transcribe"), opened.handle, ShortArray(8))) as EngineReply.Text
        out += "transcribe ${t.text}"
        throwing()
        try {
            host.call(EngineOp.Ask(tag("ask"), opened.handle, "s", "boom", null, 16, 0.0, 1))
            out += "no exception"
        } catch (e: Throwable) {
            out += "threw ${e.javaClass.name}: ${e.message}"
        }
        host.closeAsync(EngineOp.Close(tag("close"), opened.handle))
        return out
    }

    @Test fun n23_aHostBehindTheConnectionAnswersTheJ10ScriptLikeTheHostItself() {
        // LocalEngineHost wraps EngineServer (native engines) and cannot load in a JVM test; the parity that matters here is that the
        // connection adds nothing: results, exception types and messages, and the closed handles are the host's own.
        fun script(h: FakeHost) {
            h.ask = { op -> if (op.user == "boom") throw IllegalStateException("exceeds maximum number of tokens") else EngineReply.Text("a:${op.user}", null) }
        }
        val direct = FakeHost().also(::script)
        val viaConn = FakeHost().also(::script)
        val rig = Rig()
        rig.binding.useHost(viaConn)
        val a = j10(direct) {}
        val b = j10(ConnHost(rig.conn, EngineRole.BATCH)) {}
        assertEquals(a, b)
        assertEquals(direct.closed.toList(), viaConn.closed.toList())
        // the connection sends the close as a Close op, the direct host records it only in closed
        assertEquals(direct.ops.map { it.javaClass }, viaConn.ops.filter { it !is EngineOp.Close }.map { it.javaClass })
        assertTrue(a.last().startsWith("threw java.lang.IllegalStateException: exceeds maximum"))
        assertEquals("the script left no strike or death", 0, rig.conn.view.deaths)
    }
}
