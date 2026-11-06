package com.therapylocal.app.engine

import android.app.ApplicationExitInfo
import com.therapylocal.app.config.EngineTuning
import com.therapylocal.app.models.BackendGuard
import java.util.PriorityQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.Condition
import java.util.concurrent.locks.ReentrantLock

/**
 * The virtual clock of the JVM suite (catalogue N05, N06, N09). [now] is the monotonic clock, [wallMs] is `wallOffset + now`.
 * [advance] moves time only; [jumpWall] moves the wall clock alone (the idle timer and the backoff must not care, N09).
 * With [autoAdvance] a thread that waits on the connection lock moves virtual time forward by the whole wait, so single-threaded tests
 * need no sleeps; concurrent tests turn it off (the waiter then only waits 2 ms of real time) and move time themselves.
 *
 *     val clock = ManualClock()
 *     clock.advance(250); clock.jumpWall(3_600_000)     // an hour passes on the wall clock only
 *     assertEquals(250, clock.nowMs())
 *
 * Pair it with [ManualTime] (timers run on advance) or [StepExecutor] (timers and tasks run only when the test says so).
 */
internal open class ManualClock : EngineClock {
    @Volatile var now = 0L
    @Volatile var wallOffset = 1_700_000_000_000L
    @Volatile var autoAdvance = true

    /** Set by a [StepExecutor] built on this clock, so a waiting thread can run its due tasks and timers first. */
    @Volatile internal var steps: StepExecutor? = null

    override fun nowMs() = now
    override fun wallMs() = wallOffset + now

    /** Moves the monotonic clock (and with it the wall clock) by [ms]. Does not run tasks; [ManualTime] and [StepExecutor] override or add that. */
    open fun advance(ms: Long) { now += ms }

    /** The wall clock jumps by [ms] (positive or negative); the monotonic clock does not move. */
    fun jumpWall(ms: Long) { wallOffset += ms }

    /** One step of a waiting thread: run what is due within [max] ms, or move time by [max]. */
    protected open fun step(max: Long) {
        val s = steps
        if (s != null) s.stepWithin(max) else now += max
    }

    override fun await(lock: ReentrantLock, cond: Condition, ms: Long) {
        if (!autoAdvance) {
            cond.await(2, TimeUnit.MILLISECONDS)
            return
        }
        val holds = lock.holdCount
        repeat(holds) { lock.unlock() }
        try {
            step(ms)
        } finally {
            repeat(holds) { lock.lock() }
        }
    }
}

/**
 * A virtual clock and scheduler in one: tasks run inline, timers run when time reaches them ([advance], or a waiting thread with
 * [autoAdvance]). The default of [Rig]. For tests that need to choose the order of tasks and timers, use [StepExecutor] instead.
 */
internal class ManualTime : ManualClock(), EngineScheduler {
    private class Task(val due: Long, val seq: Long, val run: Runnable) : Cancelable {
        @Volatile var cancelled = false
        override fun cancel() { cancelled = true }
    }
    private val queue = PriorityQueue<Task>(compareBy<Task>({ it.due }, { it.seq }))
    private var seq = 0L
    val executed = AtomicInteger(0)

    override fun execute(task: Runnable) {
        executed.incrementAndGet()
        task.run()
    }

    override fun schedule(delayMs: Long, task: Runnable): Cancelable = synchronized(queue) {
        Task(now + delayMs.coerceAtLeast(0), seq++, task).also { queue.add(it) }
    }

    /** Runs the earliest due task if it is due within [max] ms (time moves to it), else moves time by [max]. */
    override fun step(max: Long) {
        steps?.let { it.stepWithin(max); return } // a StepExecutor on this clock owns tasks and timers
        val t = synchronized(queue) {
            while (queue.isNotEmpty() && queue.peek().cancelled) queue.poll()
            val head = queue.peek()
            if (head != null && head.due <= now + max) queue.poll() else null
        }
        if (t == null) {
            now += max
            return
        }
        if (t.due > now) now = t.due
        t.run.run()
    }

