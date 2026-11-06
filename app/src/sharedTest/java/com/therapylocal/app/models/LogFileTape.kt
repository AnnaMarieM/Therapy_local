package com.therapylocal.app.models

import com.therapylocal.app.engine.EngineEvents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.io.File

/**
 * The same log story as [LogTape], read back from the files the app wrote: `tl*.log` (main), `backend.log` (main, pinned) and
 * `engine-*.log` (the hosts). Lines are merged by `ts=` (stable for equal stamps, as `AppLog.tailAll` does) and carry `proc` and `pid`.
 * `backend.log` repeats lines of `tl.log`, so by default it is left out of the story and only read for the proc check.
 */
internal object LogFileTape {
    /** A parsed line: the [LogTape.Line] of the story plus where it came from. */
    class FileLine(val ts: String, val proc: String, val pid: Int?, val file: String, val line: LogTape.Line) {
        val evt get() = line.evt
        operator fun get(k: String) = line[k]
    }

    private val LEVELS = mapOf('D' to BackendLadder.Level.DEBUG, 'I' to BackendLadder.Level.INFO, 'W' to BackendLadder.Level.WARN, 'E' to BackendLadder.Level.ERROR)

    /** `k=v k="quoted \" value"` into pairs, in order. Unquotes the way `AppLog.format` quotes. */
    fun fields(text: String): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        var i = 0
        while (i < text.length) {
            while (i < text.length && text[i] == ' ') i++
            val eq = text.indexOf('=', i)
            if (eq < 0) break
            val key = text.substring(i, eq)
            i = eq + 1
            val sb = StringBuilder()
            if (i < text.length && text[i] == '"') {
                i++
                while (i < text.length && text[i] != '"') {
                    if (text[i] == '\\' && i + 1 < text.length) {
                        i++
                        sb.append(if (text[i] == 'n') '\n' else text[i])
                    } else {
                        sb.append(text[i])
                    }
                    i++
                }
                i++
            } else {
                while (i < text.length && text[i] != ' ') sb.append(text[i++])
            }
            out += key to sb.toString()
        }
        return out
    }

    /** One line of a log file, or null when it is not a `ts=` line (a stack trace continuation). */
    fun parseLine(raw: String, file: String): FileLine? {
        if (!raw.startsWith("ts=")) return null
        val f = fields(raw)
        val m = f.toMap()
        val evt = m["evt"] ?: return null
        val rest = f.filter { it.first !in HEADER }.associate { it.first to (it.second as Any?) }
        val level = LEVELS[m["lvl"]?.firstOrNull()] ?: BackendLadder.Level.INFO
        return FileLine(m.getValue("ts"), m["proc"] ?: "main", m["pid"]?.toIntOrNull(), file, LogTape.Line(level, evt, rest, null))
    }

    private val HEADER = setOf("ts", "lvl", "evt")

    /** Every `ts=` line of [files], merged by timestamp (stable). [includeBackend] adds the pinned `backend.log`. */
    fun read(files: List<File>, includeBackend: Boolean = false): List<FileLine> {
        val all = files.filter { it.name.endsWith(".log") && (includeBackend || it.name != "backend.log") }.sortedBy { it.name }
            .flatMap { f -> f.readLines().mapNotNull { parseLine(it, f.name) } }
        return all.sortedBy { it.ts } // sortedBy is stable
    }

    /** The logs directory: `tl*.log` plus `engine-*.log` (and `backend.log` on request), as a [LogTape] the existing helpers can read. */
    fun tape(dir: File, includeBackend: Boolean = false): LogTape =
        LogTape().also { t -> read(dir.listFiles()?.toList().orEmpty(), includeBackend).forEach { t.lines += it.line } }

    /** Group by `load_id`: every line that carries one, in time order. */
    fun byLoadId(lines: List<FileLine>): Map<String, List<FileLine>> =
        lines.filter { it["load_id"] != null }.groupBy { it["load_id"] as String }

    /**
     * L1, the dead-clear load, from files. For [loadId]: `engine_died` then `backend_failed(engine_died=true)` then (any time after the death,
     * the restart is a connection event and may carry no `load_id`) `engine_restart` then `ladder_done`. Returns the lines of the story.
     */
    fun assertEngineDeadClear(lines: List<FileLine>, loadId: String, role: String? = null): List<FileLine> {
        val story = lines.filter { it["load_id"] == loadId || it.evt == EngineEvents.RESTART }
        val evts = story.map { it.evt }
        val died = story.indexOfFirst { it.evt == EngineEvents.DIED && it["load_id"] == loadId && (role == null || it["role"] == role) }
        assertTrue("engine_died for $loadId in $evts", died >= 0)
        val failed = story.indexOfFirst { it.evt == "backend_failed" && it["load_id"] == loadId && it["engine_died"] == "true" }
        assertTrue("backend_failed(engine_died=true) for $loadId in $evts", failed > died)
        val restart = story.indexOfFirst { it.evt == EngineEvents.RESTART && story.indexOf(it) > died && (role == null || it["role"] == role) }
        assertTrue("engine_restart after the death in $evts", restart > died)
        val done = story.indexOfFirst { it.evt == "ladder_done" && it["load_id"] == loadId }
        assertTrue("ladder_done after engine_restart in $evts", done > restart)
        return story
    }

    /** L6: every host line says which process wrote it; `backend.log` holds main lines only. */
    fun assertProcTags(dir: File) {
        for (f in dir.listFiles().orEmpty().sortedBy { it.name }) {
            val role = Regex("engine-(live|batch)").find(f.name)?.groupValues?.get(1)
            val parsed = f.takeIf { it.name.endsWith(".log") }?.readLines()?.mapNotNull { parseLine(it, f.name) }.orEmpty()
            for (l in parsed) {
                when {
                    role != null -> {
                        assertEquals("proc on ${f.name} ${l.evt}", role, l.proc)
                        assertTrue("pid on ${f.name} ${l.evt}", l.pid != null)
                    }
                    f.name == "backend.log" || f.name.startsWith("tl") -> assertEquals("proc on ${f.name} ${l.evt}", "main", l.proc)
                }
            }
        }
        assertFalse("no log file of an unknown shape", dir.listFiles().orEmpty().any { it.name.endsWith(".log") && Regex("^(tl.*|backend|engine-(live|batch).*)\\.log$").matches(it.name).not() })
    }
}
