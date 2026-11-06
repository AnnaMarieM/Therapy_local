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

    fun unbind() { runCatching { ctx.unbindService(conn) } }

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

        fun start(role: EngineRole, timeoutMs: Long = 10_000): BareHost {
            val ctx = InstrumentationRegistry.getInstrumentation().targetContext
            val latch = CountDownLatch(1)
            lateinit var h: BareHost
            val t0 = SystemClock.elapsedRealtime()
            val conn = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, service: IBinder) {
                    val host = IEngineHost.Stub.asInterface(service)
                    host.attach(Binder(), object : IEngineEvents.Stub() {
                        override fun phase(req: String?, loadId: String?, model: String?, backend: String?, phase: String?) {}
                    }, Process.myPid(), "probe")
                    h.host = host
                    h.pid = (h.status() as EngineReply.HostStatus).pid
                    h.connectMs = SystemClock.elapsedRealtime() - t0
                    latch.countDown()
                }

                override fun onServiceDisconnected(name: ComponentName) { h.disconnected = true }
            }
            h = BareHost(role, ctx, conn)
            val cls = if (role == EngineRole.LIVE) EngineHostLive::class.java else EngineHostBatch::class.java
            check(ctx.bindService(Intent(ctx, cls), FLAGS, Runnable::run, conn)) { "bindService refused for $role" }
            check(latch.await(timeoutMs, TimeUnit.MILLISECONDS)) { "host $role did not connect in $timeoutMs ms" }
            return h
        }
    }
}

/** `getHistoricalProcessExitReasons`, polled up to a bound (anti-flake rule 4a: AMS writes the record asynchronously). */
object ExitInfos {
    class Found(val info: ApplicationExitInfo, val latencyMs: Long)

    fun await(pid: Int, timeoutMs: Long = 5_000): Found? {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val am = ctx.getSystemService(android.app.ActivityManager::class.java)
        val t0 = SystemClock.elapsedRealtime()
        while (true) {
            am.getHistoricalProcessExitReasons(ctx.packageName, pid, 1).firstOrNull()?.let { return Found(it, SystemClock.elapsedRealtime() - t0) }
            if (SystemClock.elapsedRealtime() - t0 > timeoutMs) return null
            Thread.sleep(50) // bounded poll, see above
        }
    }
}