    /** Moves time by [ms], running every task that falls due, in order. */
    override fun advance(ms: Long) {
        steps?.let { it.advance(ms); return }
        val end = now + ms
        while (true) {
            val t = synchronized(queue) {
                while (queue.isNotEmpty() && queue.peek().cancelled) queue.poll()
                val head = queue.peek()
                if (head != null && head.due <= end) queue.poll() else null
            } ?: break
            if (t.due > now) now = t.due
            t.run.run()
        }
        now = end
    }

    fun pending(): Int = synchronized(queue) { queue.count { !it.cancelled } }
}

internal class ListLog : EngineLog {
    data class Line(val level: Char, val event: String, val f: Map<String, Any?>)
    val lines = CopyOnWriteArrayList<Line>()
    override fun log(level: Char, event: String, fields: List<Pair<String, Any?>>) {
        lines += Line(level, event, fields.toMap())
    }
    fun all(event: String) = lines.filter { it.event == event }
    fun count(event: String) = all(event).size
    fun names() = lines.map { it.event }
}

internal class RecSettler(private val order: MutableList<String>? = null) : HandleSettler {
    val unloaded = CopyOnWriteArrayList<Pair<String, String>>()
    val died = CopyOnWriteArrayList<Triple<String, String, BackendGuard.LastExit>>()
    override fun unloaded(guardModel: String, backend: String) {
        unloaded += guardModel to backend
        order?.add("settle.unloaded")
    }
    override fun diedWhileLoaded(guardModel: String, backend: String, exit: BackendGuard.LastExit) {
        died += Triple(guardModel, backend, exit)
        order?.add("settle.died")
    }
}

/**
 * A scripted fake of the platform half (`EngineBinding`). Callbacks are delivered straight away on the calling thread (which stands in
 * for the executor).
 *
 * How a test drives it:
 *  - [onBind] decides what a bind does ("connect", "refuse", "silent", "null", "died", "attach_failed");
 *  - [script] answers or fails calls, [die] ends a process (exit record, release of blocked callers, callbacks);
 *  - [fire] delivers any of the five connection callbacks for any generation, also an old one (N24, AMS redelivery);
 *  - [useHost] routes every call to a [FakeHost] (so `FakeHost.die()` ends the process the way a real death does).
 *
 *     val binding = FakeBinding(ManualClock(), "pkg:batch")
 *     binding.fire(gen = 1, "disconnected")            // a stale callback after the generation ended
 *     val host = FakeHost().also { binding.useHost(it) }
 *     host.hangOn = { it is EngineOp.Ask }; ...; host.die()
 */
internal class FakeBinding(val time: ManualClock, val processName: String, val pidBase: Int = 1000, val events: MutableList<String>? = null, val tag: String = "") : EngineBinding {
    override lateinit var listener: EngineBinding.Listener

    inner class Proc(val gen: Int, val pid: Int) {
        @Volatile var alive = true
        val deadLatch = CountDownLatch(1)
        var nextHandle = 1
        val bornWall = time.wallMs()
    }

    val procs = HashMap<Int, Proc>()
    val binds = CopyOnWriteArrayList<Int>()
    val bindAt = HashMap<Int, Long>()
    val deathAt = HashMap<Int, Long>()
    val unbinds = CopyOnWriteArrayList<Int>()
    val released = CopyOnWriteArrayList<Int>()
    val killed = CopyOnWriteArrayList<Int>()
    val shutdowns = CopyOnWriteArrayList<Pair<Int, String>>()
    val calls = CopyOnWriteArrayList<Pair<Int, EngineOp>>()
    val records = CopyOnWriteArrayList<Pair<ExitRecord, Int>>() // record and the number of lookups after which it becomes visible
    @Volatile var queries = 0
    @Volatile var maxAlive = 0

