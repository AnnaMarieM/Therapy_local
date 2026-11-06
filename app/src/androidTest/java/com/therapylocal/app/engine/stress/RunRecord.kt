package com.therapylocal.app.engine.stress

import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import org.junit.AssumptionViolatedException
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.io.File

/**
 * The per-run data recorder: one line per test in `files/stress/results.jsonl`, schema `tl.stress.v1` (catalogue 4.1). Written in
 * `finished`, so also when the test failed or was skipped. A test (or the `EngineLab` rule, later) adds what it measured:
 *
 *     @get:Rule val record = RunRecord()
 *     record.timing("fault_to_engine_died", 38); record.process("batch", pid, gen = 1); record.exit(...)
 *
 * No patient text goes in here (the fakes use canaries only; L5 also runs over this file).
 */
class RunRecord : TestWatcher() {
    private val timings = JSONObject()
    private val processes = JSONArray()
    private val exits = JSONArray()
    private val dropbox = JSONArray()
    private val extra = JSONObject()
    private var failure: JSONObject? = null
    private var skipReason: String? = null
    private var status = "passed"
    private var startedAt = 0L

    fun timing(name: String, ms: Long?) { timings.put(name, ms ?: JSONObject.NULL) }

    fun process(role: String, pid: Int, gen: Int? = null) {
        val o = (0 until processes.length()).map { processes.getJSONObject(it) }.firstOrNull { it.getString("role") == role }
            ?: JSONObject().put("role", role).put("pids", JSONArray()).put("gens", JSONArray()).also { processes.put(it) }
        o.getJSONArray("pids").put(pid)
        gen?.let { o.getJSONArray("gens").put(it) }
        ProcStats.oomScoreAdj(pid)?.let { adj ->
            val a = o.optJSONObject("oom_adj") ?: JSONObject().put("min", adj).put("max", adj).also { x -> o.put("oom_adj", x) }
            a.put("min", minOf(a.getInt("min"), adj)).put("max", maxOf(a.getInt("max"), adj))
        }
    }

    fun exit(process: String, pid: Int, reason: String, status: Int, kind: String?, description: String? = null, expectedKind: String? = null) {
        exits.put(JSONObject().put("process", process).put("pid", pid).put("reason", reason).put("status", status)
            .put("kind", kind ?: JSONObject.NULL).put("expected_kind", expectedKind ?: JSONObject.NULL).put("description", description ?: JSONObject.NULL))
    }

    fun dropbox(tag: String) { dropbox.put(tag) }

    /** Free-form top-level section (`ui`, `memory`, `guard`, `fds`, `story`, `excerpts`), as the schema names them. */
    fun section(name: String, value: Any) { extra.put(name, value) }

    override fun starting(d: Description) { startedAt = System.nanoTime() }

    override fun failed(e: Throwable, d: Description) {
        status = "failed"
        failure = JSONObject().put("assertion", e.javaClass.simpleName).put("message", e.message?.take(2000) ?: JSONObject.NULL)
            .put("first_divergent_event", JSONObject.NULL)
    }

    override fun skipped(e: AssumptionViolatedException, d: Description) {
        status = "skipped"
        skipReason = e.message?.take(200)
    }

    override fun finished(d: Description) {
        runCatching { write(d) } // a recorder failure must never fail the test
    }

    private fun write(d: Description) {
        val case = d.getAnnotation(Case::class.java)
        val tier = when {
            d.getAnnotation(Lmk::class.java) != null || d.testClass?.getAnnotation(Lmk::class.java) != null -> "LMK"
            d.getAnnotation(Slow::class.java) != null || d.testClass?.getAnnotation(Slow::class.java) != null -> "SLOW"
            d.getAnnotation(Core::class.java) != null || d.testClass?.getAnnotation(Core::class.java) != null -> "CORE"
            else -> "FULL"
        }
        val line = JSONObject()
            .put("schema", SCHEMA).put("lane", LaneArgs.lane).put("api", Build.VERSION.SDK_INT).put("device", Build.MODEL)
            .put("ram_mb", LaneArgs.memTotalMb()).put("image", LaneArgs.image).put("scale", LaneArgs.scale)
            .put("override", LaneArgs.backendOverride).put("fake_model_mb", LaneArgs.fakeModelMb).put("commit", LaneArgs.commit)
            .put("test", case?.id ?: d.methodName).put("class", d.testClass?.simpleName ?: d.className).put("method", d.methodName)
            .put("tier", tier).put("status", status).put("skip_reason", skipReason ?: JSONObject.NULL)
            .put("duration_ms", (System.nanoTime() - startedAt) / 1_000_000)
            .put("plan_id", case?.plan?.ifEmpty { null } ?: JSONObject.NULL)
            .put("timings_ms", timings).put("processes", processes).put("exits", exits).put("dropbox", dropbox)
            .put("failure", failure ?: JSONObject.NULL)
        extra.keys().forEach { line.put(it, extra.get(it)) }
        // org.json escapes newlines, so one JSON object is one line.
        File(LaneArgs.stressDir(), "results.jsonl").appendText(line.toString() + "\n")
    }

    companion object {
        const val SCHEMA = "tl.stress.v1"
    }
}
