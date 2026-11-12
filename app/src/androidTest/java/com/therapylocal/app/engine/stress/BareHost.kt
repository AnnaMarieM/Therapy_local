package com.therapylocal.app.engine.stress

import android.app.ApplicationExitInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.therapylocal.app.engine.CallTag
import com.therapylocal.app.engine.EngineHostBatch
import com.therapylocal.app.engine.EngineHostLive
import com.therapylocal.app.engine.EngineOp
import com.therapylocal.app.engine.EngineReply
import com.therapylocal.app.engine.EngineRole
import com.therapylocal.app.engine.IEngineEvents
import com.therapylocal.app.engine.IEngineHost
import com.therapylocal.app.engine.wire.EngineCodec
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A host bound the way production binds it (`BIND_AUTO_CREATE or BIND_NOT_PERCEPTIBLE`) but without the connection logic, so the probes
 * see the platform and not our recovery. No models, no fault plan: a bare host that answers `Status`.
 */
class BareHost private constructor(val role: EngineRole, private val ctx: Context, private val conn: ServiceConnection) {
    @Volatile var host: IEngineHost? = null
    @Volatile var pid: Int = -1
    @Volatile var disconnected = false
    /** What the connection callbacks saw, for the message of a failed start: `connected`, `attached`, `binding_died`, `null_binding`, `disconnected`. */
    internal val trace = java.util.concurrent.ConcurrentLinkedQueue<String>()
    @Volatile internal var connectError: Throwable? = null
    var connectMs: Long = -1
        private set

    /** One `Status` call; the reply names the host's pid. */
    fun status(extraInline: String? = null): EngineReply {
        val b = EngineCodec.encode(EngineOp.Status(CallTag("probe", null, "status")))
        try {
            if (extraInline != null) b.putString("probe_pad", extraInline) // ignored by the codec, counted by Binder
            return EngineCodec.decodeReply(host!!.call(b))
        } finally {
            EngineCodec.release(b)
        }
    }

    /**
     * Unbinds and waits (bounded) for the host process to be gone. A host halts itself on unbind; binding again while it is still dying makes
     * Android delay the next start by about 10 s ("Process ... has died" with a pending bind; seen on a real phone). EngineConnection waits
     * the same way, so a probe that rebinds must too.
     */
    fun unbind() {
        runCatching { ctx.unbindService(conn) }
        awaitRoleGone(ctx, role, ROLE_GONE_MS)
    }

    /** Waits (bounded) until the process is gone; true if it is. The wait is the stimulus' own bound, not a sleep to synchronise. */
    fun awaitGone(timeoutMs: Long = 10_000): Boolean {
        val end = SystemClock.elapsedRealtime() + timeoutMs
        while (ProcStats.alive(pid)) {
            if (SystemClock.elapsedRealtime() > end) return false
            Thread.sleep(25) // polling /proc, bounded above
        }
        return true
    }

    companion object {
        private const val FLAGS = Context.BIND_AUTO_CREATE or Context.BIND_NOT_PERCEPTIBLE
        private const val ROLE_GONE_MS = 15_000L

        private fun processName(ctx: Context, role: EngineRole) = ctx.packageName + if (role == EngineRole.LIVE) ":live" else ":batch"

        /**
         * Waits (bounded) until no process of the role is alive; true if so. Not just the pid this host had: while it was bound and died, the
         * system restarts a crashed *bound* service after 1 s for the connection that is still open ("Scheduling restart of crashed service ...
         * for connection"), so a second process of the role is spawned for nobody. It halts itself on the unbind that follows, but only once
         * it has cold-started (4 to 8 s on a loaded CI emulator), and a bind made meanwhile lands on that halting process: it dies under the
         * new connection, the system schedules another 1 s restart, and the new bind pays two cold starts (p05, 2026-11-12, run 43125264056).
         * Waiting for the pid alone (dead already) does not see this; waiting for the role does.
         */
        private fun awaitRoleGone(ctx: Context, role: EngineRole, timeoutMs: Long): Boolean {
            val name = processName(ctx, role)
            val end = SystemClock.elapsedRealtime() + timeoutMs
            while (ProcStats.pidsOf(name).isNotEmpty()) {
                if (SystemClock.elapsedRealtime() > end) return false
                Thread.sleep(25) // polling /proc, bounded above
            }
            return true
        }

        fun start(role: EngineRole, timeoutMs: Long = 30_000): BareHost {
            val ctx = InstrumentationRegistry.getInstrumentation().targetContext
            val latch = CountDownLatch(1)
            lateinit var h: BareHost
            // A previous host (or the restart the system made for its still-open connection) must be gone before this bind. Bounded; if it is
            // still there the failure message says so.
            val roleClear = awaitRoleGone(ctx, role, ROLE_GONE_MS)
            val t0 = SystemClock.elapsedRealtime()
            val conn = object : ServiceConnection {
                private fun at() = "@${SystemClock.elapsedRealtime() - t0}ms"

                override fun onServiceConnected(name: ComponentName, service: IBinder) {
                    h.trace += "connected" + at()
                    try {
                        val host = IEngineHost.Stub.asInterface(service)
                        host.attach(Binder(), object : IEngineEvents.Stub() {
                            override fun phase(req: String?, loadId: String?, model: String?, backend: String?, phase: String?) {}
                        }, Process.myPid(), "probe")
                        h.trace += "attached" + at()
                        h.host = host
                        h.pid = (h.status() as EngineReply.HostStatus).pid
                        h.connectMs = SystemClock.elapsedRealtime() - t0
                        latch.countDown()
                    } catch (t: Throwable) {
                        h.connectError = t // not a missing connection: report what failed instead of letting the latch time out
                        latch.countDown()
                    }
                }

                override fun onServiceDisconnected(name: ComponentName) {
                    h.disconnected = true
                    h.trace += "disconnected" + at()
                }

                override fun onBindingDied(name: ComponentName) { h.trace += "binding_died" + at() }

                override fun onNullBinding(name: ComponentName) { h.trace += "null_binding" + at() }
            }
            h = BareHost(role, ctx, conn)
            val cls = if (role == EngineRole.LIVE) EngineHostLive::class.java else EngineHostBatch::class.java
            check(ctx.bindService(Intent(ctx, cls), FLAGS, Runnable::run, conn)) { "bindService refused for $role" }
            val arrived = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            h.connectError?.let { throw IllegalStateException("host $role connected but the first call failed: $it (callbacks: ${h.trace.joinToString()})", it) }
            check(arrived) {
                "host $role did not connect in $timeoutMs ms (callbacks: ${h.trace.joinToString().ifEmpty { "none" }}; " +
                    "role processes alive now: ${ProcStats.pidsOf(processName(ctx, role)).ifEmpty { "none" }}; role was clear before the bind: $roleClear)"
            }
            return h
        }
    }
}
