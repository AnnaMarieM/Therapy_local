package com.therapylocal.app.engine

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.Process
import android.os.RemoteException
import com.therapylocal.app.config.EngineConfig
import com.therapylocal.app.config.EngineTuning
import com.therapylocal.app.engine.wire.EngineCodec
import com.therapylocal.app.log.AppLog
import com.therapylocal.app.log.LogCtx
import com.therapylocal.app.models.BackendGuard
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The engines of one role (`:live` or `:batch`) in their own process, reached over Binder. The same [EngineHost] contract as
 * [LocalEngineHost]; the host-side [EngineServer] is the same too.
 *
 * Threads: binding, the connection callbacks and the death recipient run on a single-thread executor of their own (`engine-conn-<role>`),
 * never on the main looper or a binder thread, so a worker waiting for the bind never depends on a free main thread. [call] must not run on
 * the main thread. [closeAsync] hands the close to a second executor (`engine-close-<role>`) and returns.
 *
 * This is the basic connection: one bind per generation, a death is an [EngineDied] without exit lookup, a transport error is
 * [EngineUnavailable]. No restart or timeout logic yet (EngineConnection adds those). A `RemoteException` never leaves this class.
 */
class RemoteEngineHost(
    context: Context,
    override val role: EngineRole,
    private val tuning: EngineTuning = EngineConfig.DEFAULT,
) : EngineHost {
    private val app = context.applicationContext
    private val tag = "Engine"
    private val connExecutor: ExecutorService = daemon("engine-conn-${role.proc}")
    private val closeExecutor: ExecutorService = daemon("engine-close-${role.proc}")
    private val bindLock = Any()
    private val generations = AtomicInteger(0)
    private val clientToken = Binder()

    /** `req` to the sink of the call in flight, so the host's phase callbacks reach the right ladder attempt. */
    private val phaseSinks = ConcurrentHashMap<String, PhaseSink>()

    /** Handle to the generation it was opened on (a minimal stand-in for the per-generation registry). */
    private val handleGen = ConcurrentHashMap<Int, Int>()

    @Volatile private var conn: Conn? = null

    private val events = object : IEngineEvents.Stub() {
        override fun phase(req: String, loadId: String?, model: String, backend: String, phase: String) {
            val sink = phaseSinks[req] ?: return
            runCatching { sink.phase(CallTag(req, loadId, "open"), model, backend, phase) }
        }
    }

    /** One bound connection. The `ServiceConnection` is per generation, so a callback of an older one can be told from the current one. */
    private inner class Conn(val gen: Int) : android.content.ServiceConnection {
        val latch = CountDownLatch(1)
        @Volatile var host: IEngineHost? = null
        @Volatile var failure: String? = null
        @Volatile var ended = false
        val death = IBinder.DeathRecipient { onGone(this, "binder_died") }

        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            if (ended) return
            try {
                val h = IEngineHost.Stub.asInterface(service)
                service.linkToDeath(death, 0)
                h.attach(clientToken, events, Process.myPid(), AppLog.sid)
                host = h
                AppLog.i(tag, EngineEvents.CONNECTED, LogCtx.NONE, "role" to role.proc, "gen" to gen)
            } catch (e: Exception) {
                failure = "attach_failed"
                runCatching { AppLog.w(tag, EngineEvents.BIND_TIMEOUT, LogCtx.NONE, "role" to role.proc, "gen" to gen, "why" to "attach_failed", "err" to e.javaClass.simpleName) }
            } finally {
                latch.countDown()
            }
        }

        override fun onServiceDisconnected(name: ComponentName) = onGone(this, "disconnected")
        override fun onBindingDied(name: ComponentName) {
            failure = failure ?: "binding_died"
            onGone(this, "binding_died")
        }
        override fun onNullBinding(name: ComponentName) {
            failure = "null_binding"
            latch.countDown()
        }

        fun alive(): Boolean = !ended && host?.asBinder()?.isBinderAlive == true
    }

    private fun onGone(c: Conn, reason: String) {
        if (conn !== c) {
            AppLog.d(tag, EngineEvents.STALE_CALLBACK, LogCtx.NONE, "role" to role.proc, "conn_gen" to c.gen, "gen_now" to conn?.gen)
            return
        }
        endConn(c, reason)
    }

    private fun endConn(c: Conn, reason: String) {
        synchronized(bindLock) {
            if (c.ended) return
            c.ended = true
            if (conn === c) conn = null
        }
        runCatching { c.host?.asBinder()?.unlinkToDeath(c.death, 0) }
        runCatching { app.unbindService(c) }
        c.latch.countDown()
        AppLog.i(tag, EngineEvents.UNBOUND, LogCtx.NONE, "role" to role.proc, "gen" to c.gen, "reason" to reason)
    }

    /** Binds if needed and waits (bounded) for the connection. Never on the main thread. */
    private fun ensureBound(): Conn {
        check(Looper.getMainLooper() == null || Looper.myLooper() !== Looper.getMainLooper()) { "engine bind on the main thread" }
        val c: Conn
        synchronized(bindLock) {
            val cur = conn
            if (cur != null && !cur.ended && cur.latch.count == 0L && cur.alive()) return cur
            if (cur != null && !cur.ended && cur.latch.count > 0L) {
                c = cur // somebody else is binding: wait on the same attempt below
            } else {
                if (cur != null) endConn(cur, "stale")
                c = Conn(generations.incrementAndGet())
                conn = c
                val intent = Intent(app, if (role == EngineRole.LIVE) EngineHostLive::class.java else EngineHostBatch::class.java)
                AppLog.i(tag, EngineEvents.BIND, LogCtx.NONE, "role" to role.proc, "gen" to c.gen, "flags" to "auto_create|not_perceptible")
                val ok = try {
                    app.bindService(intent, android.content.Context.BIND_AUTO_CREATE or android.content.Context.BIND_NOT_PERCEPTIBLE, connExecutor, c)
                } catch (e: SecurityException) {
                    false
                }
                if (!ok) {
                    c.failure = "refused"
                    endConn(c, "refused")
                }
            }
        }
        val connected = c.latch.await(tuning.bindTimeoutMs, TimeUnit.MILLISECONDS)
        val why = c.failure ?: if (!connected) "timeout" else if (c.host == null) "binding_died" else null
        if (why != null) {
            AppLog.w(tag, EngineEvents.BIND_TIMEOUT, LogCtx.NONE, "role" to role.proc, "gen" to c.gen, "why" to why, "timeout_ms" to tuning.bindTimeoutMs)
            endConn(c, "bind_$why")
            throw EngineUnavailable(role, "bind_$why", 0)
        }
        return c
    }

    override fun call(op: EngineOp, phases: PhaseSink): EngineReply {
        val req = op.tag.requestId
        phaseSinks[req] = phases
        var encoded: android.os.Bundle? = null
        try {
            val c = ensureBound()
            checkHandle(op, c)
            val b = EngineCodec.encode(op).also { encoded = it }
            val raw = try {
                c.host!!.call(b)
            } catch (e: RemoteException) {
                val alive = c.alive()
                val ex = RemoteFailures.toException(e, alive, role, c.gen, op.tag.op, sizeOf(b))
                if (ex is EngineDied) endConn(c, "died_in_call")
                throw ex
            }
            val reply = EngineCodec.decodeReply(raw)
            if (reply is EngineReply.Opened) handleGen[reply.handle] = c.gen
            if (op is EngineOp.Close) handleGen.remove(op.handle)
            return reply
        } finally {
            phaseSinks.remove(req)
            encoded?.let { EngineCodec.release(it) }
        }
    }

    /** A handle opened on an earlier generation is never sent to a newer process (the registry per generation arrives with EngineConnection). */
    private fun checkHandle(op: EngineOp, c: Conn) {
        val handle = when (op) {
            is EngineOp.Ask -> op.handle
            is EngineOp.EmbedText -> op.handle
            is EngineOp.EmbedAudio -> op.handle
            is EngineOp.Transcribe -> op.handle
            is EngineOp.Close -> op.handle
            else -> return
        }
        val had = handleGen[handle] ?: return
        if (had != c.gen) {
            if (op is EngineOp.Close) handleGen.remove(handle)
            AppLog.w(tag, EngineEvents.CALL_FAILED, LogCtx.NONE, "role" to role.proc, "op" to op.tag.op, "why" to "stale_handle", "gen_had" to had, "gen_now" to c.gen)
            throw EngineDied(role, had, BackendGuard.LastExit(BackendGuard.ExitKind.UNKNOWN, false, "handle from an earlier host process"), null, op.tag.op)
        }
    }

    /**
     * Never blocks the caller. Only an existing connection is used: a close never starts a host, and a handle of a process that is gone needs
     * no close (the process ending was the unload).
     */
    override fun closeAsync(op: EngineOp.Close) {
        val c = conn?.takeIf { it.alive() }
        if (c == null) {
            handleGen.remove(op.handle)
            return
        }
        runCatching {
            closeExecutor.execute {
                try {
                    if (c.alive()) {
                        val b = EngineCodec.encode(op)
                        try {
                            EngineCodec.decodeReply(c.host!!.call(b))
                        } finally {
                            EngineCodec.release(b)
                        }
                    }
                } catch (t: Throwable) {
                    runCatching { AppLog.w(tag, EngineEvents.CALL_FAILED, LogCtx.NONE, "role" to role.proc, "op" to "close", "req" to op.tag.requestId, "err" to t.javaClass.simpleName) }
                } finally {
                    handleGen.remove(op.handle)
                }
            }
        }
    }

    /**
     * Asks a bound host to delete its own log files. False when no host is bound (the caller deletes the files itself) or the host did not
     * answer in `closeTimeoutMs`. Never binds.
     */
    fun clearLogsIfBound(): Boolean {
        val c = conn?.takeIf { it.alive() } ?: return false
        val op = EngineOp.ClearLogs(CallTag(java.util.UUID.randomUUID().toString().take(8), null, "clear_logs"))
        val f = try {
            closeExecutor.submit<Boolean> {
                val b = EngineCodec.encode(op)
                try {
                    EngineCodec.decodeReply(c.host!!.call(b)) is EngineReply.Done
                } finally {
                    EngineCodec.release(b)
                }
            }
        } catch (_: Throwable) {
            return false
        }
        return try {
            f.get(tuning.closeTimeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: Throwable) {
            f.cancel(true)
            false
        }
    }

    /** Whether a host is bound right now. */
    val isBound: Boolean get() = conn?.alive() == true

    /** Unbinds. The host process ends itself when its last client unbinds. */
    fun release() {
        val c = conn ?: return
        endConn(c, "release")
    }

    private fun sizeOf(b: android.os.Bundle): Int {
        val p = Parcel.obtain()
        return try {
            p.writeBundle(b)
            p.dataSize()
        } catch (_: Throwable) {
            -1
        } finally {
            p.recycle()
        }
    }

    private companion object {
        fun daemon(name: String): ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, name).apply { isDaemon = true } }
    }
}
