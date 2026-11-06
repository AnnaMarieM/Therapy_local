package com.therapylocal.app.engine.stress

import android.os.SystemClock
import com.therapylocal.app.log.AppLog
import com.therapylocal.app.models.LogFileTape
import com.therapylocal.app.models.LogFileTape.FileLine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/** One line of the story, as text for regexes: `evt=engine_died role=batch gen=1 ...` (header fields left out). */
internal fun FileLine.text(): String = "evt=$evt " + line.fields.entries.joinToString(" ") { "${it.key}=${it.value}" }

private val TS = ThreadLocal.withInitial { SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US) }

/** The line's `ts=` as epoch milliseconds (device local time zone, the zone `AppLog` formats in). */
internal fun FileLine.epochMs(): Long = TS.get()!!.parse(ts)!!.time

/**
 * Waits for log events by name and fields across the files of every process: `tl*.log` (main), `engine-live*.log`, `engine-batch*.log`
 * (and `backend.log` on request), merged by `ts=` through `LogFileTape`. Anti-flake rule 1: this is how a test synchronises, never a sleep.
 * Every wait is bounded (rule 2); a timeout fails with the last 40 lines of every file, so the failure shows what each process did.
 *
 * [defaultTimeoutMs] should come from the lab tuning times the lane scale (`EngineLab.logs` does this). Polls every [pollMs] (50 ms) after
 * `AppLog.flush()` in this process; the hosts flush their phase lines before the native step, other lines arrive within the poll.
 *
 *     val logs = lab.logs
 *     val m = logs.mark()                                           // lines before this point do not count
 *     lab.startScenario(Scenario.PROCESSING, plan)
 *     val died = logs.until("engine_died", after = m, where = mapOf("role" to "batch", "call" to "open"))
 *     val restarted = logs.until("engine_restart", after = m, where = mapOf("gen" to 2))
 *     assertTrue(restarted.epochMs() >= died.epochMs())
 *
 * `where` values: a String or number (exact text), a [Regex] (contains a match), a `(String) -> Boolean`, or `null` (the field must be absent).
 */
