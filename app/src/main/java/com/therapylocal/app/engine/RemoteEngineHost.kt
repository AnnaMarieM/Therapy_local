package com.therapylocal.app.engine

import android.app.ActivityManager
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
import com.therapylocal.app.perf.PerfStats
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * The engines of one role (`:live` or `:batch`) in their own process, reached over Binder. The same [EngineHost] contract as
 * [LocalEngineHost]; the host-side [EngineServer] is the same too.
 *
 * This class is the platform half. The logic (generations, death, exit reasons, restart, timeouts, idle stop, the handle registry) is in
 * [EngineConnection], which runs on a fake binding in the JVM tests. Here: the Binder binding with one `ServiceConnection` per
 * generation, the executors, and the production seams.
 *
 * Threads: `engine-conn-<role>` runs every connection callback, the death recipient and the connection's timers; `engine-close-<role>`
 * runs closes, so a hung close never delays a death callback. [call] must not run on the main thread; [closeAsync] and [release] may.
 */
class RemoteEngineHost(
    context: Context,
    override val role: EngineRole,
    private val tuning: EngineTuning = EngineConfig.DEFAULT,
    beforeOpen: () -> Unit = {},
    settler: HandleSettler = HandleSettler.None,
) : EngineHost {
    private val app = context.applicationContext
    private val connExecutor = ScheduledThreadPoolExecutor(1) { r -> Thread(r, "engine-conn-${role.proc}").apply { isDaemon = true } }
        .apply { removeOnCancelPolicy = true }
    private val closeExecutor: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "engine-close-${role.proc}").apply { isDaemon = true } }
    private val binding = AndroidBinding()

    val connection = EngineConnection(
        role = role,
        binding = binding,
        clock = SystemEngineClock,
        scheduler = object : EngineScheduler {
            override fun execute(task: Runnable) { connExecutor.execute(task) }
            override fun schedule(delayMs: Long, task: Runnable): Cancelable {
                val f = connExecutor.schedule(task, delayMs, TimeUnit.MILLISECONDS)
                return Cancelable { f.cancel(false) }
            }
        },
        exits = ExitReasons.source(app),
        cpuTime = CpuTime { pid -> runCatching { ExitReasons.cpuMsFromStat(File("/proc/$pid/stat").readText(), clockTicks) }.getOrNull() },
        mainImportance = {
            val i = ActivityManager.RunningAppProcessInfo()
            ActivityManager.getMyMemoryState(i)
            i.importance
        },
        tuning = tuning,
        log = AppLogSink,
        processName = "${app.packageName}:${role.proc}",
        settler = settler,
        lowMemoryKillReports = runCatching { ActivityManager.isLowMemoryKillReportSupported() }.getOrDefault(false),
        closeExecutor = closeExecutor,
        beforeOpen = beforeOpen,
        decodeTps = { PerfStats.live.value?.decodeTokS ?: 0.0 },
        sysMemory = {
            val mi = ActivityManager.MemoryInfo().also { app.getSystemService(ActivityManager::class.java)?.getMemoryInfo(it) }
            listOf("sys_avail_mb" to (mi.availMem shr 20), "sys_total_mb" to (mi.totalMem shr 20), "sys_low" to mi.lowMemory)
        },
        onView = { EngineState.publish(role, it) },
    )

    override fun call(op: EngineOp, phases: PhaseSink): EngineReply {
        check(Looper.getMainLooper() == null || Looper.myLooper() !== Looper.getMainLooper()) { "engine call on the main thread" }
        return connection.call(op, phases)
    }

    override fun closeAsync(op: EngineOp.Close) = connection.closeAsync(op)

    override fun closeAsync(op: EngineOp.Close, onClosed: (acked: Boolean) -> Unit) = connection.closeAsync(op, onClosed)

    override fun awaitPendingCloses() = connection.awaitPendingCloses()

    override fun statusIfBound(): EngineReply.HostStatus? = connection.statusIfBound()

    /**
     * Asks a bound host to delete its own log files. False when no host is bound (the caller deletes the files itself) or the host did not
     * answer in `closeTimeoutMs`. Never binds.
     */
    fun clearLogsIfBound(): Boolean {
        val op = EngineOp.ClearLogs(CallTag(java.util.UUID.randomUUID().toString().take(8), null, "clear_logs"))
        val f = try {
            closeExecutor.submit<Boolean> { connection.callIfBound(op) is EngineReply.Done }
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
    val isBound: Boolean get() = connection.isBound

    /** Ends the host without waiting; it exits when its client unbinds. Never blocks. */
    fun release() = connection.release()

    /** `:live` before `:batch`: shuts the host down and waits (bounded) for its process to be gone, killing it after that. */
    fun stopForBatch(): Boolean = connection.shutdownAndAwait("batch_starting")

    /** The platform half of the connection: one `ServiceConnection` per generation, all callbacks on `engine-conn-<role>`. */
    private inner class AndroidBinding : EngineBinding {
        override lateinit var listener: EngineBinding.Listener
        private val convs = ConcurrentHashMap<Int, Conn>()
        private val clientToken = Binder()

        private val events = object : IEngineEvents.Stub() {
            override fun phase(req: String, loadId: String?, model: String, backend: String, phase: String) {
                runCatching { listener.phase(req, loadId, model, backend, phase) }
            }
        }

        private inner class Conn(val gen: Int, val startNo: Int) : android.content.ServiceConnection {
            @Volatile var host: IEngineHost? = null
            @Volatile var unbound = false
            val death = IBinder.DeathRecipient { connExecutor.execute { listener.binderDied(gen) } }

            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                val pid: Int
                try {
                    val h = IEngineHost.Stub.asInterface(service)
                    service.linkToDeath(death, 0)
                    h.attach(clientToken, events, Process.myPid(), AppLog.sid)
                    armFaults(h, startNo)
                    pid = hostPid(h)
                    host = h
                } catch (e: Exception) {
                    listener.attachFailed(gen, "attach_failed")
                    return
                }
                listener.connected(gen, pid)
            }

            override fun onServiceDisconnected(name: ComponentName) = listener.disconnected(gen)
            override fun onBindingDied(name: ComponentName) = listener.bindingDied(gen)
            override fun onNullBinding(name: ComponentName) = listener.nullBinding(gen)
        }

        /** The host's pid, from its `Status` (the platform does not hand it to a bound client otherwise). */
        private fun hostPid(h: IEngineHost): Int {
            val op = EngineOp.Status(CallTag("attach", null, "status"))
            val b = EngineCodec.encode(op)
            try {
                return (EngineCodec.decodeReply(h.call(b)) as EngineReply.HostStatus).pid
            } finally {
                EngineCodec.release(b)
            }
        }

        /** Debug lab only: sends the fault plan right after attach, on every generation (the plan is null, so nothing is sent, in every other build). */
        private fun armFaults(h: IEngineHost, startNo: Int) {
            val plan = Faults.plan ?: return
            val b = EngineCodec.encode(EngineOp.ArmFaults(CallTag("arm", null, "arm_faults"), plan, startNo))
            try {
                h.call(b)
            } finally {
                EngineCodec.release(b)
            }
        }

        override fun bind(gen: Int): Boolean {
            val c = Conn(gen, Faults.beforeBind(app, role))
            convs[gen] = c
            val intent = Intent(app, if (role == EngineRole.LIVE) EngineHostLive::class.java else EngineHostBatch::class.java)
            val ok = try {
                app.bindService(intent, Context.BIND_AUTO_CREATE or Context.BIND_NOT_PERCEPTIBLE, connExecutor, c)
            } catch (e: SecurityException) {
                false
            }
            if (!ok) {
                runCatching { app.unbindService(c) }
                convs.remove(gen)
            }
            return ok
        }

        override fun unbind(gen: Int) {
            val c = convs[gen] ?: return
            if (c.unbound) return
            c.unbound = true
            runCatching { app.unbindService(c) }
        }

        override fun release(gen: Int) {
            val c = convs.remove(gen) ?: return
            runCatching { c.host?.asBinder()?.unlinkToDeath(c.death, 0) }
            if (!c.unbound) {
                c.unbound = true
                runCatching { app.unbindService(c) }
            }
        }

        override fun call(gen: Int, op: EngineOp): EngineReply {
            val h = convs[gen]?.host ?: throw BindingFailure(BindingFailure.Kind.DEATH)
            val b = EngineCodec.encode(op)
            try {
                val raw = try {
                    h.call(b)
                } catch (e: RemoteException) {
                    val kind = when (RemoteFailures.classify(e, h.asBinder().isBinderAlive)) {
                        RemoteFailures.Failure.Death -> BindingFailure.Kind.DEATH
                        RemoteFailures.Failure.TooLarge -> BindingFailure.Kind.TOO_LARGE
                        RemoteFailures.Failure.Transport -> BindingFailure.Kind.TRANSPORT
                    }
                    throw BindingFailure(kind, sizeOf(b))
                }
                return EngineCodec.decodeReply(raw)
            } finally {
                EngineCodec.release(b)
            }
        }

        override fun shutdown(gen: Int, reason: String) {
            runCatching { convs[gen]?.host?.shutdown(reason) }
        }

        override fun kill(pid: Int) = Process.killProcess(pid)

        override fun pidAlive(pid: Int): Boolean = File("/proc/$pid").exists()
    }

    private companion object {
        /** `_SC_CLK_TCK` is 100 on Android's kernels. */
        const val clockTicks = 100L

        fun sizeOf(b: android.os.Bundle): Int {
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
    }
}

/** Connection lines into AppLog (which adds `proc=` and `pid=`). */
object AppLogSink : EngineLog {
    private const val TAG = "Engine"

    override fun log(level: Char, event: String, fields: List<Pair<String, Any?>>) {
        val f = fields.toTypedArray()
        when (level) {
            'd' -> AppLog.d(TAG, event, LogCtx.NONE, *f)
            'w' -> AppLog.w(TAG, event, LogCtx.NONE, *f)
            'e' -> AppLog.e(TAG, event, LogCtx.NONE, null, *f)
            else -> AppLog.i(TAG, event, LogCtx.NONE, *f)
        }
    }

    override fun flush() = AppLog.flush()
}
