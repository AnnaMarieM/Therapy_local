package com.therapylocal.app.engine

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.system.Os
import android.system.OsConstants
import com.therapylocal.app.engine.runner.Runner
import com.therapylocal.app.log.AppLog
import com.therapylocal.app.log.LogCtx
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * The fault seam in debug (catalogue 2.3). Main code calls it directly; release, feel and qa have a no-op copy in src/noFaults.
 *
 * Main side: [plan] (set by androidTest or `EngineLabActivity` through [LabWiring.install]) is sent as `ArmFaults(plan, startNo)` right
 * after every attach, and [beforeBind] counts the start numbers per role and writes the pre-attach plan file.
 * Host side: [arm] installs the plan; [at] fires the rules that match a point; [runner] serves the `fake-*` model ids; [reply] corrupts replies.
 */
object Faults {
    /** The lab-only log events (not part of [EngineEvents.ALL]; a log assertion lists them as lab events). */
    const val EVT_ARMED = "lab_fault_armed"
    const val EVT_FIRED = "lab_fault_fired"
    private const val TAG = "Engine"

    // ------------------------------------------------------------------------------------------------------------ main side

    @Volatile
    var plan: String? = null

    private val starts = ConcurrentHashMap<EngineRole, AtomicInteger>()

    /** Forgets the start numbers; [LabWiring.install] calls it, so "crash on the first two starts" counts from the install. */
    fun resetStarts() = starts.clear()

    /** Counts a start for [role] and returns its number (1, 2, ...). */
    fun nextStart(role: EngineRole): Int = starts.getOrPut(role) { AtomicInteger() }.incrementAndGet()

    /** Before each bind: the start number, and the pre-attach file the host reads in `App.onCreate` (deleted when there is no plan). */
    fun beforeBind(context: Context, role: EngineRole): Int {
        val n = nextStart(role)
        val f = planFile(context, role)
        runCatching {
            val p = plan
            if (p == null) f.delete() else f.writeText(JSONObject().put("startNo", n).put("plan", p).toString())
        }
        return n
    }

    private fun planFile(context: Context, role: EngineRole) = File(context.noBackupFilesDir, "fault-plan-${role.proc}.json")

    // ------------------------------------------------------------------------------------------------------------- host side

    private class RuleState { val matches = AtomicInteger(); val fired = AtomicInteger() }

    private class Armed(val json: String, val plan: FaultPlan, val startNo: Int) {
        val states = plan.rules.map { RuleState() }
        val calls = AtomicInteger()
        val held = ArrayList<Any>()
        @Volatile var lastCallEnd = 0L
    }

    @Volatile
    private var armed: Armed? = null
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    /** The plan now armed, for the fake runners. */
    internal val current: FaultPlan? get() = armed?.plan

    fun scaleOf(): Double = armed?.plan?.scale ?: 1.0

    /** Host process start (`App.onCreate`): reads the pre-attach plan file, then fires `host_start`. */
    fun hostStart(context: Context, role: EngineRole) {
        val f = planFile(context, role)
        runCatching {
            if (f.exists()) {
                val j = JSONObject(f.readText())
                install(j.getString("plan"), j.getInt("startNo"))
            }
        }.onFailure { AppLog.e(TAG, "lab_fault_plan_invalid", LogCtx.NONE, it, "role" to role.proc) }
        at("host_start", role, null)
    }

    /** Installs [json]. The same plan with the same start number keeps its counters. Throws [FaultPlanException] on a bad plan. */
    fun install(json: String, startNo: Int) {
        val a = armed
        if (a != null && a.json == json && a.startNo == startNo) return
        val plan = FaultPlan.parse(json)
        armed = Armed(json, plan, startNo)
        AppLog.i(TAG, EVT_ARMED, LogCtx.NONE, "plan" to plan.id, "start_no" to startNo, "rules" to plan.rules.size)
    }

    /** Removes the armed plan (androidTest cleanup in the main process). */
    fun disarm() {
        armed = null
    }

    fun arm(op: EngineOp.ArmFaults): EngineReply {
        try {
            install(op.planJson, op.startNo)
        } catch (t: FaultPlanException) {
            AppLog.w(TAG, "lab_fault_plan_invalid", LogCtx.NONE, "req" to op.tag.requestId, "why" to t.message)
            throw t
        }
        return EngineReply.Done
    }

    private fun countsAsCall(op: EngineOp?) = op != null && op !is EngineOp.Status && op !is EngineOp.ArmFaults && op !is EngineOp.ClearLogs

