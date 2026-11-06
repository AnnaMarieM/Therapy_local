package com.therapylocal.app.engine.stress

import java.io.File

/**
 * A 10 s perfetto trace for tests annotated [Trace] (A04, B01, D10, K01, K14; catalogue 4.2): sched, cpu frequency, low-memory kills and
 * process stats, started through the shell with the config on stdin, written to `/data/misc/perfetto-traces`, then copied to
 * `files/stress/perfetto/<name>.pftrace` where the lane's pull step finds it. Best effort: a missing `perfetto` binary or a refused start
 * returns null and never fails the test. `EngineLab` does this by itself for `@Trace` tests; call it directly for a different window.
 *
 *     val trace = Perfetto.start("K01")          // returns the name, or null if not available
 *     ... scenario ...
 *     val info = Perfetto.finish(trace!!)        // waits (bounded) for the trace to end, copies it; JSON-able info or null
 */
object Perfetto {
    private const val DIR = "/data/misc/perfetto-traces"

    private val CONFIG = """
        duration_ms: 10000
        buffers { size_kb: 16384 fill_policy: RING_BUFFER }
        data_sources { config { name: "linux.ftrace" ftrace_config {
          ftrace_events: "sched/sched_switch" ftrace_events: "sched/sched_process_exit" ftrace_events: "power/cpu_frequency"
          ftrace_events: "lowmemorykiller/lowmemory_kill" ftrace_events: "oom/oom_score_adj_update" ftrace_events: "task/task_newtask"
          atrace_categories: "am" atrace_apps: "com.therapylocal.app"
        } } }
        data_sources { config { name: "linux.process_stats" process_stats_config { scan_all_processes_on_start: true proc_stats_poll_ms: 1000 } } }
    """.trimIndent()

    fun start(name: String): String? {
        val safe = name.replace(Regex("[^A-Za-z0-9_.-]"), "_")
        val r = Shell.script("perfetto --background -c - --txt -o $DIR/$safe.pftrace <<'PFCFG'\n$CONFIG\nPFCFG")
        return safe.takeIf { r.exit == 0 }
    }

    /** Waits (bounded, 13 s) until no perfetto runs, copies the trace to the stress dir, returns its path and size. */
    fun finish(name: String): String? {
        val end = System.nanoTime() + 13_000_000_000L
        while (Shell.out("pidof perfetto").isNotBlank() && System.nanoTime() < end) Thread.sleep(250) // bounded wait for the 10 s duration
        val out = File(LaneArgs.stressDir(), "perfetto").also { it.mkdirs() }
        val dest = File(out, "$name.pftrace")
        Shell.script("cp $DIR/$name.pftrace ${Shell.quote(dest.path)} && chmod 666 ${Shell.quote(dest.path)}")
        return dest.takeIf { it.exists() && it.length() > 0 }?.let { "${it.name}:${it.length()}" }
    }
}