    /** What [bind] does: connect at once (default), refuse, stay silent (no callback), or answer with another callback. */
    var onBind: (Int) -> String = { "connect" }
    /** Runs on every call after the dead check; return a reply, throw, or block with [block]; null = the default reply. */
    var script: (Proc, EngineOp) -> EngineReply? = { _, _ -> null }
    /** What a `shutdown` does to the process: exit at once (default) or ignore it. */
    var onShutdown: (Proc) -> Boolean = { true }
    val inCall = CountDownLatch(1)
    private val pids = AtomicInteger(0)
    private var routed: FakeHost? = null

    /** Delivers a connection callback by name to the listener: connected, disconnected, bindingDied, nullBinding, binderDied, attachFailed. */
    fun fire(gen: Int, callback: String, pid: Int = pidBase) {
        when (callback) {
            "connected" -> listener.connected(gen, procs[gen]?.pid ?: pid)
            "disconnected" -> listener.disconnected(gen)
            "bindingDied" -> listener.bindingDied(gen)
            "nullBinding" -> listener.nullBinding(gen)
            "binderDied" -> listener.binderDied(gen)
            "attachFailed" -> listener.attachFailed(gen, "attach_failed")
            else -> error("unknown callback $callback")
        }
    }

    /**
     * Routes every call to [host] after [script] had its say. A [DeadObjectException][android.os.DeadObjectException] from the host becomes a
     * [BindingFailure] DEATH (what `RemoteFailures.classify` does), host phases reach the listener, and `host.die()` ends the current process
     * with the host's [FakeHost.exitReason] and [FakeHost.exitStatus]. Each new bind revives the host as a new generation.
     */
    fun useHost(host: FakeHost) {
        routed = host
        host.onDied = {
            procs.values.filter { it.alive }.maxByOrNull { it.gen }?.let { die(it, host.exitReason, host.exitStatus) }
        }
    }

    override fun bind(gen: Int): Boolean {
        binds += gen
        bindAt[gen] = time.now
        events?.add("$tag.bind")
        return when (onBind(gen)) {
            "refuse" -> false
            "silent" -> true
            "null" -> { listener.nullBinding(gen); true }
            "died" -> { listener.bindingDied(gen); true }
            "attach_failed" -> { listener.attachFailed(gen, "attach_failed"); true }
            else -> {
                val p = Proc(gen, pidBase + pids.incrementAndGet())
                procs[gen] = p
                routed?.let { if (it.dead) it.revive() }
                maxAlive = maxOf(maxAlive, procs.values.count { it.alive })
                listener.connected(gen, p.pid)
                true
            }
        }
    }

    override fun unbind(gen: Int) { unbinds += gen }
    override fun release(gen: Int) { released += gen }

    override fun call(gen: Int, op: EngineOp): EngineReply {
        calls += gen to op
        events?.add("$tag.${op.tag.op}")
        val p = procs[gen] ?: throw BindingFailure(BindingFailure.Kind.DEATH)
        if (!p.alive) throw BindingFailure(BindingFailure.Kind.DEATH)
        script(p, op)?.let { return it }
        routed?.let { h ->
            return try {
                h.call(op) { tag, model, backend, phase -> listener.phase(tag.requestId, tag.loadId, model, backend, phase) }
            } catch (e: android.os.DeadObjectException) {
                throw BindingFailure(BindingFailure.Kind.DEATH)
            }
        }
        return when (op) {
            is EngineOp.Open -> EngineReply.Opened(p.nextHandle++, op.backend, 512, null)
            is EngineOp.Ask -> EngineReply.Text("ok", null)
            is EngineOp.EmbedText, is EngineOp.EmbedAudio -> EngineReply.Vector(FloatArray(4))
            is EngineOp.Transcribe -> EngineReply.Text("t", null)
            is EngineOp.Status -> EngineReply.HostStatus(p.pid, 0, 0, emptyList())
            else -> EngineReply.Done
        }
    }

    /** Blocks the calling (binder-like) thread until the process dies, then fails like a transact on a dead process. */
    fun block(p: Proc): Nothing {
        inCall.countDown()
        if (!p.deadLatch.await(20, TimeUnit.SECONDS)) throw AssertionError("a blocked call was never released")
        throw BindingFailure(BindingFailure.Kind.DEATH)
    }

