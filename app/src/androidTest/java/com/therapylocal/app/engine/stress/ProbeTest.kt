package com.therapylocal.app.engine.stress

import android.content.Intent
import android.os.Process
import android.os.SharedMemory
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.app.ApplicationExitInfo
import android.os.TransactionTooLargeException
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.therapylocal.app.MainActivity
import com.therapylocal.app.config.EngineConfig
import com.therapylocal.app.engine.EngineRole
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * P-01..P-05, the platform probes (catalogue 3.P). They log the facts the design relies on and write them to `lane_caps.json`; other tests
 * read the facts (`LaneCaps`) instead of assuming them. Every lane runs them: they carry @Core, and `LaneCaps.ensure()` covers a lane whose
 * filter leaves them out. Only the first two assertions of P-01 and P-04's 128 KB are hard; everything else is data.
 */
@RunWith(AndroidJUnit4::class)
@Core
@Full
class ProbeTest {
    @get:Rule val record = RunRecord()

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * Kills a bare host with [sig], reads its exit record, returns what the platform said. `exit(3)` is not here: a bare host has no way to
     * call it without the S11 fault plan (`do.kind=exit`); it is recorded as `p01_exit3 = needs_fault_plan` until S11 lands.
     */
    private fun killAndRead(name: String, sig: Int): JSONObject {
        val h = BareHost.start(EngineRole.BATCH)
        record.process("batch", h.pid)
        Process.sendSignal(h.pid, sig)
        assertTrue("$name: host ${h.pid} did not die", h.awaitGone())
        val found = ExitInfos.await(h.pid)
        h.unbind()
        assertNotNull("$name: every kill yields an exit record (polled 5 s)", found)
        val i = found!!.info
        record.exit("${ctx.packageName}:batch", h.pid, i.reason.toString(), i.status, null, i.description)
        return JSONObject().put("reason", i.reason).put("status", i.status).put("description", i.description ?: JSONObject.NULL)
            .put("importance", i.importance).put("latency_ms", found.latencyMs)
    }

    @Test
    @Case("P-01")
    fun p01ExitRecordPerKillKind() {
        LaneCaps.ensure()
        val segv = killAndRead("SEGV", OsConstants.SIGSEGV)
        val abrt = killAndRead("ABRT", OsConstants.SIGABRT)
        val kill = killAndRead("KILL", OsConstants.SIGKILL)
        // The only hard facts: a native signal that debuggerd handles is CRASH_NATIVE. The SIGKILL mapping is data (lmkd lanes differ).
        assertEquals("SEGV", ApplicationExitInfo.REASON_CRASH_NATIVE, segv.getInt("reason"))
        assertEquals("ABRT", ApplicationExitInfo.REASON_CRASH_NATIVE, abrt.getInt("reason"))
        LaneCaps.put("p01_segv", segv)
        LaneCaps.put("p01_abrt", abrt)
        LaneCaps.put("p01_kill", kill)
        LaneCaps.put("p01_exit3", "needs_fault_plan")
        LaneCaps.put("exit_record_latency_ms", maxOf(segv.getLong("latency_ms"), abrt.getLong("latency_ms"), kill.getLong("latency_ms")))
        record.timing("exit_info_latency", kill.getLong("latency_ms"))
    }

    @Test
    @Case("P-02")
    fun p02OomAdjIsReadable() {
        LaneCaps.ensure()
        val h = BareHost.start(EngineRole.BATCH)
        try {
            record.process("main", Process.myPid())
            record.process("batch", h.pid)
            val host = ProcStats.oomScoreAdj(h.pid)
            val main = ProcStats.oomScoreAdj(Process.myPid())
            assertNotNull("/proc/<pid>/oom_score_adj of the host is readable", host)
            // Data only: under am instrument main is FOREGROUND_APP_ADJ, so this does not represent the field (finding 29).
            LaneCaps.put("p02_adj", JSONObject().put("host", host).put("main", main)
                .put("flags", "BIND_AUTO_CREATE|BIND_NOT_PERCEPTIBLE").put("note", "instrumented main is foreground; field check is the HOST lane"))
        } finally {
            h.unbind()
        }
    }

