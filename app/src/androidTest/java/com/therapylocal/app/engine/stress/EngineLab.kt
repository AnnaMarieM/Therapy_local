package com.therapylocal.app.engine.stress

import android.content.Context
import android.os.Process
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.therapylocal.app.config.EngineTuning
import com.therapylocal.app.engine.EngineEvents
import com.therapylocal.app.engine.EngineLabKills
import com.therapylocal.app.engine.EngineRole
import com.therapylocal.app.engine.LabWiring
import com.therapylocal.app.engine.Scenarios
import com.therapylocal.app.log.AppLog
import com.therapylocal.app.models.BackendGuard.ExitKind
import com.therapylocal.app.models.LogFileTape
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume
import org.junit.AssumptionViolatedException
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/** The lab scenarios of `EngineLabActivity`: model rounds in `:batch`, audio embedding in `:live`, text queries in `:live`. */
enum class Scenario(val arg: String) { PROCESSING("processing"), LIVE("live"), SEARCH("search") }

/**
 * The rule every instrumented Step 12 test uses (catalogue 2.2). It owns the whole life of one test against the fake engines.
 *
 * Before the test: kills leftover `:live`/`:batch` processes and drops the cached host connections; resets the guard; clears the logs
 * (main's and the hosts'); installs the lab wiring with [plan] (default: the fakes with no fault) and the lab tuning; takes the baselines
 * (main pid, exit-record watermark, dropbox time); starts [frames], [watchdog] and [sampler].
 *
 * After the test (also when it failed): stops the probes; flushes the logs; writes the data bundle line through [record] (`ui`, `memory`,
 * `processes`, `exits`, `guard`, `fds`, `dropbox` when a dump ran, `story`, `excerpts`, recovery timings from the log); runs the shared
 * checks U0 (exit records of main, frame gap < 1000 ms x scale, watchdog < 500 ms x scale, and the dropbox and window dumps when the
 * test failed or something died), L6 (proc tags) and, with [canaries], L5; stops scenarios, releases the hosts and asserts that no host
 * process is left within `idleMs + 2 s`. A check failure fails the test; when the test body failed already, the body's error wins and
 * the check errors are attached as suppressed.
 *
 *     @RunWith(AndroidJUnit4::class) @Core @Full
 *     class NativeCrashTest {
 *         @get:Rule val lab = EngineLab()
 *
 *         @Test @Case("A07", "A07-batch-llm-initialize-sigabrt")
 *         fun batchLlmInitializeSigabrt() {
 *             val plan = Plans.plan("A07-batch-llm-initialize-sigabrt",
 *                 Plans.rule(at = "initialize", role = "batch", model = "fake-llm", backend = "GPU", start = listOf(1), action = Plans.Do.signal("ABRT")))
 *             lab.install(plan)
 *             val m = lab.logs.mark()
 *             lab.startScenario(Scenario.PROCESSING)
 *             val died = lab.logs.until("engine_died", after = m, where = mapOf("role" to "batch", "phase" to "initialize"))
 *             lab.record.timing("engine_died_to_restart", lab.logs.until("engine_restart", after = m).epochMs() - died.epochMs())
 *             lab.assumeCap("lmk_reports")                    // a gate from the probes, if the row needs one
 *         }
 *     }
 *
 * Opt-outs for the rows that break a rule on purpose: [assertUx] (a test that blocks the main thread itself), [assertLogs],
 * [assertHostsGone], [assertMainIntactChecks].
 */