    /** The server's hook points: `before:<op>`, `after:<op>`, `phase:<p>` and `before_open`, plus the service's own points. */
    fun at(point: String, role: EngineRole, op: EngineOp?, model: String? = null, backend: String? = null) {
        val a = armed ?: return
        when {
            point.startsWith("before:") -> {
                if (!countsAsCall(op)) return
                a.calls.incrementAndGet()
                fire(a, planPoint(point.removePrefix("before:")) ?: return, role, model, backend, op)
            }
            point.startsWith("after:") -> {
                if (!countsAsCall(op)) return
                a.lastCallEnd = System.nanoTime()
                val mark = a.lastCallEnd
                // After the binder reply has been written (about): post, so the reply is on its way when the rule fires.
                handler.postDelayed({ fire(a, "after_reply", role, model, backend, op) }, 30)
                handler.postDelayed({ if (a.lastCallEnd == mark) fire(a, "idle", role, model, backend, op) }, 100)
            }
            point.startsWith("phase:") -> fire(a, point.removePrefix("phase:"), role, model, backend, op)
            point == "before_open" -> Unit
            else -> fire(a, point, role, model, backend, op)
        }
    }

    private fun planPoint(opName: String) = when (opName) {
        "ask", "embed_text", "embed_audio", "transcribe", "close" -> opName
        else -> null
    }

    private fun fire(a: Armed, point: String, role: EngineRole, model: String?, backend: String?, op: EngineOp?) {
        for (r in a.plan.rules) {
            if (r.at != point || r.action is FaultPlan.Action.Reply || r.action is FaultPlan.Action.Perf) continue
            if (!matches(a, r, role, model, backend)) continue
            val s = a.states[r.index]
            val n = s.matches.incrementAndGet()
            if (n < r.on.nth) continue
            if (r.on.times != -1 && s.fired.get() >= r.on.times) continue
            s.fired.incrementAndGet()
            log(a, r, point, role, op)
            FaultActions.run(r.action, a.plan.scale, a.held, op)
        }
    }

    private fun matches(a: Armed, r: FaultPlan.Rule, role: EngineRole, model: String?, backend: String?): Boolean {
        if (r.role != null && r.role != "any" && r.role != role.proc) return false
        if (r.model != null && model != null && r.model != model) return false
        if (r.backend != null && backend != null && !r.backend.equals(backend, ignoreCase = true)) return false
        if (r.on.start != null && a.startNo !in r.on.start) return false
        if (r.on.call != null && a.calls.get() !in r.on.call) return false
        return true
    }

    private fun log(a: Armed, r: FaultPlan.Rule, point: String, role: EngineRole, op: EngineOp?) {
        AppLog.w(
            TAG, EVT_FIRED, LogCtx.NONE, "plan" to a.plan.id, "rule" to r.index, "point" to point, "action" to r.action.javaClass.simpleName,
            "role" to role.proc, "start_no" to a.startNo, "call_no" to a.calls.get(), "req" to op?.tag?.requestId, "op" to op?.tag?.op,
        )
        AppLog.flush()
    }

    /** A fake engine for a `fake-*` model id; null for every other id (the real runner then loads). */
    internal fun runner(op: EngineOp.Open, role: EngineRole, phases: PhaseSink): Pair<Runner, EngineReply.Opened>? =
        FakeRunners.open(op, role, phases)

    /** Applies the `reply` and `perf` rules of the op's call point to [reply]. */
    fun reply(role: EngineRole, op: EngineOp, reply: EngineReply): EngineReply {
        val a = armed ?: return reply
        val point = when (op) {
            is EngineOp.Ask -> "ask"
            is EngineOp.EmbedText -> "embed_text"
            is EngineOp.EmbedAudio -> "embed_audio"
            is EngineOp.Transcribe -> "transcribe"
            else -> return reply
        }
        var out = reply
        for (r in a.plan.rules) {
            if (r.at != point || (r.action !is FaultPlan.Action.Reply && r.action !is FaultPlan.Action.Perf)) continue
            if (!matches(a, r, role, null, null)) continue
            val s = a.states[r.index]
            if (s.matches.incrementAndGet() < r.on.nth) continue
            if (r.on.times != -1 && s.fired.get() >= r.on.times) continue
            s.fired.incrementAndGet()
            log(a, r, point, role, op)
            out = FaultActions.rewrite(r.action, out)
        }
        return out
    }
}

