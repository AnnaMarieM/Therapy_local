package com.therapylocal.app.engine.stress

import com.therapylocal.app.engine.FaultPlan
import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds fault plans (format v1, catalogue 2.3) without hand-written JSON. Every plan is parsed with the real `FaultPlan.parse` when it is
 * built, so a typo fails in the test that wrote it, loudly (unknown keys and actions are rejected).
 *
 * The rule's fields are named like the JSON: `at` (where it fires), `when` ([start], [call], [nth], [times]) and `do` (an action from [Do]).
 *
 *     val plan = Plans.plan(
 *         "A07-batch-llm-initialize-sigabrt",
 *         Plans.rule(at = "initialize", role = "batch", model = "fake-llm", backend = "GPU", start = listOf(1), action = Do.signal("ABRT")),
 *     )
 *     lab.install(plan)                         // or lab.startScenario(Scenario.PROCESSING, plan)
 *
 * Plans from the lane: [scale] defaults to the lane's `-e scale`, and the fake models' resident size to `-e fakeModelMb`.
 * Host-script plans (`qa/stress/lib.sh`) use the same JSON, so a plan can move between an instrumented test and a script.
 */
object Plans {
    /** The fake engines' behaviour (`fake` section). All values default to the catalogue's lab defaults. */
    fun fake(
        llmCtx: Int = 4096, llmReply: String = "echo", prefillTps: Double? = null, decodeTps: Double? = null, script: List<Pair<String, String>> = emptyList(),
        embedDim: Int = 768, asrMode: String = "crc", asrText: String? = null, residentMb: Int = LaneArgs.fakeModelMb,
    ): JSONObject {
        val llm = JSONObject().put("ctx", llmCtx).put("reply", llmReply)
        if (prefillTps != null || decodeTps != null) {
            llm.put("perf", JSONObject().also { p -> prefillTps?.let { p.put("prefill_tps", it) }; decodeTps?.let { p.put("decode_tps", it) } })
        }
        if (script.isNotEmpty()) llm.put("script", JSONArray(script.map { JSONObject().put("contains", it.first).put("reply", it.second) }))
        val asr = JSONObject().put("mode", asrMode).also { a -> asrText?.let { a.put("text", it) } }
        return JSONObject().put("llm", llm).put("embed", JSONObject().put("dim", embedDim)).put("asr", asr).put("resident_mb", residentMb)
    }

    /** One rule. [role] is `live`, `batch`, `main` or `any` (null = any). [start]/[call] null match any number. */
    fun rule(
        at: String, action: JSONObject, role: String? = null, model: String? = null, backend: String? = null,
        start: List<Int>? = null, call: List<Int>? = null, nth: Int = 1, times: Int = 1,
    ): JSONObject {
        val w = JSONObject().put("nth", nth).put("times", times)
        start?.let { w.put("start", JSONArray(it)) }
        call?.let { w.put("call", JSONArray(it)) }
        val r = JSONObject().put("at", at).put("when", w).put("do", action)
        role?.let { r.put("role", it) }; model?.let { r.put("model", it) }; backend?.let { r.put("backend", it) }
        return r
    }

    /**
     * The plan JSON. [gate] `allow_all` (the lab default) lifts the memory/availability gates so the GPU candidate is tried on the x86
     * emulator; null keeps the real gates. [scale] multiplies every delay, step and hang of the host side (slow lanes).
     */
    fun plan(id: String, vararg rules: JSONObject, scale: Double = LaneArgs.scale, gate: String? = "allow_all", fake: JSONObject = fake()): String {
        val o = JSONObject().put("v", 1).put("id", id).put("scale", scale).put("fake", fake).put("rules", JSONArray(rules.toList()))
        gate?.let { o.put("gate", it) }
        return o.toString().also { FaultPlan.parse(it) } // fail loud, here
    }

    /** A plan with the fakes and no fault: the baseline of a scenario, and the control run of a pair. */
    fun clean(id: String = "clean", fake: JSONObject = fake()): String = plan(id, fake = fake)

    /** The actions (`do`). One function per `do.kind` of the catalogue. */
    object Do {
        fun signal(sig: String, thread: String = "binder", delayMs: Long = 0) =
            JSONObject().put("kind", "signal").put("sig", sig).put("thread", thread).put("delay_ms", delayMs)

        fun exit(code: Int = 1) = JSONObject().put("kind", "exit").put("code", code)

        /** `throw`: [type] from the fixed allow-list (or `HostOnlyException`). */
        fun throwing(type: String, message: String? = null, causes: List<String> = emptyList()) =
            JSONObject().put("kind", "throw").put("type", type).also { o -> message?.let { o.put("message", it) }; if (causes.isNotEmpty()) o.put("causes", JSONArray(causes)) }

        /** [thread]: `worker` (default), `looper` or `binder`. */
        fun uncaught(type: String = "RuntimeException", thread: String = "worker", message: String? = null) =
            JSONObject().put("kind", "uncaught").put("type", type).put("thread", thread).also { o -> message?.let { o.put("message", it) } }

        fun javaOom(thread: String = "binder", catch: Boolean = false) = JSONObject().put("kind", "java_oom").put("thread", thread).put("catch", catch)

        /** Real memory pressure. [kind] `shm` or `java`; [until] `done` or `killed` (allocate until lmkd kills the host). */
        fun alloc(mb: Int, stepMb: Int = 16, stepMs: Long = 50, kind: String = "shm", touch: Boolean = true, until: String = "done", hold: Boolean = true, jitterPct: Int = 0, seed: Long = 1) =
            JSONObject().put("kind", "alloc").put("mb", mb).put("step_mb", stepMb).put("step_ms", stepMs).put("alloc_kind", kind).put("touch", touch)
                .put("until", until).put("hold", hold).put("jitter_pct", jitterPct).put("seed", seed)

        fun leak(mbPerCall: Int) = JSONObject().put("kind", "leak").put("mb_per_call", mbPerCall)

        /** [ms] -1 hangs forever. [where] `binder`, `worker` or `main`. No CPU progress. */
        fun hang(ms: Long = -1, where: String = "binder") = JSONObject().put("kind", "hang").put("ms", ms).put("where", where)

        /** A busy loop: the host's CPU time advances (D11). */
        fun spin(ms: Long) = JSONObject().put("kind", "spin").put("ms", ms)

        fun delay(ms: Long) = JSONObject().put("kind", "delay").put("ms", ms)

        /** A reply of [bytes] (G: binder size). Only at call points. */
        fun reply(bytes: Int) = JSONObject().put("kind", "reply").put("bytes", bytes)

        /** A corrupt reply: `nan`, `wrong_dim`, `empty` or `huge_msg`. Only at call points. */
        fun corrupt(how: String) = JSONObject().put("kind", "reply").put("corrupt", how)

        fun writeInput() = JSONObject().put("kind", "write_input")

        fun perf(prefillTps: Double? = null, decodeTps: Double? = null) =
            JSONObject().put("kind", "perf").also { o -> prefillTps?.let { o.put("prefill_tps", it) }; decodeTps?.let { o.put("decode_tps", it) } }
    }
}
