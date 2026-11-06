package com.therapylocal.app.engine.stress

import android.os.Process
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.therapylocal.app.engine.EngineRole
import com.therapylocal.app.engine.FaultPlanException
import com.therapylocal.app.engine.LabWiring
import com.therapylocal.app.log.AppLog
import com.therapylocal.app.log.LogCtx
import com.therapylocal.app.models.BackendGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * One small proof per shared instrumented helper (Step 12 helpers): the plan builder, the rule, `LogWait`, `FrameProbe`, `MainWatchdog`,
 * `ProcSampler`, `ExitInfos` and the checks. None of these starts a host process, so they are cheap on every lane. Test writers can copy
 * the shape of any of them.
 */
@RunWith(AndroidJUnit4::class)
@Full
@FullOnly
class HelpersExampleTest {
    @get:Rule val lab = EngineLab()

    @Test
    fun ruleInstallsTheLabWiringAndTheFakes() {
        assertNotNull("the lab wiring is armed", LabWiring.override)
        assertEquals("fake-llm", LabWiring.model("gemma-4-e2b"))
        assertEquals(1_000, lab.tuning.idleMs)
        assertEquals(lab.mainPid, Process.myPid())
        lab.assumeCap("page_size") // a probe fact: skipped, never failed, where the probes did not record it
    }

    @Test
    fun plansAreValidatedWhenBuilt() {
        val plan = Plans.plan(
            "ex-1",
            Plans.rule(at = "initialize", role = "batch", model = "fake-llm", backend = "GPU", start = listOf(1), action = Plans.Do.signal("ABRT")),
            Plans.rule(at = "ask", role = "batch", call = listOf(2), action = Plans.Do.hang(ms = 5_000)),
            Plans.rule(at = "embed_text", action = Plans.Do.corrupt("nan")),
        )
        assertTrue(plan.contains("\"initialize\""))
        try {
            Plans.plan("bad", Plans.rule(at = "nowhere", action = Plans.Do.delay(1)))
            fail("an unknown point must be rejected")
        } catch (e: FaultPlanException) {
            assertTrue(e.message!!.contains("nowhere"))
        }
    }

    @Test
    fun logWaitSeesLinesAfterAMarkAndFailsWithTailsOnTimeout() {
        val m = lab.logs.mark()
        AppLog.i("HelperExample", "helper_event", LogCtx.NONE, "k" to "v1", "n" to 7)
        val l = lab.logs.until("helper_event", after = m, where = mapOf("k" to "v1", "n" to 7))
        assertEquals("main", l.proc)
        assertEquals(1, lab.logs.count("helper_event", after = m, where = mapOf("k" to Regex("v\\d"))))
        lab.logs.assertNone("helper_event", where = mapOf("k" to "other"), after = m)
        val t0 = SystemClock.elapsedRealtime()
        val e = runCatching { lab.logs.until("never_happens", timeoutMs = 300) }.exceptionOrNull()
        assertTrue(e is AssertionError && e.message!!.contains("---"))
        assertTrue(SystemClock.elapsedRealtime() - t0 < 5_000)
    }

    @Test
    fun logWaitSequenceAndStall() {
        val m = lab.logs.mark()
        AppLog.i("HelperExample", "tick", LogCtx.NONE)
        AppLog.i("HelperExample", "boundary", LogCtx.NONE)
        SystemClock.sleep(25) // the stimulus: log lines carry millisecond stamps, and a "later" tick must really be later (a fast emulator logs all three in one ms)
        AppLog.i("HelperExample", "tick", LogCtx.NONE)
        val seq = lab.logs.untilSequence("tick", "boundary", "tick", after = m)
        assertEquals(3, seq.size)
        val stall = lab.logs.stallMs("tick", seq[1])
        assertNotNull("no tick found after the boundary line: ${seq.map { it.text() }}", stall)
        assertTrue("stall $stall ms", stall!! in 0..5_000)
    }

    @Test
    fun frameProbeAndWatchdogSeeABlockedMainThread() {
        lab.assertUx = false // this test blocks the main thread itself; the rule's own probes must not judge it
        val f = FrameProbe().also { it.start() }
        val w = MainWatchdog(periodMs = 50).also { it.start() }
        SystemClock.sleep(300) // the stimulus: let a few frames and pings pass
        runOnMain { SystemClock.sleep(400) } // a main thread stall of 400 ms is seen by both
        SystemClock.sleep(200)
        val fs = f.stop()
        val ws = w.stop()
        assertTrue("main gap ${fs.maxGapMs}", fs.maxGapMs >= 350)
        assertTrue("watchdog ${ws.latency.max}", ws.latency.max >= 300)
        fs.assertNoAnrClassGap(scale = 1.0) // 400 ms is below the 1000 ms ANR-class bound
        ws.assertResponsive(limitMs = 1_000, scale = 1.0)
    }

    @Test
    fun procSamplerSeesMainAndItsOomAdj() {
        val s = ProcSampler(periodMs = 50, pssEvery = 1).also { it.start() }
        SystemClock.sleep(250) // gather a few samples
        val sum = s.stop()
        assertEquals(listOf(Process.myPid()), sum.pids("main"))
        assertTrue(sum.pids("batch").isEmpty())
        assertNotNull(sum.oomAdj("main"))
        assertTrue((sum.peakPssMb("main") ?: 0) > 0)
        assertNull(sum.oomAdj("batch"))
    }

    @Test
    fun exitInfosWatermarkAndFilters() {
        val t0 = ExitInfos.watermark()
        assertTrue(ExitInfos.since(t0 + 60_000).isEmpty())
        assertTrue(ExitInfos.badForMain(t0).isEmpty())
        ExitInfos.assertMainIntact(t0)
        assertEquals("${lab.pkg}:batch", ExitInfos.batch)
    }

    @Test
    fun amStartLineFollowsTheScriptContract() {
        val cmd = EngineLab.amStart("com.therapylocal.app", "--es scenario live", "{\"v\":1}", fgs = true, background = true, canary = "CANARY-PT-7f3a-x")
        assertEquals(
            "am start -W -n com.therapylocal.app/.engine.EngineLabActivity --es scenario live --es plan '{\"v\":1}' --ez fgs true --ez background true --es canary 'CANARY-PT-7f3a-x'",
            cmd,
        )
    }

    @Test
    fun guardSnapshotEnumeratesTheGuardsOwnRecords() {
        val before = GuardSnapshot.take()
        lab.assertNoStrike()
        GuardSnapshot.guard().failed("fake-llm", "GPU", "helper example strike")
        val after = GuardSnapshot.take()
        after.assertFault("fake-llm|GPU", BackendGuard.State.FAILED)
        assertEquals(setOf("fake-llm|GPU"), after.diff(before).keys)
        assertTrue(runCatching { after.assertSame(before) }.isFailure)
    }

    @Test
    fun errTextAndCanaryChecks() {
        ErrText.assertClean(listOf("Recording paused, will retry"))
        assertEquals(1, ErrText.hits(listOf("ok", "DeadObjectException: x")).size)
        val c = Canary.make("H00")
        assertTrue(c.startsWith(Canary.PREFIX))
        Canary.assertClean(listOf(c), logcat = false)
        assertNull(lab.hostPid(EngineRole.BATCH))
    }
}