class EngineLab(
    private val plan: String? = null,
    tuning: EngineTuning? = null,
    private val canaries: List<String> = emptyList(),
) : TestRule {
    /** The data bundle writer for this test (a [RunRecord]); add what the test measured. */
    val record = RunRecord()

    var assertUx = true
    var assertLogs = true
    var assertHostsGone = true
    var assertMainIntactChecks = true

    val ctx: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    val pkg: String get() = ctx.packageName

    /** The lane's `-e scale`. */
    val scale: Double get() = LaneArgs.scale

    /** The lab tuning in force (per-op limits are already multiplied by the scale; `idleMs`, `exitLookupMs`, `liveShutdownMs` are not). */
    val tuning: EngineTuning get() = LabWiring.override?.tuning ?: customTuning ?: LabWiring.labTuning(scale)
    private var customTuning: EngineTuning? = tuning

    /** `ms x scale`, for the few bounds the tuning does not carry (anti-flake rule 2: bounds come from the tuning x scale). */
    fun scaled(ms: Long): Long = (ms * scale).toLong().coerceAtLeast(1)

    /** The default bound of a log wait: open + exit lookup + live shutdown, all from the tuning. */
    val waitMs: Long get() = tuning.openTimeoutMs + tuning.exitLookupMs + tuning.liveShutdownMs + scaled(2_000)

    internal val logs: LogWait by lazy { LogWait(defaultTimeoutMs = waitMs) }
    val frames = FrameProbe()
    val watchdog = MainWatchdog()
    val sampler = ProcSampler()

    /** Main pid at the start (the instrumentation process). */
    var mainPid = 0
        private set

    /** Wall-clock watermark of the exit records, taken in `@Before`. */
    var startWallMs = 0L
        private set
    var dropboxStamp = ""
        private set
    lateinit var guardBefore: GuardSnapshot
        private set

    private var description: Description? = null
    private var installedPlanId: String? = null
    private var installedPlan: String? = null
    private var killedSomething = false
    private var firstOfClass = false
    private var tracing: String? = null

    // ------------------------------------------------------------------------------------------------ plans and scenarios

    /**
     * Arms [planJson] (see [Plans]) and the lab tuning. Drops the cached host connections first, so the next engine call builds hosts
     * with this tuning and the plan's start counters begin at 1. Call before the first engine call of the test.
     */
    fun install(planJson: String, tuning: EngineTuning? = null) {
        Hosts.reset()
        if (tuning != null) customTuning = tuning
        LabWiring.install(planJson, customTuning)
        installedPlan = planJson
        installedPlanId = runCatching { JSONObject(planJson).optString("id") }.getOrNull()
    }

    /**
     * Starts the scenario loop in this process (the instrumentation process is the app's main process), after installing [planJson] if
     * given. [canary] goes into every prompt and query. The loops run until [stopScenarios] or the end of the test.
     */
    fun startScenario(scenario: Scenario, planJson: String? = null, canary: String = "") {
        if (planJson != null) install(planJson)
        Scenarios.start(ctx, scenario.arg, canary)
    }

    /** Ends all scenario loops after their current round; true when they all ended within [joinMs]. */
    fun stopScenarios(joinMs: Long = 10_000): Boolean = Scenarios.stopAll(joinMs)

    /**
     * Starts the scenario through the real `EngineLabActivity` with `am start -W` (the contract of `qa/stress/lib.sh`), as the shell holds
     * `DUMP`. The activity installs the plan itself with the default lab tuning of the plan's scale, which also resets the start counters.
     * Returns the time `am start -W` took. Use for [fgs] (foreground service priority) and [background] (the activity finishes at once).
     *
     * With no [planJson] the plan of the last [install] is passed on (the activity would otherwise install "no plan" and wipe it). A custom
     * [EngineTuning] does not survive the launch: the activity applies the lab default of the plan's scale. Use [startScenario] for it.
     */
    fun launch(scenario: Scenario, planJson: String? = null, fgs: Boolean = false, background: Boolean = false, canary: String? = null): Long {
        val t0 = SystemClock.elapsedRealtime()
        val r = Shell.script(amStart(pkg, "--es scenario ${scenario.arg}", planJson ?: installedPlan, fgs, background, canary))
        check(!r.out.contains("Error") && !r.err.contains("Exception")) { "am start of the lab entry failed: ${r.out} ${r.err}" }
        return SystemClock.elapsedRealtime() - t0
    }

    // ------------------------------------------------------------------------------------------------ gates (Assume)

    /** `Assume` on a boolean probe fact of `lane_caps.json` (for example `lmk_reports`, `bundle_with_shared_memory`). */
    fun assumeCap(key: String, why: String = key) {
        LaneCaps.ensure()
        Assume.assumeTrue("probe says $why is false or missing", LaneCaps.bool(key))
    }

    /** `Assume` that a probe fact is absent or false (P-05: `assumeCapFalse("p05_dialog")` for F01 and K14). Missing counts as not measured, so skipped. */
    fun assumeCapFalse(key: String, why: String = key) {
        LaneCaps.ensure()
        Assume.assumeTrue("probe $why not measured", LaneCaps.has(key))
        Assume.assumeFalse("probe says $why is true", LaneCaps.bool(key))
    }

    /** Skips an lmkd row whose pressure never produced a kill within the cap (rule 4b); the summary counts it as `lmk_not_triggered`. */
    fun skipLmkNotTriggered(): Nothing = throw AssumptionViolatedException("lmk_not_triggered")

    fun assumeApi(min: Int) = Assume.assumeTrue("needs API $min", android.os.Build.VERSION.SDK_INT >= min)

    /** True on a lane where a `SIGKILL` of a host is reported as `UNKNOWN` (LMK reports exist), false where it reads as KILLED_FOR_MEMORY (P-01). */
    fun sigkillIsUnknown(): Boolean = ExitInfos.lmkReports()

    /** The exit kind a plain `SIGKILL` of a host maps to on this lane (the `SIGKILL*` rows of the catalogue). */
    fun sigkillKind(): ExitKind = if (ExitInfos.lmkReports()) ExitKind.UNKNOWN else ExitKind.KILLED_FOR_MEMORY

    // ------------------------------------------------------------------------------------------------ processes

    fun hostPid(role: EngineRole): Int? = ProcStats.pidsOf(if (role == EngineRole.LIVE) ExitInfos.live else ExitInfos.batch).firstOrNull()

    /** Kills the host with signal [sig] from outside (an "external kill"), like `kill -9`. Returns the pid, or null when none runs. */
    fun killHost(role: EngineRole, sig: Int = android.system.OsConstants.SIGKILL): Int? =
        hostPid(role)?.also { Process.sendSignal(it, sig); killedSomething = true }

    /** Main-tagged kill through `EngineLabKills`: the connection records the end as [asKind] and logs `engine_kill why=test`. */
    fun killTagged(role: EngineRole, asKind: ExitKind): Boolean = EngineLabKills.killForTest(ctx, role, asKind).also { if (it) killedSomething = true }

    /** Tells the lab that the test killed something (the heavy U0 dumps then run in `@After`). `killHost`/`killTagged` do it already. */
    fun noteKill() { killedSomething = true }

    /** Kills every `:live`/`:batch` process and waits until they are gone. */
    fun killLeftovers() {
        for (n in listOf(ExitInfos.live, ExitInfos.batch)) ProcStats.pidsOf(n).forEach { Process.killProcess(it); ProcWait.gone(it, 5_000) }
    }

    // ------------------------------------------------------------------------------------------------ shared checks

    /**
     * U0, app intact: the exit records of main since `@Before`, the dropbox and window dumps ([heavy]), `FrameProbe` and `MainWatchdog`
     * thresholds when the probes ran. The rule calls it in `@After`; a test may call it earlier.
     */
    fun assertAppIntact(heavy: Boolean = true) {
        ExitInfos.assertMainIntact(startWallMs)
        check(ProcStats.alive(mainPid)) { "main pid $mainPid is gone" }
        if (heavy) {
            Dropbox.assertMainIntact(dropboxStamp)
            Windows.assertNoErrorDialog()
        }
    }

    /** L6: every host line carries `proc=` and `pid=`, main lines `proc=main`, `backend.log` only main. */
    fun assertProcTags() {
        AppLog.flush(2_000)
        AppLog.logDir()?.let { LogFileTape.assertProcTags(it) }
    }

    /** L5 over our files and logcat for the canaries given to the rule. */
    fun assertNoCanary(extra: List<String> = emptyList()) = Canary.assertClean(canaries + extra)

    /** Guard store now vs `@Before` (L4). */
    fun assertNoStrike() = GuardSnapshot.take().assertSame(guardBefore)

    // ------------------------------------------------------------------------------------------------ the rule

    override fun apply(base: Statement, d: Description): Statement = record.apply(object : Statement() {
        override fun evaluate() {
            before(d)
            var failure: Throwable? = null
            try {
                base.evaluate()
            } catch (t: Throwable) {
                failure = t
                throw t
            } finally {
                after(failure)
            }
        }
    }, d)

    private fun before(d: Description) {
        description = d
        LaneCaps.ensure()
        killedSomething = false
        mainPid = Process.myPid()
        Scenarios.stopAll(5_000)
        Hosts.reset()
        ProcWait.hostsGone(5_000)
        killLeftovers()
        // After the leftovers are gone, so their exit records are older than the watermark.
        startWallMs = ExitInfos.watermark()
        firstOfClass = lastClass != d.className
        if (firstOfClass) { lastClass = d.className; classDropboxStamp = Dropbox.now() }
        dropboxStamp = classDropboxStamp // the dumps cover the class so far
        LabWiring.reset()
        GuardSnapshot.guard().reset()
        AppLog.clear()
        AppLog.flush(5_000)
        guardBefore = GuardSnapshot.take()
        val id = d.getAnnotation(Case::class.java)?.let { it.plan.ifEmpty { it.id } } ?: d.methodName
        install(plan ?: Plans.clean(id), customTuning)
        if (d.getAnnotation(Trace::class.java) != null) tracing = Perfetto.start(id)
        sampler.start()
        frames.start()
        watchdog.start()
    }

    private fun after(failure: Throwable?) {
        val errors = ArrayList<Throwable>()
        fun guarded(step: () -> Unit) { try { step() } catch (t: Throwable) { errors += t } }

        val dog = watchdog.stop()
        val fr = frames.stop()
        val procs = sampler.stop()
        guarded { Hosts.existing(EngineRole.LIVE)?.connection?.statusIfBound(); Hosts.existing(EngineRole.BATCH)?.connection?.statusIfBound() } // a round trip: host lines are written before it answers
        AppLog.flush(2_000)

        val exits = ExitInfos.since(startWallMs)
        // The dropbox and window dumps cost 0.5-1.5 s: once per class, and per test only when it failed, killed something, or a host ended
        // other than by its own halt (idle stops write EXIT_SELF records on every test, so those do not count).
        val heavy = failure != null || killedSomething || firstOfClass || exits.any { ExitInfos.kindOf(it) != ExitKind.BENIGN }
        guarded { writeBundle(fr, dog, procs, exits, heavy) }

        if (assertMainIntactChecks) guarded { assertAppIntact(heavy) }
        if (assertUx) {
            guarded { fr.assertNoAnrClassGap(scale) }
            guarded { dog.assertResponsive(500, scale) }
        }
        if (assertLogs) guarded { assertProcTags() }
        if (canaries.isNotEmpty()) guarded { assertNoCanary() }

        guarded { if (!stopScenarios()) throw AssertionError("a scenario loop did not end") }
        guarded { Hosts.reset() }
        val gone = ProcWait.hostsGone(tuning.idleMs + 2_000)
        if (!gone) {
            val left = listOf(ExitInfos.live, ExitInfos.batch).flatMap { ProcStats.pidsOf(it) }
            killLeftovers()
            if (assertHostsGone) errors += AssertionError("host processes left after teardown: $left")
        }
        LabWiring.reset()
        if (tracing != null) guarded { Perfetto.finish(tracing!!)?.let { record.section("perfetto", it) } }
        tracing = null

        if (errors.isEmpty()) return
        if (failure != null) errors.forEach { failure.addSuppressed(it) } else throw errors.first().also { e -> errors.drop(1).forEach { e.addSuppressed(it) } }
    }

    private fun writeBundle(fr: FrameProbe.Stats, dog: MainWatchdog.Stats, procs: ProcSampler.Summary, exits: List<android.app.ApplicationExitInfo>, heavy: Boolean) {
        procs.writeTo(record)
        record.section("ui", JSONObject().put("frame_gap_ms", fr.gaps.toJson()).put("frame_time_ms", fr.frameTimes.toJson()).put("watchdog_max_ms", dog.latency.max)
            .put("cue_blank_events", 0).put("error_text_hits", 0).put("dialogs", JSONArray()))
        record.section("fds", JSONObject().put("main_before", procs.before.mainFds ?: JSONObject.NULL).put("main_after", procs.after.mainFds ?: JSONObject.NULL))
        record.section("guard", JSONObject().put("before", guardBefore.toJson()).put("after", GuardSnapshot.take().toJson()))
        exits.reversed().forEach { ExitInfos.record(record, it) }
        if (heavy) record.section("dropbox_entries", JSONArray(Dropbox.tags(Dropbox.entries(dropboxStamp))))
        val all = logs.lines()
        if (!record.hasSection("story")) {
            val story = all.filter { it.proc == "main" && (it.evt in EngineEvents.ALL || it.evt in LADDER) }
            record.section("story", JSONObject().put("ok", true).put("load_ids", JSONArray(LogFileTape.byLoadId(all).keys.toList()))
                .put("events", JSONArray(story.map { it.evt })))
        }
        val died = all.firstOrNull { it.evt == EngineEvents.DIED }
        if (died != null && !record.hasSection("excerpts")) {
            record.section("excerpts", JSONObject().put("main", JSONArray(logs.excerpt(EngineEvents.DIED))))
        }
        val restart = all.firstOrNull { it.evt == EngineEvents.RESTART }
        val connected = restart?.let { r -> all.firstOrNull { it.evt == EngineEvents.CONNECTED && it.epochMs() >= r.epochMs() } }
        if (died != null && restart != null) record.timing("engine_died_to_restart", restart.epochMs() - died.epochMs())
        if (restart != null && connected != null) record.timing("restart_to_connected", connected.epochMs() - restart.epochMs())
        val ls = all.firstOrNull { it.evt == "ladder_start" }
        val ld = all.lastOrNull { it.evt == "ladder_done" }
        if (ls != null && ld != null) record.timing("ladder_total", ld.epochMs() - ls.epochMs())
    }

    companion object {
        @Volatile private var lastClass: String? = null
        @Volatile private var classDropboxStamp = ""
        private val LADDER = setOf("ladder_start", "backend_attempting", "backend_failed", "backend_proven", "backend_skipped", "ladder_done", "ladder_failed")

        /**
         * The `am start` line of the lab entry (see `qa/stress/lib.sh`). [extra] is `--es scenario X` or `--es action vault_lock`.
         * Quoting: use through `Shell.script`.
         */
        fun amStart(pkg: String, extra: String, planJson: String?, fgs: Boolean, background: Boolean, canary: String?): String = buildString {
            append("am start -W -n $pkg/.engine.EngineLabActivity $extra")
            planJson?.let { append(" --es plan ${Shell.quote(it)}") }
            if (fgs) append(" --ez fgs true")
            if (background) append(" --ez background true")
            canary?.let { append(" --es canary ${Shell.quote(it)}") }
        }
    }
}
