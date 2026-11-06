package com.therapylocal.app.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** A [BackendGuard.Store] in memory, safe to share between threads, so a "new process" is a new guard on the same instance. */
internal class SharedStore : BackendGuard.Store {
    val map = ConcurrentHashMap<String, String>()
    override fun get(key: String) = map[key]
    override fun put(key: String, value: String?) {
        if (value == null) map.remove(key) else map[key] = value
    }
    override fun keys(): Set<String> = map.keys.toSet()
}

/** Everything a ladder wrote, with helpers that say whether a field log would read as one clear story. */
internal class LogTape : BackendLadder.Log {
    data class Line(val level: BackendLadder.Level, val evt: String, val fields: Map<String, Any?>, val t: Throwable?) {
        operator fun get(k: String) = fields[k]
    }

    val lines = CopyOnWriteArrayList<Line>()

    override fun write(level: BackendLadder.Level, evt: String, fields: Array<Pair<String, Any?>>, t: Throwable?) {
        lines += Line(level, evt, fields.toMap(), t)
    }

    fun evts() = lines.map { it.evt }
    fun of(evt: String) = lines.filter { it.evt == evt }
    fun decisive(backend: String) = lines.filter { it.evt in DECISIVE && it["backend_try"] == backend }

    /**
     * The "dead clear" rule for one load of [model]: one `ladder_start` naming [outcomes]' backends in order, one `load_id` and the model on
     * every line, one decisive event per candidate that was reached, a failure that names its type and cause chain, and one final line whose
     * summary lists every candidate in order with its outcome. [outcomes] is backend to "proven" | "failed" | "skipped" | "blocked", for the
     * candidates reached; [candidates] is every candidate (those after the proven one have no line at all). Returns the final line.
     */
    fun assertDeadClear(model: String, outcomes: List<Pair<String, String>>, candidates: List<String> = outcomes.map { it.first }): Line {
        val starts = of("ladder_start")
        assertEquals("exactly one ladder_start in ${evts()}", 1, starts.size)
        assertEquals("candidates on ladder_start", candidates.joinToString(","), starts.single()["candidates"])
        assertEquals("outcomes are the first candidates", candidates.take(outcomes.size), outcomes.map { it.first })
        candidates.drop(outcomes.size).forEach { b -> assertTrue("$b after the proven one is never touched", lines.none { it["backend_try"] == b }) }
        val ids = lines.map { it["load_id"] }.distinct()
        assertEquals("one load_id on every line: ${lines.map { it.evt to it["load_id"] }}", 1, ids.size)
        assertNotNull("load_id is set", ids.single())
        lines.forEach { assertEquals("model on ${it.evt}", model, it["model"]) }
        lines.forEach { assertTrue("unknown event ${it.evt}", it.evt in KNOWN) }

        for ((backend, outcome) in outcomes) {
            val d = decisive(backend)
            assertEquals("one decisive event for $backend in ${evts()}", 1, d.size)
            val expected = when (outcome) {
                "proven" -> "backend_proven"
                "failed" -> "backend_failed"
                "skipped" -> "backend_skipped"
                "blocked" -> "backend_skipped_blocked"
                else -> error(outcome)
            }
            assertEquals("outcome of $backend", expected, d.single().evt)
            if (outcome == "proven" || outcome == "failed") {
                val attempting = lines.indexOfFirst { it.evt == "backend_attempting" && it["backend_try"] == backend }
                assertTrue("$backend attempting is logged before its outcome", attempting in 0 until lines.indexOf(d.single()))
            }
            if (outcome == "skipped") assertFalse("skip reason of $backend is not blank", (d.single()["reason"] as String?).isNullOrBlank())
            if (outcome == "failed") {
                val f = d.single()
                val t = f.t
                assertNotNull("backend_failed carries the throwable", t)
                assertEquals("error is the class name", t!!.javaClass.name, f["error"])
                assertFalse("cause_chain is set", (f["cause_chain"] as String?).isNullOrBlank())
                assertEquals("is_oom", t is OutOfMemoryError, f["is_oom"])
            }
        }
        val last = lines.last()
        assertTrue("the last line is final: ${last.evt}", last.evt == "ladder_done" || last.evt == "ladder_failed")
        assertEquals("one final line", 1, lines.count { it.evt == "ladder_done" || it.evt == "ladder_failed" })
        assertEquals(if (outcomes.any { it.second == "proven" }) "ladder_done" else "ladder_failed", last.evt)
        val summary = last["summary"] as String
        val parts = if (summary.isEmpty()) emptyList() else summary.split(';')
        assertEquals("summary names every candidate in order: $summary", outcomes.map { it.first }, parts.map { it.substringBefore('=') })
        parts.zip(outcomes).forEach { (p, o) ->
            val shape = when (o.second) {
                "proven" -> Regex("[A-Z0-9_]+=proven\\(\\d+ms\\)")
                "failed" -> Regex("[A-Z0-9_]+=failed\\([A-Za-z0-9_$.]+,\\d+ms\\)")
                else -> Regex("[A-Z0-9_]+=${o.second}")
            }
            assertTrue("summary part '$p' reads as ${o.second}", shape.matches(p))
        }
        return last
    }

    companion object {
        val DECISIVE = setOf("backend_skipped", "backend_skipped_blocked", "backend_proven", "backend_failed")
        val KNOWN = DECISIVE + setOf(
            "ladder_start", "backend_attempting", "backend_gate_failed", "backend_blocked", "backend_guard_error", "backend_close_failed",
            "ladder_done", "ladder_failed",
        )
    }
}

/** A resource an `open` builds: counted so a test can say nothing leaked. */
internal class Engine(val backend: String, private val counter: Counter) : AutoCloseable {
    var closed = false
    init {
        counter.opened++
    }
    override fun close() {
        check(!closed) { "closed twice" }
        closed = true
        counter.closed++
    }
}

internal class Counter {
    var opened = 0
    var closed = 0
    fun assertNoLeak(alive: Int = 0) = assertEquals("opened $opened, closed $closed", opened - alive, closed)
}

internal fun assertNoText(where: String, text: String, vararg secrets: String) = secrets.forEach {
    if (text.contains(it)) fail("$where quotes the message text '$it': $text")
}
