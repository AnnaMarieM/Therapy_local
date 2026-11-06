package com.therapylocal.app.engine

import com.therapylocal.app.models.BackendGuard
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.Condition
import java.util.concurrent.locks.ReentrantLock

/*
 * The seams around EngineConnection. Everything here is pure Kotlin, so the connection runs on the JVM against fakes: a scripted
 * Binding, a virtual clock and scheduler, a fake exit-record source, a fake CPU-time reader.
 */

/** Time. [nowMs] is monotonic (idle, backoff, storm, watchdog); [wallMs] is epoch time, only for matching `ApplicationExitInfo.timestamp`. */
interface EngineClock {
    fun nowMs(): Long
    fun wallMs(): Long

    /** The caller holds [lock]. Waits on [cond] for at most [ms]. A fake clock may move virtual time instead of waiting. */
    fun await(lock: ReentrantLock, cond: Condition, ms: Long)
}

object SystemEngineClock : EngineClock {
    override fun nowMs(): Long = System.nanoTime() / 1_000_000
    override fun wallMs(): Long = System.currentTimeMillis()
    override fun await(lock: ReentrantLock, cond: Condition, ms: Long) {
        cond.await(ms.coerceAtLeast(1), TimeUnit.MILLISECONDS)
    }
}

fun interface Cancelable { fun cancel() }

/** The role's `engine-conn-<role>` executor: connection callbacks, death handling, the exit-record poll, the idle timer and the watchdog. */
interface EngineScheduler {
    fun execute(task: Runnable)
    fun schedule(delayMs: Long, task: Runnable): Cancelable
}

/** Host CPU time (`utime + stime` of `/proc/<pid>/stat`) in ms, or null when it cannot be read. */
fun interface CpuTime { fun cpuMs(pid: Int): Long? }

/** One structured line per state change. Production forwards to AppLog; tests record. */
interface EngineLog {
    fun log(level: Char, event: String, fields: List<Pair<String, Any?>>)
    fun flush() {}
}

/**
 * Settles the guard entries of the handles that were open when a host generation ended (finding 13), exactly once per handle. A benign end
 * is `unloaded`; a death is `diedWhileLoaded` (the guard side arrives with S10). Never `unloaded` on a death: it resets strikes.
 */
interface HandleSettler {
    fun unloaded(guardModel: String, backend: String)
    fun diedWhileLoaded(guardModel: String, backend: String, exit: BackendGuard.LastExit)

    object None : HandleSettler {
        override fun unloaded(guardModel: String, backend: String) {}
        override fun diedWhileLoaded(guardModel: String, backend: String, exit: BackendGuard.LastExit) {}
    }
}

/** Why a binder call failed, decided by [RemoteFailures.classify] in the real binding. */
class BindingFailure(val kind: Kind, val bytes: Int = -1) : RuntimeException("binding failure: $kind") {
    enum class Kind { DEATH, TOO_LARGE, TRANSPORT }
}

/**
 * What `EngineConnection` needs from the platform: one `ServiceConnection` per generation, delivering its callbacks to [listener] on the
 * connection executor, and a call that throws [BindingFailure] for a failed transact (an engine failure is rethrown as rebuilt).
 */
interface EngineBinding {
    /** Set once by the connection, before the first [bind]. */
    var listener: Listener

    interface Listener {
        fun connected(gen: Int, pid: Int)
        fun attachFailed(gen: Int, why: String)
        fun disconnected(gen: Int)
        fun bindingDied(gen: Int)
        fun nullBinding(gen: Int)
        fun binderDied(gen: Int)
        fun phase(req: String, loadId: String?, model: String, backend: String, phase: String)
    }

    /** Starts the bind for [gen] with a new connection object. False when the system refuses it. */
    fun bind(gen: Int): Boolean

    /** `unbindService` for [gen]'s connection, so the system can never restart the service under it. Idempotent. Keeps the death link. */
    fun unbind(gen: Int)

    /** Drops [gen]'s death link and connection. Idempotent. */
    fun release(gen: Int)

    fun call(gen: Int, op: EngineOp): EngineReply

    /** `IEngineHost.shutdown`, oneway. */
    fun shutdown(gen: Int, reason: String)

    fun kill(pid: Int)
    fun pidAlive(pid: Int): Boolean
}

/** What a host showed the settings screen. */
enum class HostState { UNBOUND, BINDING, BOUND, DEAD, STOPPING }

data class HostView(
    val state: HostState,
    val pid: Int,
    val gen: Int,
    val sinceMs: Long,
    val deaths: Int,
    val lastExit: BackendGuard.LastExit?,
    val loaded: List<String>,
    val pssKb: Long?,
)
