package com.therapylocal.app.engine

import org.json.JSONArray
import org.json.JSONObject

/** A fault plan was malformed: an unknown key or action, a bad value, or a plan over the size cap. Thrown so a test fails loud. */
class FaultPlanException(message: String) : IllegalArgumentException(message)

/** An exception only the debug lab can throw (it exists in no other build), so a test can tell a lab fault from a real one. */
class HostOnlyException(message: String?) : RuntimeException(message)

/**
 * The fault plan, format v1 (catalogue section 2.3). Pure Kotlin plus `org.json`, so the JVM tests parse it. Unknown keys and unknown
 * actions are rejected with a [FaultPlanException], never ignored.
 */
class FaultPlan(val id: String, val scale: Double, val gate: String?, val fake: FakeSpec, val rules: List<Rule>) {

    data class FakeSpec(
        val llmCtx: Int = 4096, val llmReply: String = "echo", val prefillTps: Double = 120.0, val decodeTps: Double = 18.0,
        val script: List<Pair<String, String>> = emptyList(), val embedDim: Int = 768, val asrMode: String = "crc", val asrText: String = "fake transcript",
        val residentMb: Int = 0,
    )

    /** Fires on the [nth] match and at most [times] times ([times] -1 is always). [start] and [call] null match any. */
    data class When(val start: List<Int>? = null, val call: List<Int>? = null, val nth: Int = 1, val times: Int = 1)

    data class Rule(val index: Int, val role: String?, val model: String?, val backend: String?, val at: String, val on: When, val action: Action)

    sealed class Action {
        data class Signal(val sig: String, val thread: String, val delayMs: Long) : Action()
        data class Exit(val code: Int) : Action()
        data class Throw(val type: String, val message: String?, val causes: List<String>) : Action()
        data class Uncaught(val type: String, val thread: String, val message: String?) : Action()
        data class JavaOom(val thread: String, val catch: Boolean) : Action()
        data class Alloc(
            val mb: Int, val stepMb: Int, val stepMs: Long, val kind: String, val touch: Boolean, val until: String, val hold: Boolean,
            val jitterPct: Int, val seed: Long,
        ) : Action()
        data class Leak(val mbPerCall: Int) : Action()
        data class Hang(val ms: Long, val where: String) : Action()
        data class Spin(val ms: Long) : Action()
        data class Delay(val ms: Long) : Action()
        data class Reply(val bytes: Int?, val corrupt: String?) : Action()
        object WriteInput : Action()
        data class Perf(val prefillTps: Double?, val decodeTps: Double?) : Action()
    }