    override fun shutdown(gen: Int, reason: String) {
        shutdowns += gen to reason
        events?.add("$tag.shutdown")
        val p = procs[gen] ?: return
        if (onShutdown(p)) die(p, ApplicationExitInfo.REASON_EXIT_SELF, HostExit.SHUTDOWN.code)
    }

    override fun kill(pid: Int) {
        killed += pid
        procs.values.firstOrNull { it.pid == pid }?.let { die(it, ApplicationExitInfo.REASON_SIGNALED, 9) }
    }

    override fun pidAlive(pid: Int) = procs.values.any { it.pid == pid && it.alive }

    /** The process ends: its callers are released, an exit record is written (when [reason] is set) and the callbacks fire. */
    fun die(p: Proc, reason: Int?, status: Int = 0, lag: Int = 0, callbacks: List<String> = listOf("binderDied")) {
        if (!p.alive) return
        p.alive = false
        deathAt[p.gen] = time.now
        if (reason != null) {
            records += ExitRecord(reason, status, time.wallMs(), processName, p.pid, 100, 2048, 4096, "test") to queries + lag
        }
        p.deadLatch.countDown()
        for (c in callbacks) when (c) {
            "binderDied" -> listener.binderDied(p.gen)
            "disconnected" -> listener.disconnected(p.gen)
            "bindingDied" -> listener.bindingDied(p.gen)
        }
    }

    fun die(gen: Int, reason: Int?, status: Int = 0, lag: Int = 0, callbacks: List<String> = listOf("binderDied")) = die(procs.getValue(gen), reason, status, lag, callbacks)

    val source = ExitReasons.Source { pid ->
        queries++
        records.filter { it.first.pid == pid && queries > it.second }.map { it.first }
    }
}

internal class Rig(
    val role: EngineRole = EngineRole.BATCH,
    val tuning: EngineTuning = EngineTuning(),
    autoAdvance: Boolean = true,
    val processName: String = "pkg:batch",
    pidBase: Int = 1000,
    val events: MutableList<String>? = null,
    val tag: String = "",
    val beforeOpen: () -> Unit = {},
    closeExecutor: java.util.concurrent.Executor = java.util.concurrent.Executor { it.run() },
    val time: ManualTime = ManualTime(),
    val log: ListLog = ListLog(),
    /** Where tasks and timers run. The default is [time] itself (inline tasks, timers on advance); pass a [StepExecutor] to choose the order. */
    val scheduler: EngineScheduler = time,
) {
    init { time.autoAdvance = autoAdvance }
    val binding = FakeBinding(time, processName, pidBase, events, tag)
    val settler = RecSettler(events)
    @Volatile var cpu: Long? = 0L
    @Volatile var importance = 100
    val views = CopyOnWriteArrayList<HostView>()
    var decodeTps = 0.0
    val conn = EngineConnection(
        role, binding, time, scheduler, binding.source, CpuTime { cpu }, { importance }, tuning, log, processName, settler,
        lowMemoryKillReports = true, closeExecutor = closeExecutor, beforeOpen = beforeOpen, decodeTps = { decodeTps }, onView = { views += it },
    )
    private val ids = AtomicInteger()
    fun tag(op: String, loadId: String? = null) = CallTag("r${ids.incrementAndGet()}", loadId, op)
    fun open(backend: String = "CPU", model: String = "m") = conn.call(EngineOp.Open(tag("open", "L"), EngineKind.LLM, model, "g-$model", backend)) as EngineReply.Opened
    fun ask(h: Int, tokens: Int = 0) = conn.call(EngineOp.Ask(tag("ask"), h, "s", "u", null, tokens, 0.0, 1)) as EngineReply.Text
    fun embed(h: Int) = conn.call(EngineOp.EmbedText(tag("embed_text"), h, "t", false))
}
