package com.therapylocal.app.models

import com.therapylocal.app.config.BackendPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BackendLadderTest {
    private class MapStore : BackendGuard.Store {
        val map = HashMap<String, String>()
        override fun get(key: String) = map[key]
        override fun put(key: String, value: String?) {
            if (value == null) map.remove(key) else map[key] = value
        }
        override fun keys(): Set<String> = map.keys.toSet()
    }

    private class Engine(val backend: String) {
        var closed = false
    }

    private val guard = BackendGuard(MapStore(), "b").also { it.recover(null) }
    private val events = ArrayList<String>()
    private val lines = ArrayList<Pair<String, Map<String, Any?>>>()
    private val ladder = BackendLadder(guard, log = { _, evt, f, _ ->
        lines += evt to f.toMap()
        events += "$evt:${f.toMap()["backend_try"]}"
    })
    private val opened = ArrayList<Engine>()

    private fun open(failing: Set<String>, afterOpen: Set<String> = emptySet()): (String) -> Engine = { b ->
        if (b in failing) throw IllegalStateException("$b init failed")
        Engine(b).also { opened += it; if (b in afterOpen) throw IllegalStateException("$b self-test failed") }
    }

    @Test
    fun theBestBackendThatWorksIsUsedAndEachStepIsNoted() {
        val r = ladder.load("m", listOf("NPU", "GPU", "CPU"), open = open(failing = setOf("NPU")), close = { it.closed = true })
        assertEquals("GPU", r.backend)
        assertEquals(
            listOf("ladder_start:null", "backend_attempting:NPU", "backend_failed:NPU", "backend_attempting:GPU", "backend_proven:GPU", "ladder_done:GPU"),
            events,
        )
        // One load, one id on every line, so a field log can be read as a story.
        assertEquals(1, lines.map { it.second["load_id"] }.distinct().size)
        assertEquals("attempting", lines.first { it.first == "backend_attempting" }.second["note"])
        assertEquals("proven on this device", lines.first { it.first == "backend_proven" }.second["note"])
        assertEquals("NPU=failed(IllegalStateException,0ms);GPU=proven(0ms)", lines.last().second["summary"].toString().replace(Regex("\\d+ms"), "0ms"))
        assertEquals(BackendGuard.State.PROVEN, guard.record("m", "GPU")!!.state)
        assertEquals(BackendGuard.State.FAILED, guard.record("m", "NPU")!!.state)
    }

    @Test
    fun anEngineThatFailsAfterItWasBuiltIsClosedBeforeTheNextTry() {
        val r = ladder.load("m", listOf("GPU", "CPU"), open = open(failing = emptySet(), afterOpen = setOf("GPU")), close = { it.closed = true })
        assertEquals("CPU", r.backend)
        // The failing open closes what it built itself in the real engines; here the ladder never got it, so only the CPU one is alive.
        assertTrue(opened.filter { it.backend == "CPU" }.none { it.closed })
    }

    @Test
    fun aGateOrABlockSkipsWithoutTrying() {
        repeat(BackendGuard.BLOCK_UNPROVEN) { guard.attempting("m", "NPU"); guard.failed("m", "NPU", "x") }
        val r = ladder.load("m", listOf("NPU", "GPU", "CPU"), gate = { if (it == "GPU") "not enough free memory" else null }, open = open(emptySet()), close = {})
        assertEquals("CPU", r.backend)
        assertEquals(listOf("CPU"), opened.map { it.backend })
        assertEquals(2, r.skipped.size)
    }

    @Test
    fun aGateThatThrowsCountsAsASkipNotACrash() {
        val r = ladder.load("m", listOf("GPU", "CPU"), gate = { if (it == "GPU") error("no service") else null }, open = open(emptySet()), close = {})
        assertEquals("CPU", r.backend)
    }

    @Test
    fun aRemoteErrorKeepsItsOriginalNameInReasonsAndTheCauseChain() {
        val remote = com.therapylocal.app.engine.RemoteEngineException("LiteRtLmJniException", "boom")
        assertEquals("LiteRtLmJniException", BackendLadder.name(remote))
        assertEquals("IllegalStateException", BackendLadder.name(IllegalStateException("x")))
        val e = assertThrows(BackendLadder.NoBackend::class.java) {
            ladder.load("m", listOf("GPU"), open = { throw remote }, close = {})
        }
        assertTrue(e.message!!, e.message!!.contains("GPU: boom"))
        assertTrue(lines.toString(), lines.any { l -> l.second.values.any { it.toString().contains("failed(LiteRtLmJniException") } })
        val chained = IllegalStateException("outer", com.therapylocal.app.engine.RemoteEngineException("a.b.Inner", null))
        assertEquals("IllegalStateException <- a.b.Inner", BackendLadder.causeChain(chained, messages = false).replace("java.lang.", ""))
    }

    @Test
    fun whenNothingWorksOneExceptionCarriesEveryReason() {
        val e = assertThrows(BackendLadder.NoBackend::class.java) {
            ladder.load("m", listOf("GPU", "CPU"), open = open(failing = setOf("GPU", "CPU")), close = {})
        }
        assertTrue(e.message!!.contains("GPU: GPU init failed"))
        assertTrue(e.message!!.contains("CPU: CPU init failed"))
    }

    @Test
    fun theSettingsOverviewShowsWhatRanAndWhyTheRestDidNot() {
        ladder.load("m", listOf("NPU", "GPU", "CPU"), gate = { if (it == "NPU") "dispatch library missing" else null }, open = open(failing = setOf("GPU")), close = {})
        val view = guard.overview().single()
        assertEquals("CPU", view.selected)
        assertEquals(listOf("NPU", "GPU", "CPU"), view.backends.keys.toList())
        assertEquals("dispatch library missing", view.backends.getValue("NPU").second!!.reason)
        assertEquals(BackendGuard.State.FAILED, view.backends.getValue("GPU").first!!.state)
        assertEquals(BackendGuard.State.PROVEN, view.backends.getValue("CPU").first!!.state)

        guard.reset()
        assertTrue(guard.overview().isEmpty())
    }

    @Test
    fun aForcedBackendStillFallsBackToTheCpu() {
        assertEquals(listOf("NPU", "GPU", "CPU"), BackendPolicy.order(listOf("NPU", "GPU", "CPU"), "auto"))
        assertEquals(listOf("GPU", "CPU"), BackendPolicy.order(listOf("NPU", "GPU", "CPU"), "gpu"))
        assertEquals(listOf("CPU"), BackendPolicy.order(listOf("NPU", "GPU", "CPU"), "cpu"))
        assertEquals("a probe asking for the GPU only gets the GPU only", listOf("GPU"), BackendPolicy.order(listOf("GPU"), "auto"))
    }
}