    @Test
    @Case("P-03")
    fun p03ShellCapabilities() {
        LaneCaps.ensure()
        // `am crash` would kill the process under test, so only its presence is probed. Nothing is asserted.
        val probes = linkedMapOf(
            "am_crash" to "am help | grep -c '^  crash'",
            "am_send_trim_memory" to "am help | grep -c 'send-trim-memory'",
            "dumpsys_dropbox" to "dumpsys dropbox | head -3",
            "dumpsys_meminfo" to "dumpsys meminfo --checkin | head -1",
            "cmd_device_state" to "cmd device_state print-states",
            "perfetto_version" to "perfetto --version",
            "adb_root" to "id -u",
        )
        val j = JSONObject()
        for ((k, cmd) in probes) {
            val r = Shell.run(cmd)
            j.put(k, JSONObject().put("exit", r.exit ?: JSONObject.NULL).put("out", r.out.take(200)).put("err", r.err.take(200)))
        }
        LaneCaps.put("p03_shell", j)
    }

    @Test
    @Case("P-04")
    fun p04SharedMemoryAndInlineLimit() {
        LaneCaps.ensure()
        LaneCaps.put("page_size", Os.sysconf(OsConstants._SC_PAGESIZE))
        val shm = SharedMemory.create("probe", 1 shl 16)
        shm.close()
        LaneCaps.put("bundle_with_shared_memory", true) // the full Bundle-with-SharedMemory path is exercised by EngineCodecTest and G-tests
        val h = BareHost.start(EngineRole.BATCH)
        var maxOk = 0
        try {
            for (kb in listOf(64, 128, 256, 512, 768, 900)) {
                try {
                    h.status("x".repeat(kb * 1024))
                    maxOk = kb * 1024
                } catch (e: TransactionTooLargeException) {
                    break
                } catch (e: android.os.RemoteException) {
                    if (e.cause is TransactionTooLargeException || e.message?.contains("TransactionTooLarge") == true) break
                    throw e
                }
            }
        } finally {
            h.unbind()
        }
        LaneCaps.put("max_inline_bytes", maxOk)
        assertTrue("inline calls up to ${EngineConfig.INLINE_MAX_BYTES} bytes succeed (max seen $maxOk)", maxOk >= EngineConfig.INLINE_MAX_BYTES)
    }

    /**
     * Crashes `:batch` three times within 60 s with `MainActivity` in front and records whether Android shows a "keeps stopping" dialog and
     * whether a rebind still works. F01 and K14 `Assume` on `p05_dialog == false`. The crash is a SIGSEGV delivered to the bare host; the
     * `host_start` fault plan (S11) would crash it earlier in its life, which changes nothing about the dialog question.
     */
    @Test
    @Case("P-05")
    fun p05RepeatedCrashShowsNoDialog() {
        LaneCaps.ensure()
        val inst = InstrumentationRegistry.getInstrumentation()
        val activity = inst.startActivitySync(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            val t0 = SystemClock.elapsedRealtime()
            repeat(3) {
                val h = BareHost.start(EngineRole.BATCH)
                Process.sendSignal(h.pid, OsConstants.SIGSEGV)
                assertTrue("crash ${it + 1}: host died", h.awaitGone())
                h.unbind()
            }
            val within = SystemClock.elapsedRealtime() - t0
            val windows = Shell.out("dumpsys window windows") + Shell.out("dumpsys activity activities | grep -i AppErrorDialog")
            val dialog = Regex("keeps stopping|has stopped|Application Error|AppErrorDialog", RegexOption.IGNORE_CASE).containsMatchIn(windows)
            val r0 = SystemClock.elapsedRealtime()
            val again = BareHost.start(EngineRole.BATCH)
            val rebindMs = SystemClock.elapsedRealtime() - r0
            val pid = again.pid
            again.unbind()
            assertTrue("a rebind still works (pid $pid)", pid > 0)
            LaneCaps.put("p05_dialog", dialog)
            LaneCaps.put("p05_rebind_ms", rebindMs)
            LaneCaps.put("p05_three_crashes_ms", within)
            record.timing("restart_to_connected", rebindMs)
        } finally {
            activity.finish()
        }
    }
}
