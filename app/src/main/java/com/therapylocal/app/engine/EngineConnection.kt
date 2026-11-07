package com.therapylocal.app.engine

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import com.therapylocal.app.config.EngineTuning
import com.therapylocal.app.models.BackendGuard
import com.therapylocal.app.models.BackendGuard.ExitKind
import java.util.concurrent.Executor
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The state machine of one host role (`:live` or `:batch`): generations, death with the system's exit reason, restart with backoff and a
 * storm cap, progress-aware timeouts that kill, idle stop, a handle registry per generation, and `:live` shut down before every `:batch`
 * open. Pure Kotlin over an [EngineBinding], so the JVM tests drive it with fakes.
 *
 * Threads. Callers ([call]) are worker threads, never the UI thread: they wait on a condition with no lock held. Every connection
 * callback, the exit-record poll, the idle timer and the watchdog run on the [scheduler] (the role's `engine-conn-<role>` executor).
 *
 * Generations. Each bind makes a new generation with its own connection object. On any end of a generation the connection calls
 * `unbindService` at once, so the system can never restart the service under us with its own backoff; a callback of an older generation
 * is ignored (`engine_stale_callback`). A generation goes BINDING, BOUND, then DYING (a death) or STOPPING (idle, shutdown, release) and
 * ENDED. An ended generation is settled before any caller is released: its remaining handles go to [settler] exactly once.
 *
 * Handles. The host numbers its handles from 1 in every process, so the connection hands out its own: the [EngineReply.Opened.handle] a
 * proxy sees is unique for the life of the connection and maps to (generation, host handle). A call on a handle of an ended generation
 * fails at once with [EngineDied] carrying that generation's real exit (a stale handle never reaches a newer process).
 */
class EngineConnection(
    val role: EngineRole,
    private val binding: EngineBinding,
    private val clock: EngineClock,
    private val scheduler: EngineScheduler,
    private val exits: ExitReasons.Source,
    private val cpuTime: CpuTime,
    private val mainImportance: () -> Int,
    private val tuning: EngineTuning,
    private val log: EngineLog,
    private val processName: String,
    private val settler: HandleSettler = HandleSettler.None,
    private val lowMemoryKillReports: Boolean = true,
    private val closeExecutor: Executor = Executor { it.run() },
    /** Runs before every `Open` in the `BATCH` role, before the bind too (the `:live` shutdown). */
    private val beforeOpen: () -> Unit = {},
    /** The model's last observed decode speed in tokens per second, 0 when unknown. */
    private val decodeTps: (String) -> Double = { 0.0 },
    private val sysMemory: () -> List<Pair<String, Any?>> = { emptyList() },
    private val onView: (HostView) -> Unit = {},
) {
    private enum class P { BINDING, BOUND, DYING, STOPPING, ENDED }

    /** [stop]: we asked this host to end (release, idle, batch_starting). A crash record still wins over such an expectation. */
    private class Expected(val kind: ExitKind, val why: String, val desc: String?, val hang: Boolean, val stop: Boolean = false)
    /** [retryOk]: the end was not one we asked for to keep a host away (a requested shutdown must not be undone by a retry). */
    private class End(val exit: BackendGuard.LastExit, val hang: Boolean, val retryOk: Boolean = true)

    private class Gen(val n: Int, val bindMs: Long, val bindWall: Long) {
        var phase = P.BINDING
        var pid = 0
        var connectedMs = 0L
        var expected: Expected? = null
        var failure: String? = null
        var finalizeStarted = false
        var stopDeadline = 0L
        var end: End? = null
        var lastHostPhase: String? = null
        var lastHostOp: String? = null
    }

    private class Loaded(val outer: Int, val gen: Int, val host: Int, val model: String, val guardModel: String, val backend: String, val loadId: String?) {
        var settled = false

        /** A Close was submitted and is not acknowledged yet: the entry stays, so a death before the ack still settles it (as a death). */
        var closing = false
    }

    private class Flight(
        val tag: CallTag, val opName: String, val g: Gen, val startMs: Long, val limitMs: Long?, val sink: PhaseSink, val activity: Boolean,
        val closeLike: Boolean,
    ) {
        @Volatile var phase: String? = null
        var cpuStart: Long? = null
        var lastCpu: Long? = null
        var lastCpuChangeMs = startMs
        @Volatile var killed = false
    }

    private sealed class Step {
        class Got(val f: Flight, val hostOp: EngineOp) : Step()
        class Done(val reply: EngineReply) : Step()
        class Fail(val e: Throwable) : Step()
        class Wait(val ms: Long, val v: Long) : Step()
        class Act(val run: () -> Unit) : Step()
    }

    private class Track { var bindingGen: Gen? = null }

    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private var version = 0L

    private var cur: Gen? = null
    private var genCounter = 0
    private val flights = ArrayList<Flight>()
    private val loaded = LinkedHashMap<Int, Loaded>()
    private var nextOuter = 1
    private var pendingCloses = 0
    private val ends = object : LinkedHashMap<Int, End>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, End>?): Boolean = size > 32
    }
    private val deaths = ArrayDeque<Long>()
    private var restartNotBefore = 0L
    private var pendingRestart: Pair<Long, Int>? = null
    private var circuitLogged = false
    private var lastActivityMs = 0L
    private var idleTimer: Cancelable? = null
    private var watchdog: Cancelable? = null
    private var lastExit: BackendGuard.LastExit? = null
    private var lastExitAtMs = 0L
    private var pssKb: Long? = null
    private var sinceMs = 0L

    init {
        binding.listener = object : EngineBinding.Listener {
            override fun connected(gen: Int, pid: Int) = onConnected(gen, pid)
            override fun attachFailed(gen: Int, why: String) = onBindEnd(gen, "attach_failed")
            override fun disconnected(gen: Int) = onEnd(gen, "disconnected")
            override fun bindingDied(gen: Int) = onEnd(gen, "binding_died")
            override fun nullBinding(gen: Int) = onBindEnd(gen, "null_binding")
            override fun binderDied(gen: Int) = onEnd(gen, "binder_died")
            override fun phase(req: String, loadId: String?, model: String, backend: String, phase: String) = onPhase(req, loadId, model, backend, phase)
        }
    }

    // ---------------------------------------------------------------------------------------------------------------- calls

    /** Throws what the engine threw, [EngineDied], [EngineUnavailable] or [ReplyTooLarge]. Never on the main thread (the host wrapper checks). */
    fun call(op: EngineOp, phases: PhaseSink = PhaseSink { _, _, _, _ -> }): EngineReply {
        // The previous model must be gone before the next one opens, or two are resident in the host.
        if (op is EngineOp.Open) awaitPendingCloses()
        if (role == EngineRole.BATCH && op is EngineOp.Open) {
            // The safety net must not turn a failed :live shutdown into a failed open: the kill path inside it already covers a stuck host.
            runCatching { beforeOpen() }.onFailure { ev('e', EngineEvents.CALL_FAILED, "op" to "before_open", "err" to it.javaClass.simpleName) }
        }
        var retriedBenign = false
        while (true) {
            val step = acquire(op, phases)
            if (step is Step.Done) return step.reply
            step as Step.Got
            try {
                return afterReply(step, op, transact(step.f, step.hostOp))
            } catch (d: EngineDied) {
                // One transparent retry for a benign end met on a call that holds no handle (the idle-stop race, a freezer kill).
                if (d.exit.kind == ExitKind.BENIGN && op.handleOrNull() == null && !retriedBenign && lock.withLock { ends[d.generation]?.retryOk != false }) {
                    retriedBenign = true
                    ev('w', EngineEvents.RETRY, "why" to "benign_exit", "op" to op.tag.op, "req" to op.tag.requestId, "gen" to d.generation)
                    continue
                }
                throw d
            } finally {
                // A synchronous Close forgets its entry whatever came back; a death in between was settled by the registry already.
                if (op is EngineOp.Close) lock.withLock { loaded.remove(op.handle) }
                finish(step.f)
            }
        }
    }

    /**
     * Never blocks. A close of a handle whose generation ended needs nothing (the process ending was the unload). The entry stays in `loaded`,
     * marked closing, until the host acknowledged the Close: a death in between settles it as a death. [onClosed] runs once, after the close
     * finished: `true` when the host acknowledged it (the entry is settled here, the caller tells the guard `unloaded`), `false` when the host
     * ended first or the close failed (the registry settles the entry, the caller must not).
     */
    fun closeAsync(op: EngineOp.Close, onClosed: (acked: Boolean) -> Unit = {}) {
        val entry = lock.withLock {
            loaded[op.handle]?.takeIf { !it.settled && !it.closing }?.also {
                it.closing = true
                pendingCloses++
            }
        }
        if (entry == null) {
            runCatching { onClosed(false) }
            return
        }
        val task = Runnable {
            var acked = false
            try {
                val f = lock.withLock {
                    val g = cur
                    if (g == null || g.n != entry.gen || g.phase != P.BOUND) null else register(op, g, PhaseSink { _, _, _, _ -> }, entry, activity = true)
                }
                if (f != null) {
                    try {
                        transact(f, op.copy(handle = entry.host))
                        acked = true
                    } finally {
                        finish(f)
                    }
                }
            } catch (t: Throwable) {
                runCatching { ev('w', EngineEvents.CALL_FAILED, "op" to "close", "req" to op.tag.requestId, "err" to t.javaClass.simpleName) }
            }
            try {
                // An ack that raced a death is not ours to settle: the death already did.
                val ours = lock.withLock {
                    entry.closing = false
                    if (acked && !entry.settled) {
                        entry.settled = true
                        loaded.remove(entry.outer)
                        true
                    } else false
                }
                runCatching { onClosed(ours) }
            } finally {
                lock.withLock {
                    pendingCloses--
                    bump()
                }
            }
        }
        if (runCatching { closeExecutor.execute(task) }.isFailure) {
            lock.withLock {
                entry.closing = false
                pendingCloses--
                bump()
            }
            runCatching { onClosed(false) }
        }
    }

    /** Waits, bounded by [EngineTuning.closeTimeoutMs], until every submitted close has finished. Worker threads only. */
    fun awaitPendingCloses() {
        val deadline = clock.nowMs() + tuning.closeTimeoutMs
        while (true) {
            var v = 0L
            val n = lock.withLock {
                v = version
                pendingCloses
            }
            if (n == 0) return
            val left = deadline - clock.nowMs()
            if (left <= 0) {
                ev('w', EngineEvents.CALL_FAILED, "op" to "close", "why" to "close_wait_timeout", "pending" to n)
                return
            }
            waitVersion(v, left)
        }
    }

    /**
     * Calls only a host that is bound now; never binds, never counts as activity (so a status poll cannot keep a host from its idle stop).
     * Null when no host is bound or the call failed.
     */
    fun callIfBound(op: EngineOp): EngineReply? {
        val f = lock.withLock {
            val g = cur
            if (g == null || g.phase != P.BOUND) null else register(op, g, PhaseSink { _, _, _, _ -> }, null, activity = false, limited = false)
        } ?: return null
        return try {
            transact(f, op)
        } catch (t: Throwable) {
            null
        } finally {
            finish(f)
        }
    }

    fun statusIfBound(): EngineReply.HostStatus? =
        callIfBound(EngineOp.Status(CallTag(java.util.UUID.randomUUID().toString().take(8), null, "status"))) as? EngineReply.HostStatus

    fun noteStatus(pssKb: Long?) = lock.withLock { this.pssKb = pssKb; publishLocked() }

    private fun acquire(op: EngineOp, phases: PhaseSink): Step {
        val track = Track()
        while (true) {
            when (val s = lock.withLock { decide(op, phases, track) }) {
                is Step.Got, is Step.Done -> return s
                is Step.Fail -> throw s.e
                is Step.Wait -> waitVersion(s.v, s.ms)
                is Step.Act -> s.run()
            }
        }
    }

    /** Under the lock: what this call does next. Nothing in here waits or calls out except bindService's non-blocking start. */
    private fun decide(op: EngineOp, phases: PhaseSink, track: Track): Step {
        val now = clock.nowMs()
        val h = op.handleOrNull()
        var entry: Loaded? = null
        if (h != null) {
            entry = loaded[h]
            if (entry == null) return if (op is EngineOp.Close) Step.Done(EngineReply.Done) else Step.Fail(IllegalStateException("unknown engine handle $h"))
            val c = cur
            if (c == null || c.n != entry.gen || c.phase != P.BOUND) {
                if (op is EngineOp.Close) {
                    // An entry whose generation is still ending stays: the end settles it (as a death if it was one).
                    if (entry.settled) loaded.remove(h)
                    return Step.Done(EngineReply.Done)
                }
                // That generation is still ending (its exit is not known yet), unless it is old enough to have left the table: a newer one only
                // starts after the older has ended.
                val e = ends[entry.gen] ?: if (entry.gen < genCounter && (c == null || c.n != entry.gen)) End(BackendGuard.LastExit(ExitKind.UNKNOWN, false, "host generation ended earlier"), false, false)
                else return Step.Wait(50, version)
                ev('w', EngineEvents.CALL_FAILED, "op" to op.tag.op, "req" to op.tag.requestId, "why" to "stale_handle", "gen_had" to entry.gen, "gen_now" to (c?.n ?: genCounter))
                return Step.Fail(EngineDied(role, entry.gen, e.exit, null, op.tag.op))
            }
        }
        val g = cur
        track.bindingGen?.let { w ->
            if (w.phase == P.ENDED && w.failure != null) {
                return Step.Fail(EngineUnavailable(role, "bind_${w.failure}", (restartNotBefore - now).coerceAtLeast(0)))
            }
        }
        when (g?.phase) {
            P.BOUND -> {
                val f = register(op, g, phases, entry, activity = true)
                return Step.Got(f, if (entry != null) op.withHandle(entry.host) else op)
            }
            P.BINDING -> {
                track.bindingGen = g
                val left = g.bindMs + tuning.bindTimeoutMs - now
                return if (left <= 0) Step.Act { failBind(g, "timeout") } else Step.Wait(left, version)
            }
            P.DYING -> return Step.Wait(50, version)
            P.STOPPING -> return Step.Act { stoppingWait(g) }
            P.ENDED, null -> {}
        }
        // No live process: restart policy.
        while (deaths.isNotEmpty() && now - deaths.first() > tuning.stormWindowMs) deaths.removeFirst()
        if (deaths.size >= tuning.stormDeaths) {
            val retry = (deaths.first() + tuning.stormWindowMs - now).coerceAtLeast(0)
            return Step.Act {
                ev('w', EngineEvents.UNAVAILABLE, "why" to "storm", "deaths" to deaths.size, "retry_in_ms" to retry)
                throw EngineUnavailable(role, "storm", retry)
            }
        }
        if (restartNotBefore > now) return Step.Wait(restartNotBefore - now, version)
        val n = Gen(++genCounter, now, clock.wallMs())
        cur = n
        track.bindingGen = n
        sinceMs = now
        val restart = pendingRestart.also { pendingRestart = null }
        bump()
        return Step.Act {
            restart?.let { (backoff, d) -> ev('i', EngineEvents.RESTART, "gen" to n.n, "backoff_ms" to backoff, "deaths_in_window" to d) }
            ev('i', EngineEvents.BIND, "gen" to n.n, "start_no" to n.n, "flags" to "auto_create|not_perceptible")
            val started = try {
                binding.bind(n.n)
            } catch (t: Throwable) {
                false
            }
            if (!started) failBind(n, "refused")
        }
    }

    private fun register(op: EngineOp, g: Gen, sink: PhaseSink, entry: Loaded?, activity: Boolean, limited: Boolean = true): Flight {
        val now = clock.nowMs()
        val limit = if (limited) limitFor(op, entry) else null
        val f = Flight(op.tag, op.tag.op, g, now, limit, sink, activity, op is EngineOp.Close)
        flights += f
        g.lastHostOp = op.tag.op
        if (activity) lastActivityMs = now
        if (limit != null && watchdog == null) armWatchdogLocked()
        return f
    }

    /** Null means "no watchdog" (status, log clearing: their callers bound the wait themselves). */
    private fun limitFor(op: EngineOp, entry: Loaded?): Long? = when (op) {
        is EngineOp.Open -> tuning.openTimeoutMs
        is EngineOp.Ask -> {
            val tps = entry?.let { decodeTps(it.model) } ?: 0.0
            maxOf(tuning.askMinTimeoutMs, (op.maxOutputTokens / maxOf(tps * 0.25, 0.5) * 1000).toLong())
        }
        is EngineOp.EmbedText, is EngineOp.EmbedAudio -> tuning.embedTimeoutMs
        is EngineOp.Transcribe -> tuning.transcribeTimeoutMs
        is EngineOp.Close -> tuning.closeTimeoutMs
        else -> null
    }

    /** Sends [hostOp] for flight [f]. A death waits for the generation to be settled before it is thrown. */
    private fun transact(f: Flight, hostOp: EngineOp): EngineReply {
        var retried = false
        while (true) {
            try {
                return binding.call(f.g.n, hostOp)
            } catch (e: BindingFailure) {
                when (e.kind) {
                    BindingFailure.Kind.DEATH -> {
                        val end = awaitEnd(f.g, "dead_object")
                        throw EngineDied(role, f.g.n, end.exit, f.phase, f.opName)
                    }
                    BindingFailure.Kind.TOO_LARGE -> throw ReplyTooLarge(f.opName, e.bytes)
                    BindingFailure.Kind.TRANSPORT -> {
                        val still = lock.withLock { cur === f.g && f.g.phase == P.BOUND }
                        if (!retried && still) {
                            retried = true
                            ev('w', EngineEvents.RETRY, "why" to "transport", "op" to f.opName, "req" to f.tag.requestId, "gen" to f.g.n)
                            continue
                        }
                        ev('w', EngineEvents.CALL_FAILED, "op" to f.opName, "req" to f.tag.requestId, "why" to "transport", "gen" to f.g.n)
                        throw EngineUnavailable(role, "transport", tuning.backoffMs.getOrElse(1) { tuning.backoffMs.lastOrNull() ?: 0L })
                    }
                }
            }
        }
    }

    private fun afterReply(step: Step.Got, op: EngineOp, reply: EngineReply): EngineReply {
        val g = step.f.g
        if (reply is EngineReply.Opened && op is EngineOp.Open) {
            val outer = lock.withLock {
                if (cur !== g || g.phase != P.BOUND) -1
                else {
                    val o = nextOuter++
                    loaded[o] = Loaded(o, g.n, reply.handle, op.model, op.guardModel, reply.backend, op.tag.loadId)
                    trimLoadedLocked()
                    publishLocked()
                    o
                }
            }
            if (outer < 0) {
                // The host ended between its reply and now: the handle died with it and was never registered.
                val end = awaitEnd(g, "ended_after_reply")
                throw EngineDied(role, g.n, end.exit, step.f.phase, op.tag.op)
            }
            noteSuccess(g)
            return reply.copy(handle = outer)
        }
        noteSuccess(g)
        return reply
    }

    /** The backoff resets after a host stayed up for [EngineTuning.stableMs] and answered. */
    private fun noteSuccess(g: Gen) = lock.withLock {
        if (g.phase == P.BOUND && clock.nowMs() - g.connectedMs >= tuning.stableMs && deaths.isNotEmpty()) {
            deaths.clear()
            circuitLogged = false
            restartNotBefore = 0
        }
    }

    private fun finish(f: Flight) {
        lock.withLock {
            flights.remove(f)
            if (f.activity) lastActivityMs = clock.nowMs()
            if (flights.none { it.limitMs != null }) {
                watchdog?.cancel()
                watchdog = null
            }
        }
        if (f.activity) armIdle() else armIdleIfMissing()
    }

    /**
     * A flight that is not activity (a status poll) leaves the timer alone, unless the check ran during it and dropped the timer
     * (`idleCheck` returns while a flight exists): then it is armed again for what is left of the idle time.
     */
    private fun armIdleIfMissing() {
        lock.withLock {
            val g = cur
            if (idleTimer == null && g != null && g.phase == P.BOUND) {
                idleTimer = scheduler.schedule((lastActivityMs + tuning.idleMs - clock.nowMs()).coerceAtLeast(1)) { idleCheck() }
            }
        }
    }

    private fun trimLoadedLocked() {
        if (loaded.size <= 256) return
        val it = loaded.entries.iterator()
        while (it.hasNext() && loaded.size > 256) if (it.next().value.settled) it.remove()
    }

    // ---------------------------------------------------------------------------------------------------------------- callbacks

    private fun stale(gen: Int) {
        ev('d', EngineEvents.STALE_CALLBACK, "conn_gen" to gen, "gen_now" to lock.withLock { cur?.n })
    }

    private fun onConnected(gen: Int, pid: Int) {
        var startMs = 0L
        val ok = lock.withLock {
            val g = cur
            if (g == null || g.n != gen || g.phase != P.BINDING) false
            else {
                g.pid = pid
                g.connectedMs = clock.nowMs()
                g.phase = P.BOUND
                lastActivityMs = g.connectedMs
                startMs = g.connectedMs - g.bindMs
                bump()
                true
            }
        }
        if (!ok) {
            stale(gen)
            runCatching { binding.unbind(gen) }
            return
        }
        ev('i', EngineEvents.CONNECTED, "gen" to gen, "host_pid" to pid, "start_ms" to startMs)
        armIdle()
    }

    /** `onNullBinding` and a failed attach: the bind itself failed. Afterwards, the same as a lost connection. */
    private fun onBindEnd(gen: Int, why: String) {
        val g = lock.withLock { cur?.takeIf { it.n == gen } }
        if (g == null) return stale(gen)
        if (lock.withLock { g.phase == P.BINDING }) failBind(g, why) else if (why == "attach_failed") triggerFinalize(g, why) else stale(gen)
    }

    /** `onServiceDisconnected`, `onBindingDied` and `binderDied`. */
    private fun onEnd(gen: Int, source: String) {
        val g = lock.withLock { cur?.takeIf { it.n == gen } }
        if (g == null) return stale(gen)
        when (lock.withLock { g.phase }) {
            P.BINDING -> failBind(g, "binding_died")
            P.BOUND, P.STOPPING -> triggerFinalize(g, source)
            P.DYING, P.ENDED -> {} // a second callback for the same end: nothing to do (N13)
        }
    }

    private fun onPhase(req: String, loadId: String?, model: String, backend: String, phase: String) {
        val f = lock.withLock { flights.firstOrNull { it.tag.requestId == req }?.also { it.phase = phase; it.g.lastHostPhase = phase } } ?: return
        runCatching { f.sink.phase(CallTag(req, loadId, f.opName), model, backend, phase) }
    }

    // ---------------------------------------------------------------------------------------------------------------- bind failure

    /** A start failure counts as one death in the storm window, never as a backend strike (no `Open` was attempted). */
    private fun failBind(g: Gen, why: String) {
        lock.withLock {
            if (g.phase != P.BINDING || g.finalizeStarted) return
            g.finalizeStarted = true
            g.failure = why
        }
        ev('w', EngineEvents.BIND_TIMEOUT, "gen" to g.n, "why" to why, "timeout_ms" to tuning.bindTimeoutMs)
        runCatching { binding.unbind(g.n) }
        runCatching { binding.release(g.n) }
        lock.withLock {
            val now = clock.nowMs()
            val exit = BackendGuard.LastExit(ExitKind.UNKNOWN, false, "bind failed: $why")
            countDeathLocked(now)
            g.end = End(exit, false)
            ends[g.n] = g.end!!
            lastExit = exit
            lastExitAtMs = clock.wallMs()
            g.phase = P.ENDED
            bump()
        }
        logCircuitIfOpen()
    }

    private fun backoffFor(deathsInWindow: Int): Long =
        minOf(tuning.backoffMs.getOrElse(deathsInWindow - 1) { tuning.backoffMs.lastOrNull() ?: 0L }, tuning.backoffCapMs)

    /** Counts a death in the window and sets the earliest restart. */
    private fun countDeathLocked(now: Long) {
        deaths.addLast(now)
        while (deaths.isNotEmpty() && now - deaths.first() > tuning.stormWindowMs) deaths.removeFirst()
        val b = backoffFor(deaths.size)
        restartNotBefore = now + b
        pendingRestart = b to deaths.size
    }

    private fun logCircuitIfOpen() {
        val open = lock.withLock {
            if (deaths.size >= tuning.stormDeaths && !circuitLogged) {
                circuitLogged = true
                true
            } else false
        }
        if (open) ev('e', EngineEvents.CIRCUIT_OPEN, "deaths" to deaths.size, "window_ms" to tuning.stormWindowMs)
    }

    // ---------------------------------------------------------------------------------------------------------------- death

    /** The first end seen wins: later callers and callbacks are no-ops (N13). */
    private fun triggerFinalize(g: Gen, source: String) {
        val run = lock.withLock {
            if (g.finalizeStarted || g.phase == P.ENDED) false
            else {
                g.finalizeStarted = true
                if (g.phase != P.STOPPING) g.phase = P.DYING
                bump()
                true
            }
        }
        if (run) scheduler.execute { beginFinalize(g, source) }
    }

    private fun beginFinalize(g: Gen, source: String) {
        runCatching { binding.unbind(g.n) }
        if (g.pid == 0) return complete(g, null, source)
        lookup(g, clock.nowMs() + tuning.exitLookupMs, true, source)
    }

    /**
     * The exit record may lag the death by a few hundred ms: poll every 50 ms for up to [EngineTuning.exitLookupMs], as a re-posted task.
     * The system first stores a placeholder (`REASON_UNKNOWN`, status 0, empty description) and fills it in once it has processed the death,
     * so an UNKNOWN record is not final: keep polling, and only when the bound is hit fall back to the newest record seen (a genuine UNKNOWN
     * exists, e.g. a SIGKILL without low-memory-kill reports).
     */
    private fun lookup(g: Gen, deadline: Long, first: Boolean, source: String, seen: ExitRecord? = null) {
        val rec = runCatching { ExitReasons.pick(exits.query(g.pid), g.pid, processName, g.bindWall) }.getOrNull() ?: seen
        val settled = rec != null && rec.reason != ApplicationExitInfo.REASON_UNKNOWN
        if (settled || clock.nowMs() >= deadline) return complete(g, rec, source)
        if (first) ev('w', EngineEvents.EXIT_REASON_PENDING, "gen" to g.n, "host_pid" to g.pid)
        scheduler.schedule(50) { lookup(g, deadline, false, source, rec) }
    }

    private fun complete(g: Gen, rec: ExitRecord?, source: String) {
        val mapped = rec?.let { ExitReasons.map(it, lowMemoryKillReports) }
        val importance = runCatching { mainImportance() }.getOrDefault(ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND)
        val foreground = importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE
        val expected = lock.withLock { g.expected }
        // What we caused wins over the record: a timeout kill is SIGNALED 9 in the record and must not read as a memory kill.
        // A requested stop is expected, but a host that crashed on the way out (java crash 13, native signal 100+N, crash records) is a crash.
        val crashedInStop = expected?.stop == true && (mapped?.kind == ExitKind.CRASH || mapped?.kind == ExitKind.NATIVE_CRASH)
        val kind = if (crashedInStop) mapped!!.kind else expected?.kind ?: mapped?.kind ?: ExitKind.UNKNOWN
        val exit = BackendGuard.LastExit(kind, foreground, (if (crashedInStop) null else expected?.desc) ?: rec?.let(ExitReasons::describe) ?: "exit record not found")
        val hang = expected?.hang == true
        val now = clock.nowMs()
        val counted = kind != ExitKind.BENIGN
        var calls: List<Flight> = emptyList()
        var entries: List<Loaded> = emptyList()
        var inWindow = 0
        lock.withLock {
            calls = flights.filter { it.g === g }
            if (counted) countDeathLocked(now) else restartNotBefore = now
            inWindow = deaths.size
            entries = loaded.values.filter { it.gen == g.n && !it.settled }.onEach { it.settled = true }
        }
        val first = calls.firstOrNull()
        // The in-flight close of a host that we asked to stop is the shutdown itself, not a call the death interrupted.
        val onlyOurClose = expected?.stop == true && calls.all { it.opName == "close" }
        if (kind == ExitKind.BENIGN && (calls.isEmpty() || onlyOurClose)) {
            ev(
                'i', EngineEvents.EXIT, "gen" to g.n, "host_pid" to g.pid, "reason" to (mapped?.reasonName ?: expected?.why ?: source),
                "why" to (expected?.why ?: mapped?.why), "status" to rec?.status, "source" to source, "calls" to calls.size.takeIf { it > 0 },
            )
        } else {
            ev(
                'e', EngineEvents.DIED,
                "gen" to g.n, "host_pid" to g.pid, "uptime_ms" to if (g.connectedMs > 0) now - g.connectedMs else null, "source" to source,
                "op" to first?.opName, "req" to first?.tag?.requestId, "load_id" to first?.tag?.loadId, "phase" to first?.phase,
                "call" to if (first == null) "none" else "in_flight", "calls" to calls.size,
                "exit_kind" to kind.name.lowercase(), "reason_name" to mapped?.reasonName, "status" to rec?.status, "description" to exit.description,
                "pss_kb" to rec?.pssKb, "rss_kb" to rec?.rssKb, "hang" to hang,
                "host_importance" to rec?.importance, "client_importance" to importance,
                "last_host_phase" to g.lastHostPhase, "last_host_op" to g.lastHostOp, "deaths" to inWindow,
                *runCatching { sysMemory() }.getOrDefault(emptyList()).toTypedArray(),
            )
        }
        // The registry settles before any caller is released: every remaining handle exactly once.
        for (e in entries) {
            runCatching {
                if (kind == ExitKind.BENIGN) settler.unloaded(e.guardModel, e.backend) else settler.diedWhileLoaded(e.guardModel, e.backend, exit)
            }
        }
        lock.withLock {
            g.end = End(exit, hang, expected == null || expected.why == "idle" || expected.why == "close_timeout")
            ends[g.n] = g.end!!
            lastExit = exit
            lastExitAtMs = clock.wallMs()
            g.phase = P.ENDED
            idleTimer?.cancel()
            idleTimer = null
            bump()
        }
        runCatching { binding.release(g.n) }
        logCircuitIfOpen()
    }

    /** Waits (bounded, no lock held) until [g] is settled. */
    private fun awaitEnd(g: Gen, source: String): End {
        triggerFinalize(g, source)
        val deadline = clock.nowMs() + tuning.exitLookupMs + 2_000
        while (true) {
            var v = 0L
            val done = lock.withLock {
                v = version
                g.end
            }
            if (done != null) return done
            val left = deadline - clock.nowMs()
            if (left <= 0) return End(BackendGuard.LastExit(ExitKind.UNKNOWN, false, "exit not settled in time"), false)
            waitVersion(v, left)
        }
    }

    private fun waitVersion(seen: Long, ms: Long) {
        lock.withLock { if (version == seen) clock.await(lock, changed, ms.coerceAtLeast(1)) }
    }

    // ---------------------------------------------------------------------------------------------------------------- idle, stop

    private fun armIdle() {
        lock.withLock {
            idleTimer?.cancel()
            val g = cur
            idleTimer = if (g != null && g.phase == P.BOUND) scheduler.schedule(tuning.idleMs) { idleCheck() } else null
        }
    }

    private fun idleCheck() {
        var stop: Gen? = null
        lock.withLock {
            idleTimer = null
            val g = cur ?: return
            if (g.phase != P.BOUND || flights.isNotEmpty()) return
            val idleFor = clock.nowMs() - lastActivityMs
            if (idleFor >= tuning.idleMs) {
                beginStopLocked(g, "idle")
                stop = g
            } else {
                idleTimer = scheduler.schedule(tuning.idleMs - idleFor) { idleCheck() }
            }
        }
        stop?.let {
            ev('i', EngineEvents.IDLE_STOP, "gen" to it.n, "host_pid" to it.pid, "idle_ms" to tuning.idleMs)
            finishStop(it, "idle")
        }
    }

    /** Under the lock. A call that starts after this waits for the old process to be gone and rebinds. */
    private fun beginStopLocked(g: Gen, reason: String) {
        g.phase = P.STOPPING
        // A kill already chosen (a hang) keeps its verdict: asking for a stop on top of it must not turn the strike into a benign end.
        if (g.expected == null) g.expected = Expected(ExitKind.BENIGN, reason, null, false, stop = true)
        g.stopDeadline = clock.nowMs() + tuning.liveShutdownMs
        idleTimer?.cancel()
        idleTimer = null
        bump()
    }

    private fun finishStop(g: Gen, reason: String) {
        runCatching { binding.shutdown(g.n, reason) }
        ev('i', EngineEvents.UNBOUND, "gen" to g.n, "reason" to reason)
        runCatching { binding.unbind(g.n) } // the death link stays: its callback is how we learn the process is gone
        if (g.pid == 0) triggerFinalize(g, "stopped_before_connect")
    }

    /**
     * Debug lab (`EngineLabKills`, catalogue B10 and F03): tags the current generation's coming end as [kind] with reason [why] and returns
     * its pid, or null when no host is bound or its end is already tagged. The caller then kills the process. The same mechanism as an idle
     * or timeout kill, so main has no other test code.
     */
    fun expectedExit(kind: ExitKind, why: String): Int? {
        val g = lock.withLock {
            val g = cur?.takeIf { it.phase == P.BOUND && it.expected == null } ?: return null
            g.expected = Expected(kind, why, "$why kill", false)
            g
        }
        ev('e', EngineEvents.KILL, "host_pid" to g.pid, "why" to why, "gen" to g.n)
        log.flush()
        return g.pid
    }

    /** Ends the host without waiting (the settings screen, `Engines.suspend`). Never blocks. */
    fun release() {
        val g = lock.withLock { cur?.takeIf { it.phase == P.BOUND || it.phase == P.BINDING }?.also { beginStopLocked(it, "release") } } ?: return
        finishStop(g, "release")
    }

    /**
     * Shuts the host down and waits (bounded by [EngineTuning.liveShutdownMs]) for its process to be gone; kills it after that. A no-op
     * when nothing is bound. True when it had to kill. This is what runs for `:live` before every `:batch` open.
     */
    fun shutdownAndAwait(reason: String): Boolean {
        val t0 = clock.nowMs()
        var started = false
        val g = lock.withLock {
            val c = cur
            if (c == null || c.phase == P.ENDED) null
            else {
                if (c.phase == P.BOUND || c.phase == P.BINDING) {
                    beginStopLocked(c, reason)
                    started = true
                }
                c
            }
        } ?: return false
        if (reason == "batch_starting") ev('i', EngineEvents.LIVE_SHUTDOWN_REQUESTED, "gen" to g.n, "host_pid" to g.pid)
        if (started) finishStop(g, reason)
        var forced = false
        while (true) {
            var v = 0L
            var left = 0L
            val ended = lock.withLock {
                v = version
                left = g.stopDeadline - clock.nowMs()
                g.phase == P.ENDED
            }
            if (ended) break
            if (g.pid > 0 && !binding.pidAlive(g.pid)) {
                triggerFinalize(g, "pid_gone")
                awaitEnd(g, "pid_gone")
                break
            }
            if (left <= 0) {
                forced = true
                ev('w', EngineEvents.KILL, "host_pid" to g.pid, "why" to "shutdown_timeout", "gen" to g.n)
                log.flush()
                if (g.pid > 0) binding.kill(g.pid)
                triggerFinalize(g, "killed")
                awaitEnd(g, "killed")
                break
            }
            waitVersion(v, minOf(left, 50))
        }
        ev('i', EngineEvents.LIVE_SHUTDOWN_DONE, "gen" to g.n, "waited_ms" to (clock.nowMs() - t0), "forced" to forced)
        return forced
    }

    /** A bind after a stop waits for the old process to be gone, so it can never attach to a dying ProcessRecord. */
    private fun stoppingWait(g: Gen) {
        var v = 0L
        var left = 0L
        lock.withLock {
            v = version
            left = g.stopDeadline - clock.nowMs()
            if (g.phase != P.STOPPING) return
        }
        if (g.pid > 0 && !binding.pidAlive(g.pid)) return triggerFinalize(g, "pid_gone")
        if (left <= 0) {
            ev('w', EngineEvents.KILL, "host_pid" to g.pid, "why" to "stopping_wait", "gen" to g.n)
            log.flush()
            if (g.pid > 0) binding.kill(g.pid)
            return triggerFinalize(g, "killed")
        }
        waitVersion(v, minOf(left, 50))
    }

    // ---------------------------------------------------------------------------------------------------------------- watchdog

    private fun armWatchdogLocked() {
        val tick = (tuning.hangNoProgressMs / 4).coerceIn(1, 1000)
        watchdog = scheduler.schedule(tick) { watchdogTick() }
    }

    /**
     * Kills a host whose call has run past its limit AND shows no CPU progress for [EngineTuning.hangNoProgressMs], or past
     * [EngineTuning.hardCapFactor] times the limit whatever the CPU does. A close has no CPU rule: it is killed at its limit, without a strike.
     * Unreadable CPU time means "unknown", so only the hard cap applies then.
     */
    private fun watchdogTick() {
        val snapshot = lock.withLock {
            watchdog = null
            flights.filter { it.limitMs != null && !it.killed && it.g.phase == P.BOUND }.toList()
        }
        val now = clock.nowMs()
        for (f in snapshot) {
            val limit = f.limitMs ?: continue
            val cpu = cpuTime.cpuMs(f.g.pid)
            if (cpu != null) {
                if (f.cpuStart == null) {
                    f.cpuStart = cpu
                    f.lastCpu = cpu
                } else if (f.lastCpu != cpu) {
                    f.lastCpu = cpu
                    f.lastCpuChangeMs = now
                }
            }
            val elapsed = now - f.startMs
            val hard = elapsed > tuning.hardCapFactor * limit
            val silent = cpu != null && elapsed > limit && now - f.lastCpuChangeMs >= tuning.hangNoProgressMs
            val closeLike = f.closeLike
            if (if (closeLike) elapsed > limit else (silent || hard)) kill(f, limit, elapsed, (cpu ?: 0) - (f.cpuStart ?: cpu ?: 0), hard, closeLike)
        }
        lock.withLock { if (watchdog == null && flights.any { it.limitMs != null }) armWatchdogLocked() }
    }

    private fun kill(f: Flight, limit: Long, elapsed: Long, cpuDelta: Long, hard: Boolean, closeLike: Boolean) {
        val g = f.g
        val go = lock.withLock {
            f.killed = true
            if (g.expected != null || g.phase != P.BOUND) false
            else {
                g.expected = if (closeLike) Expected(ExitKind.BENIGN, "close_timeout", "close exceeded $limit ms", false)
                else Expected(ExitKind.ANR, "hang", "hang: ${f.opName} exceeded $limit ms", true)
                true
            }
        }
        if (!go) return
        ev('e', EngineEvents.CALL_TIMEOUT, "op" to f.opName, "req" to f.tag.requestId, "limit_ms" to limit, "elapsed_ms" to elapsed, "cpu_ms_delta" to cpuDelta, "hard_cap" to hard)
        ev('e', EngineEvents.KILL, "host_pid" to g.pid, "why" to if (closeLike) "close_timeout" else "hang", "gen" to g.n)
        log.flush()
        binding.kill(g.pid)
    }

    // ---------------------------------------------------------------------------------------------------------------- view, log

    val view: HostView get() = lock.withLock { viewLocked() }

    val isBound: Boolean get() = lock.withLock { cur?.phase == P.BOUND }

    private fun viewLocked(): HostView {
        val g = cur
        val state = when (g?.phase) {
            null -> HostState.UNBOUND
            P.BINDING -> HostState.BINDING
            P.BOUND -> HostState.BOUND
            P.DYING -> HostState.DEAD
            P.STOPPING -> HostState.STOPPING
            P.ENDED -> if (g.failure != null || lastExit?.kind.let { it != null && it != ExitKind.BENIGN }) HostState.DEAD else HostState.UNBOUND
        }
        return HostView(
            state, g?.pid ?: 0, g?.n ?: 0, sinceMs, deaths.size, lastExit,
            loaded.values.filter { !it.settled && !it.closing }.map { "${it.guardModel}@${it.backend}" }, pssKb, lastExitAtMs,
        )
    }

    private fun publishLocked() = runCatching { onView(viewLocked()) }

    private fun bump() {
        version++
        changed.signalAll()
        publishLocked()
    }

    private fun ev(level: Char, event: String, vararg fields: Pair<String, Any?>) {
        runCatching { log.log(level, event, listOf<Pair<String, Any?>>("role" to role.proc) + fields) }
    }

    private fun EngineOp.handleOrNull(): Int? = when (this) {
        is EngineOp.Ask -> handle
        is EngineOp.EmbedText -> handle
        is EngineOp.EmbedAudio -> handle
        is EngineOp.Transcribe -> handle
        is EngineOp.Close -> handle
        else -> null
    }

    private fun EngineOp.withHandle(h: Int): EngineOp = when (this) {
        is EngineOp.Ask -> copy(handle = h)
        is EngineOp.EmbedText -> copy(handle = h)
        is EngineOp.EmbedAudio -> copy(handle = h)
        is EngineOp.Transcribe -> copy(handle = h)
        is EngineOp.Close -> copy(handle = h)
        else -> this
    }
}
