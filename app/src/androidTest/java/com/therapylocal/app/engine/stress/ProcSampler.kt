package com.therapylocal.app.engine.stress

import android.os.Process
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject

/**
 * Samples the app's three processes (`main`, `live`, `batch`) from `/proc` while a scenario runs: which pids and in which order (a
 * restart gives a second pid), `oom_score_adj` at 10 Hz, PSS from `smaps_rollup` about twice a second, file descriptors, and system
 * `MemAvailable`. Same uid and debuggable, so no `dumpsys meminfo` per process per test (catalogue 4.1, finding 30).
 *
 * The main process is the instrumentation process itself (`Process.myPid()`), so it is always present.
 *
 *     val sampler = ProcSampler().also { it.start() }
 *     ... scenario ...
 *     val s = sampler.stop()
 *     assertEquals(2, s.pids("batch").size)                      // one restart
 *     assertTrue(s.oomAdj("batch")!!.first >= 0)
 *     s.writeTo(record)                                          // processes + memory sections of the data bundle
 *
 * [snapshot] is a one-shot reading for the `memory.before` / `memory.after` pair.
 */
class ProcSampler(private val periodMs: Long = 100, private val pssEvery: Int = 5) {
    class Snapshot(val mainPssMb: Long?, val livePssMb: Long?, val batchPssMb: Long?, val sysAvailMb: Long, val mainFds: Int?) {
        fun toJson(): JSONObject = JSONObject().put("main_pss_mb", mainPssMb ?: JSONObject.NULL).put("live_pss_mb", livePssMb ?: JSONObject.NULL)
            .put("batch_pss_mb", batchPssMb ?: JSONObject.NULL).put("sys_avail_mb", sysAvailMb)
    }

    class Summary internal constructor(
        private val pidsSeen: Map<String, List<Int>>, private val adj: Map<String, Pair<Int, Int>>, private val peakPss: Map<String, Long>,
        val minAvailMb: Long, val samples: Int, val before: Snapshot, val after: Snapshot,
    ) {
        /** Pids of the role in the order they were first seen. */
        fun pids(role: String): List<Int> = pidsSeen[role].orEmpty()

        /** `oom_score_adj` (min, max) over all samples of the role, or null when it never ran. */
        fun oomAdj(role: String): Pair<Int, Int>? = adj[role]

        /** Highest PSS in MB seen for the role. */
        fun peakPssMb(role: String): Long? = peakPss[role]

        /** Fills the `processes` and `memory` sections of the data bundle and the pids of each role. */
        fun writeTo(record: RunRecord) {
            for (role in listOf("main", "live", "batch")) pids(role).forEachIndexed { i, pid -> record.process(role, pid, gen = if (role == "main") null else i + 1) }
            record.section("memory", JSONObject().put("before", before.toJson()).put("after", after.toJson()).put("min_sys_avail_mb", minAvailMb))
            record.section("oom_adj", JSONObject().also { o -> adj.forEach { (r, a) -> o.put(r, JSONObject().put("min", a.first).put("max", a.second)) } })
            record.section("peak_pss_mb", JSONObject().also { o -> peakPss.forEach { (r, v) -> o.put(r, v) } })
        }

        fun toJson(): JSONObject = JSONObject().put("samples", samples).put("pids", JSONObject().also { o -> pidsSeen.forEach { (r, p) -> o.put(r, JSONArray(p)) } })
    }

    private val pidsSeen = LinkedHashMap<String, MutableList<Int>>()
    private val adj = HashMap<String, Pair<Int, Int>>()
    private val peak = HashMap<String, Long>()
    private var minAvail = Long.MAX_VALUE
    private var n = 0
    @Volatile private var running = false
    private var thread: Thread? = null
    private var before: Snapshot? = null

    /** One reading now. */
    fun snapshot(): Snapshot {
        fun pss(name: String) = ProcStats.pidsOf(name).firstOrNull()?.let { ProcStats.pssMb(it) }
        val mainPid = Process.myPid()
        return Snapshot(ProcStats.pssMb(mainPid), pss(ExitInfos.live), pss(ExitInfos.batch), ProcStats.availMb(), ProcStats.fdCount(mainPid))
    }

    fun start() {
        before = snapshot()
        running = true
        thread = Thread({
            var tick = 0
            while (running) {
                sampleOnce(tick++ % pssEvery == 0)
                SystemClock.sleep(periodMs) // the sampling period itself, not a synchronisation sleep
            }
        }, "proc-sampler").apply { isDaemon = true; start() }
    }

    private fun sampleOnce(withPss: Boolean) {
        val pids = mapOf("main" to listOf(Process.myPid()), "live" to ProcStats.pidsOf(ExitInfos.live), "batch" to ProcStats.pidsOf(ExitInfos.batch))
        synchronized(this) {
            n++
            for ((role, list) in pids) for (pid in list) {
                val seen = pidsSeen.getOrPut(role) { ArrayList() }
                if (pid !in seen) seen += pid
                ProcStats.oomScoreAdj(pid)?.let { a -> adj[role] = adj[role]?.let { minOf(it.first, a) to maxOf(it.second, a) } ?: (a to a) }
                if (withPss) ProcStats.pssMb(pid)?.let { p -> peak[role] = maxOf(peak[role] ?: 0, p) }
            }
            if (withPss) minAvail = minOf(minAvail, ProcStats.availMb())
        }
    }

    fun stop(): Summary {
        running = false
        thread?.join(2_000)
        sampleOnce(true)
        val after = snapshot()
        synchronized(this) {
            return Summary(pidsSeen.mapValues { it.value.toList() }, adj.toMap(), peak.toMap(), if (minAvail == Long.MAX_VALUE) after.sysAvailMb else minAvail, n, before ?: after, after)
        }
    }
}
