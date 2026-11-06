package com.therapylocal.app.engine

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import com.therapylocal.app.engine.wire.EngineCodec
import com.therapylocal.app.engine.wire.isText
import com.therapylocal.app.log.AppLog
import com.therapylocal.app.log.LogCtx
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A bound, non-foreground process that runs one [EngineServer] for a role (`:live` or `:batch`). Main binds it, calls it with one generic
 * Bundle method, and ends it by unbinding, by [IEngineHost.shutdown] or by dying. The process ends itself with `Runtime.halt(code)`,
 * which the system reports as `EXIT_SELF` with status 10, 11 or 12 ([HostExit]); a crash also ends as EXIT_SELF (13, or 100 + signal, AD-04); `killProcess` would read as a memory kill (SIGNALED 9).
 *
 * Calls block a binder thread for as long as the inference takes. The host calls back into main only through the synchronous `phase`,
 * which main serves on its own binder pool, and main's caller is never a binder thread, so there is no deadlock.
 */
abstract class EngineHostService(private val role: EngineRole) : Service() {
    private lateinit var server: EngineServer
    private val ended = AtomicBoolean(false)

    @Volatile private var events: IEngineEvents? = null

    override fun onCreate() {
        super.onCreate()
        server = EngineServer(applicationContext, role, lowerPriority = true)
        Faults.at("service_create", role, null)
        AppLog.i(TAG, EngineEvents.HOST_START, LogCtx.NONE, "role" to role.proc, "pid" to Process.myPid())
    }

    private val binder = object : IEngineHost.Stub() {
        override fun call(op: Bundle): Bundle {
            var tag: CallTag? = null
            var text = true
            return try {
                val decoded = EngineCodec.decodeOp(op)
                tag = decoded.tag
                text = decoded.isText
                val reply = EngineCodec.encode(server.handle(decoded, phasesVia(events)))
                scheduleRelease(reply)
                reply
            } catch (t: Throwable) {
                // An engine failure never crosses as a Binder exception: it is a reply with ok=false.
                if (tag == null) AppLog.w(TAG, "host_call_undecodable", LogCtx.NONE, "type" to t.javaClass.simpleName)
                // Pipeline.embedFitting splits a text on "exceeds maximum", so that phrase must survive the boundary (plan Step 2). Every log line that
                // mentions an error of a text op is redacted (the engine server's spans, main's eRedacted).
                val e = EngineErrors.capture(t, redact = false)
                // Only the one phrase a caller acts on crosses for a text op; the rest of the message could quote the patient's text.
                EngineCodec.error(if (text) e.copy(message = if (e.message?.contains(KEPT_PHRASE) == true) KEPT_PHRASE else null) else e)
            }
        }

        override fun attach(clientToken: IBinder, events: IEngineEvents, clientPid: Int, clientSid: String?) {
            this@EngineHostService.events = events
            AppLog.i(
                TAG, EngineEvents.HOST_START, LogCtx.NONE,
                "role" to role.proc, "pid" to Process.myPid(), "step" to "attach", "client_pid" to clientPid, "client_sid" to clientSid,
            )
            try {
                clientToken.linkToDeath({ end(HostExit.CLIENT_DIED, null) }, 0)
            } catch (_: Exception) {
                // The client is already gone.
                end(HostExit.CLIENT_DIED, null)
            }
        }

        override fun shutdown(reason: String?) = end(HostExit.SHUTDOWN, reason)
    }

    override fun onBind(intent: Intent?): IBinder {
        Faults.at("bind", role, null)
        return binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        end(HostExit.UNBIND, null)
        return false
    }

    override fun onDestroy() {
        end(HostExit.UNBIND, null)
        super.onDestroy()
    }

    private fun phasesVia(sink: IEngineEvents?) = PhaseSink { tag, model, backend, phase ->
        // Synchronous on purpose: main notes the step in its guard before the host runs it.
        runCatching { sink?.phase(tag.requestId, tag.loadId, model, backend, phase) }
    }

    /**
     * Ends the process on purpose, once. Handles are closed first (not for a client death: nobody is left to wait), the log is flushed,
     * then `halt` gives `EXIT_SELF` with the exit's status.
     */
    private fun end(exit: HostExit, reason: String?) {
        if (!ended.compareAndSet(false, true)) return
        if (exit == HostExit.SHUTDOWN) Faults.at("shutdown", role, null)
        if (exit == HostExit.CLIENT_DIED) AppLog.i(TAG, EngineEvents.HOST_CLIENT_DIED, LogCtx.NONE, "role" to role.proc)
        AppLog.i(TAG, EngineEvents.HOST_EXIT, LogCtx.NONE, "role" to role.proc, "why" to exit.why, "reason" to reason)
        AppLog.flush()
        if (exit != HostExit.CLIENT_DIED) runCatching { server.closeAll() }
        Runtime.getRuntime().halt(exit.code)
    }

    private companion object {
        const val TAG = "Engine"

        /** The one phrase of a text-op error message that crosses to main: Pipeline.embedFitting splits an over-long text on it. */
        const val KEPT_PHRASE = "exceeds maximum"

        /** A reply's shared memory can only be closed after Binder has parcelled the reply, which happens after `call` returns. */
        val releaser = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "engine-reply-release").apply { isDaemon = true } }

        fun scheduleRelease(reply: Bundle) {
            releaser.schedule({ EngineCodec.release(reply) }, 10, TimeUnit.SECONDS)
        }
    }
}

class EngineHostLive : EngineHostService(EngineRole.LIVE)

class EngineHostBatch : EngineHostService(EngineRole.BATCH)