/** What each action does. Every one runs on the calling (binder) thread unless the plan names another. */
internal object FaultActions {
    private fun ms(v: Long, scale: Double) = (v * scale).toLong().coerceAtLeast(0)

    fun run(a: FaultPlan.Action, scale: Double, held: MutableList<Any>, op: EngineOp?) {
        when (a) {
            is FaultPlan.Action.Signal -> onThread(a.thread, "fault-signal") {
                if (a.delayMs > 0) sleepAll(ms(a.delayMs, scale))
                Os.kill(Os.getpid(), when (a.sig) {
                    "SEGV" -> OsConstants.SIGSEGV
                    "ABRT" -> OsConstants.SIGABRT
                    "BUS" -> OsConstants.SIGBUS
                    else -> OsConstants.SIGKILL
                })
            }
            is FaultPlan.Action.Exit -> System.exit(a.code)
            is FaultPlan.Action.Throw -> throw build(a.type, a.message, a.causes)
            is FaultPlan.Action.Uncaught -> uncaught(a)
            is FaultPlan.Action.JavaOom -> {
                val body = {
                    val chunks = ArrayList<LongArray>()
                    try {
                        while (true) chunks.add(LongArray(1 shl 20))
                    } catch (e: OutOfMemoryError) {
                        chunks.clear()
                        throw e
                    }
                }
                if (a.thread == "worker") onThread("worker", "fault-oom") { body() }
                else if (a.catch) runCatching { body() } else body()
            }
            is FaultPlan.Action.Alloc -> Memory.alloc(a, scale, held)
            is FaultPlan.Action.Leak -> synchronized(held) { held.add(Memory.touched(a.mbPerCall)) }
            is FaultPlan.Action.Hang -> onThread(a.where, "fault-hang") { sleepAll(if (a.ms < 0) Long.MAX_VALUE else ms(a.ms, scale)) }
            is FaultPlan.Action.Spin -> {
                val end = System.nanoTime() + a.ms * 1_000_000
                var x = 0L
                while (System.nanoTime() < end) x += x xor 0x9e3779b9L
                if (x == 42L) held.size
            }
            is FaultPlan.Action.Delay -> sleepAll(ms(a.ms, scale))
            FaultPlan.Action.WriteInput -> {
                // The input regions are sealed PROT_READ before they are sent; Java cannot write through such a mapping.
                val shm = android.os.SharedMemory.create("fault-input", 4096)
                try {
                    shm.setProtect(OsConstants.PROT_READ)
                    shm.mapReadWrite()
                } finally {
                    shm.close()
                }
            }
            is FaultPlan.Action.Reply, is FaultPlan.Action.Perf -> Unit // applied by Faults.reply
        }
    }

    fun rewrite(a: FaultPlan.Action, r: EngineReply): EngineReply = when (a) {
        is FaultPlan.Action.Perf -> if (r is EngineReply.Text && r.perf != null) {
            val p = r.perf
            EngineReply.Text(r.text, com.therapylocal.app.perf.LlmCall(p.promptTokens, a.prefillTps ?: p.prefillTokS, p.decodeTokens, a.decodeTps ?: p.decodeTokS, p.wallMs))
        } else r
        is FaultPlan.Action.Reply -> when {
            a.bytes != null -> when (r) {
                is EngineReply.Text -> EngineReply.Text("x".repeat(a.bytes), r.perf)
                is EngineReply.Vector -> EngineReply.Vector(FloatArray(a.bytes / 4) { 0.01f })
                else -> r
            }
            a.corrupt == "nan" -> if (r is EngineReply.Vector) EngineReply.Vector(r.v.copyOf().also { if (it.isNotEmpty()) it[0] = Float.NaN }) else r
            a.corrupt == "wrong_dim" -> if (r is EngineReply.Vector) EngineReply.Vector(r.v.copyOf(maxOf(1, r.v.size - 1))) else r
            a.corrupt == "empty" -> when (r) {
                is EngineReply.Vector -> EngineReply.Vector(FloatArray(0))
                is EngineReply.Text -> EngineReply.Text("", r.perf)
                else -> r
            }
            // An error reply with a message far over EngineErrors.MAX_MESSAGE.
            else -> throw RuntimeException("m".repeat(1 shl 20))
        }
        else -> r
    }