internal class LogWait(
    private val dir: () -> File? = { AppLog.logDir() },
    private val defaultTimeoutMs: Long = 15_000,
    private val pollMs: Long = 50,
) {
    /**
     * A position in the logs: how many `ts=` lines each file held. Lines up to the mark are ignored by `after = mark`. Counted per file, so a
     * host line that arrives late with an earlier timestamp cannot move an old line past the mark. Valid until the logs are cleared or a
     * file rotates (rotation is far beyond one test's volume).
     */
    class Mark internal constructor(internal val perFile: Map<String, Int>)

    private class Entry(val line: FileLine, val ordinal: Int)

    /** Every `ts=` line with its ordinal in its own file, merged by `ts=` (stable). */
    private fun load(includeBackend: Boolean = false): List<Entry> {
        AppLog.flush(200)
        val files = dir()?.listFiles()?.filter { it.name.endsWith(".log") && (includeBackend || it.name != "backend.log") }?.sortedBy { it.name }.orEmpty()
        val all = files.flatMap { f ->
            val parsed = runCatching { f.readLines() }.getOrDefault(emptyList()).mapNotNull { LogFileTape.parseLine(it, f.name) }
            parsed.mapIndexed { i, l -> Entry(l, i) }
        }
        return all.sortedBy { it.line.ts } // stable
    }

    private fun List<Entry>.after(m: Mark?): List<FileLine> =
        (if (m == null) this else filter { it.ordinal >= (m.perFile[it.line.file] ?: 0) }).map { it.line }

    /** Every `ts=` line of every process in time order. `backend.log` repeats main lines, so it is left out unless [includeBackend]. */
    fun lines(includeBackend: Boolean = false): List<FileLine> = load(includeBackend).map { it.line }

    fun mark(): Mark = Mark(load().groupBy { it.line.file }.mapValues { it.value.size })

    /** Lines that match, in time order. All filters are optional and combined with AND. */
    fun find(
        evt: String? = null, proc: String? = null, where: Map<String, Any?> = emptyMap(), regex: Regex? = null, after: Mark? = null,
    ): List<FileLine> = select(load().after(after), evt, proc, where, regex)

    fun count(evt: String? = null, proc: String? = null, where: Map<String, Any?> = emptyMap(), regex: Regex? = null, after: Mark? = null): Int =
        find(evt, proc, where, regex, after).size

    /** The first match once there is one. Throws [AssertionError] with the log tails after [timeoutMs] (default: the lab bound). */
    fun until(
        evt: String? = null, proc: String? = null, where: Map<String, Any?> = emptyMap(), regex: Regex? = null, after: Mark? = null,
        timeoutMs: Long = defaultTimeoutMs,
    ): FileLine = untilCount(1, evt, proc, where, regex, after, timeoutMs).first()

    /** At least [n] matches; returns them all. */
    fun untilCount(
        n: Int, evt: String? = null, proc: String? = null, where: Map<String, Any?> = emptyMap(), regex: Regex? = null, after: Mark? = null,
        timeoutMs: Long = defaultTimeoutMs,
    ): List<FileLine> {
        val t0 = SystemClock.elapsedRealtime()
        while (true) {
            val hit = find(evt, proc, where, regex, after)
            if (hit.size >= n) return hit
            if (SystemClock.elapsedRealtime() - t0 > timeoutMs) {
                throw AssertionError("log wait timed out after $timeoutMs ms: wanted $n x evt=$evt proc=$proc where=$where regex=$regex, saw ${hit.size}\n${tails()}")
            }
            Thread.sleep(pollMs) // the poll interval of the wait itself, bounded above
        }
    }

    /**
     * The events [evts] in this order (other lines may lie between), each after the previous one. Returns the matched lines. Fails with the
     * missing step and the tails. Use for the dead-clear story: `died, restart, ...`.
     */
    fun untilSequence(vararg evts: String, after: Mark? = null, timeoutMs: Long = defaultTimeoutMs): List<FileLine> {
        val t0 = SystemClock.elapsedRealtime()
        while (true) {
            val all = load().after(after)
            var i = 0
            val got = ArrayList<FileLine>()
            for (e in evts) {
                val j = (i until all.size).firstOrNull { all[it].evt == e } ?: break
                got += all[j]
                i = j + 1
            }
            if (got.size == evts.size) return got
            if (SystemClock.elapsedRealtime() - t0 > timeoutMs) {
                throw AssertionError("log sequence timed out after $timeoutMs ms at step ${got.size} (${evts.getOrNull(got.size)}) of ${evts.toList()}\n${tails()}")
            }
            Thread.sleep(pollMs) // see until()
        }
    }

    /** Asserts that nothing matching appeared since [after] (reads once; use after the story has ended, never as a wait). */
    fun assertNone(evt: String? = null, proc: String? = null, where: Map<String, Any?> = emptyMap(), regex: Regex? = null, after: Mark? = null) {
        val hit = find(evt, proc, where, regex, after)
        if (hit.isNotEmpty()) throw AssertionError("unexpected log lines (evt=$evt where=$where): " + hit.take(5).joinToString(" | ") { "${it.file} ${it.text()}" })
    }

    /**
     * U2's stall: the time between the last [evt] before [boundary] and the first [evt] after it (for example `live_cue_tick` around an
     * `engine_died`). Null when there is no line on one side. Waits (bounded) for a line after the boundary.
     */
    fun stallMs(evt: String, boundary: FileLine, timeoutMs: Long = defaultTimeoutMs): Long? {
        val t0 = SystemClock.elapsedRealtime()
        while (true) {
            val all = lines().filter { it.evt == evt }
            val before = all.lastOrNull { it.epochMs() <= boundary.epochMs() }
            val after = all.firstOrNull { it.epochMs() > boundary.epochMs() }
            if (after != null) return before?.let { after.epochMs() - it.epochMs() }
            if (SystemClock.elapsedRealtime() - t0 > timeoutMs) return null
            Thread.sleep(pollMs) // see until()
        }
    }

    /** The last [n] raw lines of each log file, for failure messages and the `excerpts` section of the data bundle. */
    fun tails(n: Int = 40): String =
        dir()?.listFiles()?.filter { it.name.endsWith(".log") }?.sortedBy { it.name }.orEmpty().joinToString("\n") { f ->
            "--- ${f.name} (last $n)\n" + runCatching { f.readLines().takeLast(n).joinToString("\n") }.getOrDefault("(unreadable)")
        }

    /** Lines around the first match of [evt]: [before] lines before and [after] after it, merged across processes (data bundle excerpts, +-20). */
    fun excerpt(evt: String, before: Int = 20, after: Int = 20): List<String> {
        val all = lines()
        val i = all.indexOfFirst { it.evt == evt }
        if (i < 0) return emptyList()
        return all.subList((i - before).coerceAtLeast(0), (i + after + 1).coerceAtMost(all.size)).map { "${it.file}: ${it.ts} ${it.text()}" }
    }

    companion object {
        private fun ok(want: Any?, got: Any?): Boolean = when (want) {
            null -> got == null
            is Regex -> got != null && want.containsMatchIn(got.toString())
            is Function1<*, *> -> got != null && (@Suppress("UNCHECKED_CAST") (want as (String) -> Boolean))(got.toString())
            else -> got != null && got.toString() == want.toString()
        }

        internal fun select(
            all: List<FileLine>, evt: String?, proc: String?, where: Map<String, Any?>, regex: Regex?,
        ): List<FileLine> = all.filter { l ->
            (evt == null || l.evt == evt) && (proc == null || l.proc == proc) &&
                where.all { (k, v) -> ok(v, l[k]) } && (regex == null || regex.containsMatchIn(l.text()))
        }
    }
}

/** Waits on processes, by `/proc` (anti-flake rule 1: `ProcWait.gone(pid)`). Bounded; the 25 ms poll is the wait's own. */
object ProcWait {
    fun gone(pid: Int, timeoutMs: Long): Boolean {
        val end = SystemClock.elapsedRealtime() + timeoutMs
        while (ProcStats.alive(pid)) {
            if (SystemClock.elapsedRealtime() > end) return false
            Thread.sleep(25)
        }
        return true
    }

    /** True once neither `:live` nor `:batch` runs. */
    fun hostsGone(timeoutMs: Long): Boolean {
        val end = SystemClock.elapsedRealtime() + timeoutMs
        while (ProcStats.pidsOf(ExitInfos.live).isNotEmpty() || ProcStats.pidsOf(ExitInfos.batch).isNotEmpty()) {
            if (SystemClock.elapsedRealtime() > end) return false
            Thread.sleep(25)
        }
        return true
    }

    /** The pid of the process named [processName] once it runs, or null. */
    fun appears(processName: String, timeoutMs: Long): Int? {
        val end = SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            ProcStats.pidsOf(processName).firstOrNull()?.let { return it }
            if (SystemClock.elapsedRealtime() > end) return null
            Thread.sleep(25)
        }
    }
}

/** `StateFlow.first { }` with a timeout, for a test thread: `liveCues.paused.firstWithin(5_000) { it }`. Fails with [AssertionError]. */
fun <T> Flow<T>.firstWithin(timeoutMs: Long, what: String = "flow value", predicate: (T) -> Boolean): T = runBlocking {
    try {
        withTimeout(timeoutMs) { first(predicate) }
    } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
        throw AssertionError("$what not seen within $timeoutMs ms")
    }
}