    companion object {
        /** The marker of the format. It is also what `verifyNoFaultsInRelease` looks for in the dex string table of non-debug builds. */
        const val FAULT_PLAN_V1 = "FAULT_PLAN_V1"
        const val MAX_BYTES = 16 * 1024

        val POINTS = setOf(
            "host_start", "service_create", "bind", "create", "initialize", "test", "ask", "embed_text", "embed_audio", "transcribe",
            "close", "shutdown", "idle", "after_reply",
        )
        val PRE_ATTACH_POINTS = setOf("host_start", "service_create", "bind")
        private val ROLES = setOf("live", "batch", "main", "any")
        private val SIGS = setOf("SEGV", "ABRT", "BUS", "KILL")
        private val THREADS = setOf("binder", "worker", "main")
        private val THROWABLES = setOf(
            "IllegalStateException", "IllegalArgumentException", "RuntimeException", "UnsupportedOperationException", "NullPointerException",
            "IndexOutOfBoundsException", "ArithmeticException", "ClassCastException", "SecurityException", "Exception", "IOException",
            "OutOfMemoryError", "StackOverflowError", "AssertionError", "HostOnlyException",
        )

        fun parse(json: String): FaultPlan {
            if (json.toByteArray().size > MAX_BYTES) throw FaultPlanException("$FAULT_PLAN_V1: plan over $MAX_BYTES bytes")
            try {
                val o = JSONObject(json)
                o.only(setOf("v", "id", "scale", "gate", "fake", "rules"), "plan")
                if (o.optInt("v", -1) != 1) throw FaultPlanException("$FAULT_PLAN_V1: v must be 1")
                val scale = o.optDouble("scale", 1.0)
                if (!(scale > 0.0)) throw FaultPlanException("scale must be > 0")
                val gate = if (o.has("gate")) o.getString("gate").also { if (it != "allow_all") throw FaultPlanException("unknown gate '$it'") } else null
                val rules = o.optJSONArray("rules") ?: JSONArray()
                return FaultPlan(
                    o.optString("id", ""), scale, gate, o.optJSONObject("fake")?.let(::fake) ?: FakeSpec(),
                    (0 until rules.length()).map { rule(it, rules.getJSONObject(it)) },
                )
            } catch (e: FaultPlanException) {
                throw e
            } catch (e: Exception) {
                throw FaultPlanException("$FAULT_PLAN_V1: ${e.javaClass.simpleName}: ${e.message}")
            }
        }

        private fun fake(o: JSONObject): FakeSpec {
            o.only(setOf("llm", "embed", "asr", "resident_mb"), "fake")
            var f = FakeSpec(residentMb = o.optInt("resident_mb", 0))
            o.optJSONObject("llm")?.let { l ->
                l.only(setOf("ctx", "reply", "perf", "script"), "fake.llm")
                val perf = l.optJSONObject("perf")?.also { it.only(setOf("prefill_tps", "decode_tps"), "fake.llm.perf") }
                val script = l.optJSONArray("script")?.let { a ->
                    (0 until a.length()).map { i -> a.getJSONObject(i).also { it.only(setOf("contains", "reply"), "fake.llm.script") }.let { it.getString("contains") to it.getString("reply") } }
                } ?: emptyList()
                val reply = l.optString("reply", "echo")
                if (reply !in setOf("echo", "empty", "json", "script")) throw FaultPlanException("unknown fake.llm.reply '$reply'")
                f = f.copy(
                    llmCtx = l.optInt("ctx", 4096), llmReply = reply, script = script,
                    prefillTps = perf?.optDouble("prefill_tps", 120.0) ?: 120.0, decodeTps = perf?.optDouble("decode_tps", 18.0) ?: 18.0,
                )
            }
            o.optJSONObject("embed")?.let { e ->
                e.only(setOf("dim"), "fake.embed")
                f = f.copy(embedDim = e.optInt("dim", 768))
            }
            o.optJSONObject("asr")?.let { a ->
                a.only(setOf("mode", "text"), "fake.asr")
                val mode = a.optString("mode", "crc")
                if (mode !in setOf("crc", "fixed")) throw FaultPlanException("unknown fake.asr.mode '$mode'")
                f = f.copy(asrMode = mode, asrText = a.optString("text", "fake transcript"))
            }
            return f
        }

        private fun rule(i: Int, o: JSONObject): Rule {
            o.only(setOf("role", "model", "backend", "at", "when", "do"), "rules[$i]")
            val role = o.optString("role", "").ifEmpty { null }?.also { if (it !in ROLES) throw FaultPlanException("rules[$i]: unknown role '$it'") }
            val at = o.getString("at")
            if (at !in POINTS) throw FaultPlanException("rules[$i]: unknown point '$at'")
            val w = o.optJSONObject("when")?.let { w ->
                w.only(setOf("start", "call", "nth", "times"), "rules[$i].when")
                When(ints(w, "start"), ints(w, "call"), w.optInt("nth", 1), w.optInt("times", 1))
            } ?: When()
            if (w.nth < 1 || w.times == 0 || w.times < -1) throw FaultPlanException("rules[$i]: nth must be >= 1 and times >= 1 or -1")
            val action = action(i, o.getJSONObject("do"))
            if (action is Action.Reply || action is Action.Perf) {
                if (at !in setOf("ask", "embed_text", "embed_audio", "transcribe")) throw FaultPlanException("rules[$i]: reply and perf need a call point")
            }
            if (at in PRE_ATTACH_POINTS && w.call != null) throw FaultPlanException("rules[$i]: 'call' has no meaning before attach")
            return Rule(i, role, o.optString("model", "").ifEmpty { null }, o.optString("backend", "").ifEmpty { null }, at, w, action)
        }

        private fun ints(o: JSONObject, k: String): List<Int>? = when (val v = if (o.has(k) && !o.isNull(k)) o.get(k) else null) {
            null -> null
            is Int -> listOf(v)
            is JSONArray -> (0 until v.length()).map { v.getInt(it) }
            else -> throw FaultPlanException("'$k' must be a number or an array of numbers")
        }

        private fun action(i: Int, d: JSONObject): Action {
            val kind = d.getString("kind")
            val where = "rules[$i].do($kind)"
            fun thread(key: String, allowed: Set<String>, def: String) = d.optString(key, def).also { if (it !in allowed) throw FaultPlanException("$where: bad $key '$it'") }
            return when (kind) {
                "signal" -> {
                    d.only(setOf("kind", "sig", "thread", "delay_ms"), where)
                    val sig = d.getString("sig")
                    if (sig !in SIGS) throw FaultPlanException("$where: unknown sig '$sig'")
                    Action.Signal(sig, thread("thread", THREADS, "binder"), d.optLong("delay_ms", 0))
                }
                "exit" -> { d.only(setOf("kind", "code"), where); Action.Exit(d.optInt("code", 1)) }
                "throw" -> {
                    d.only(setOf("kind", "type", "message", "causes"), where)
                    val type = d.getString("type")
                    if (type !in THROWABLES) throw FaultPlanException("$where: type '$type' is not in the allow-list")
                    val c = d.optJSONArray("causes")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
                    c.forEach { if (it !in THROWABLES) throw FaultPlanException("$where: cause '$it' is not in the allow-list") }
                    Action.Throw(type, if (d.has("message")) d.getString("message") else null, c)
                }
                "uncaught" -> {
                    d.only(setOf("kind", "type", "thread", "message"), where)
                    val type = d.optString("type", "RuntimeException")
                    if (type !in THROWABLES) throw FaultPlanException("$where: type '$type' is not in the allow-list")
                    Action.Uncaught(type, thread("thread", setOf("worker", "looper", "binder"), "worker"), if (d.has("message")) d.getString("message") else null)
                }
                "java_oom" -> { d.only(setOf("kind", "thread", "catch"), where); Action.JavaOom(thread("thread", setOf("binder", "worker"), "binder"), d.optBoolean("catch", false)) }
                "alloc" -> {
                    d.only(setOf("kind", "mb", "step_mb", "step_ms", "alloc_kind", "touch", "until", "hold", "jitter_pct", "seed"), where)
                    Action.Alloc(
                        d.getInt("mb"), d.optInt("step_mb", 16), d.optLong("step_ms", 50), d.optString("alloc_kind", "shm").also { if (it !in setOf("shm", "java")) throw FaultPlanException("$where: bad alloc_kind") },
                        d.optBoolean("touch", true), d.optString("until", "done").also { if (it !in setOf("killed", "done")) throw FaultPlanException("$where: bad until") },
                        d.optBoolean("hold", true), d.optInt("jitter_pct", 0), d.optLong("seed", 1),
                    )
                }
                "leak" -> { d.only(setOf("kind", "mb_per_call"), where); Action.Leak(d.getInt("mb_per_call")) }
                "hang" -> { d.only(setOf("kind", "ms", "where"), where); Action.Hang(d.optLong("ms", -1), thread("where", THREADS, "binder")) }
                "spin" -> { d.only(setOf("kind", "ms"), where); Action.Spin(d.getLong("ms")) }
                "delay" -> { d.only(setOf("kind", "ms"), where); Action.Delay(d.getLong("ms")) }
                "reply" -> {
                    d.only(setOf("kind", "bytes", "corrupt"), where)
                    val corrupt = if (d.has("corrupt")) d.getString("corrupt") else null
                    if (corrupt != null && corrupt !in setOf("nan", "wrong_dim", "empty", "huge_msg")) throw FaultPlanException("$where: bad corrupt '$corrupt'")
                    if ((corrupt == null) == !d.has("bytes")) throw FaultPlanException("$where: exactly one of bytes, corrupt")
                    Action.Reply(if (d.has("bytes")) d.getInt("bytes") else null, corrupt)
                }
                "write_input" -> { d.only(setOf("kind"), where); Action.WriteInput }
                "perf" -> {
                    d.only(setOf("kind", "prefill_tps", "decode_tps"), where)
                    Action.Perf(if (d.has("prefill_tps")) d.getDouble("prefill_tps") else null, if (d.has("decode_tps")) d.getDouble("decode_tps") else null)
                }
                else -> throw FaultPlanException("rules[$i]: unknown action '$kind'")
            }
        }

        private fun JSONObject.only(allowed: Set<String>, where: String) {
            for (k in keys()) if (k !in allowed) throw FaultPlanException("$FAULT_PLAN_V1: unknown key '$k' in $where")
        }
    }
}