    private fun sleepAll(totalMs: Long) {
        val end = if (totalMs == Long.MAX_VALUE) Long.MAX_VALUE else System.nanoTime() / 1_000_000 + totalMs
        // Ignores interrupts: no CPU progress, and only a kill ends it.
        while (System.nanoTime() / 1_000_000 < end) {
            try {
                Thread.sleep(50)
            } catch (_: InterruptedException) {
            }
        }
    }

    /** Runs [body] on the binder (calling) thread, a new thread, or the host's main looper. A worker is joined for a while: a fatal body ends the process first. */
    private fun onThread(where: String, name: String, body: () -> Unit) {
        when (where) {
            "binder" -> body()
            "worker" -> {
                val t = Thread({ body() }, name).apply { isDaemon = true }
                t.start()
                if (name != "fault-hang") t.join(5_000)
            }
            else -> {
                android.os.Handler(Looper.getMainLooper()).post { body() }
                if (name != "fault-hang") Thread.sleep(2_000)
            }
        }
    }

    private fun uncaught(a: FaultPlan.Action.Uncaught) {
        val e = build(a.type, a.message ?: "fault uncaught", emptyList())
        when (a.thread) {
            "worker" -> Thread({ throw e }, "fault-uncaught").apply { start(); join(5_000) }
            "looper" -> {
                android.os.Handler(Looper.getMainLooper()).post { throw e }
                Thread.sleep(2_000)
            }
            // The thread's own handler, as the runtime does for a throwable that escapes a binder thread.
            else -> {
                val t = Thread.currentThread()
                (t.uncaughtExceptionHandler ?: Thread.getDefaultUncaughtExceptionHandler())?.uncaughtException(t, e)
            }
        }
    }

    fun build(type: String, message: String?, causes: List<String>): Throwable {
        val t = make(type, message)
        var tail = t
        for (c in causes) {
            val next = make(c, null)
            runCatching { tail.initCause(next) }
            tail = next
        }
        return t
    }

    private fun make(type: String, m: String?): Throwable = when (type) {
        "IllegalStateException" -> IllegalStateException(m)
        "IllegalArgumentException" -> IllegalArgumentException(m)
        "RuntimeException" -> RuntimeException(m)
        "UnsupportedOperationException" -> UnsupportedOperationException(m)
        "NullPointerException" -> NullPointerException(m)
        "IndexOutOfBoundsException" -> IndexOutOfBoundsException(m)
        "ArithmeticException" -> ArithmeticException(m)
        "ClassCastException" -> ClassCastException(m)
        "SecurityException" -> SecurityException(m)
        "Exception" -> Exception(m)
        "IOException" -> IOException(m)
        "OutOfMemoryError" -> OutOfMemoryError(m)
        "StackOverflowError" -> StackOverflowError(m)
        "AssertionError" -> AssertionError(m)
        "HostOnlyException" -> HostOnlyException(m)
        else -> throw FaultPlanException("type '$type' is not in the allow-list")
    }
}

/** Memory the OS has to back: every 4 KiB page is written, so lmkd sees real pressure. */
internal object Memory {
    fun touched(mb: Int): List<ByteArray> = List(mb) { ByteArray(1 shl 20).also { b -> for (i in b.indices step 4096) b[i] = 1 } }

    fun alloc(a: FaultPlan.Action.Alloc, scale: Double, held: MutableList<Any>) {
        val body = {
            val rnd = java.util.Random(a.seed)
            var done = 0
            while (done < a.mb) {
                val step = minOf(a.stepMb, a.mb - done)
                if (a.kind == "shm") {
                    val shm = android.os.SharedMemory.create("fault-mem", step.toLong().shl(20).toInt())
                    val map = shm.mapReadWrite()
                    if (a.touch) for (i in 0 until map.capacity() step 4096) map.put(i, 1)
                    if (a.hold) synchronized(held) { held.add(shm); held.add(map) } else { android.os.SharedMemory.unmap(map); shm.close() }
                } else {
                    val b = touched(step)
                    if (a.hold) synchronized(held) { held.add(b) }
                }
                done += step
                val jitter = if (a.jitterPct > 0) 1.0 + (rnd.nextInt(2 * a.jitterPct + 1) - a.jitterPct) / 100.0 else 1.0
                Thread.sleep((a.stepMs * scale * jitter).toLong().coerceAtLeast(0))
            }
            if (a.until == "killed") while (true) Thread.sleep(1_000)
        }
        if (a.until == "killed") Thread(body, "fault-alloc").apply { isDaemon = true; start() } else body()
    }
}
