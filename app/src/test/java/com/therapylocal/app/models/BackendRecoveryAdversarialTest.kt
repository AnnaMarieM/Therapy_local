package com.therapylocal.app.models

import com.therapylocal.app.models.BackendGuard.ExitKind
import com.therapylocal.app.models.BackendGuard.LastExit
import com.therapylocal.app.models.BackendGuard.Record
import com.therapylocal.app.models.BackendGuard.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Processes that die mid-attempt or mid-work, over and over, and stores that lie: each new start must still know what to try. */
class BackendRecoveryAdversarialTest {
    private val store = SharedStore()
    private val build = "1/100/fp"

    /** A new process: a new guard on the same store, recovering from how the last one ended. */
    private fun start(exit: LastExit?, b: String = build) = BackendGuard(store, b).let { g -> g to g.recover(exit) }
    private fun guard() = BackendGuard(store, build)

    private fun exit(kind: ExitKind, foreground: Boolean = true) = LastExit(kind, foreground, "test ${kind.name}")

    /** One start that begins loading [backend] for [model] and is killed before the attempt ends. */
    private fun dieWhileLoading(model: String, backend: String, exit: LastExit?) {
        start(null)
        guard().attempting(model, backend)
        start(exit)
    }

    /** One start that loads [backend], proves it, keeps it resident and is killed while it works. */
    private fun dieWhileLoaded(model: String, backend: String, exit: LastExit?): List<BackendGuard.Event> {
        start(null)
        guard().apply { attempting(model, backend); proven(model, backend); loaded(model, backend) }
        return start(exit).second
    }

    private fun ladderLoad(tape: LogTape, open: (String) -> String = { it }) =
        BackendLadder(guard(), tape).load("gemma", listOf("GPU", "CPU"), open = open, close = {})

    @Test
    fun aKillLoopWhileLoadingEndsBlockedForEveryExitThatIsTheBackendsFault() {
        val kinds = listOf(exit(ExitKind.KILLED_FOR_MEMORY), exit(ExitKind.KILLED_FOR_MEMORY, foreground = false), exit(ExitKind.NATIVE_CRASH), exit(ExitKind.ANR))
        for (k in kinds) {
            store.map.clear()
            dieWhileLoading("gemma", "GPU", k)
            assertEquals("after one ${k.kind}", State.DIED, guard().record("gemma", "GPU")!!.state)
            dieWhileLoading("gemma", "GPU", k)
            assertTrue("blocked after two ${k.kind}", guard().blocked("gemma", "GPU"))
            assertTrue(guard().record("gemma", "GPU")!!.reason.startsWith("process died while loading"))
        }
    }

    @Test
    fun anExitThatMayNotBeTheBackendsFaultOnlyCountsWhenItRepeatsDuringTheSameAttempt() {
        for (k in listOf(exit(ExitKind.CRASH), exit(ExitKind.UNKNOWN), null)) {
            store.map.clear()
            dieWhileLoading("gemma", "GPU", k)
            assertEquals("one ${k?.kind} is not counted", 0, guard().record("gemma", "GPU")!!.strikes)
            dieWhileLoading("gemma", "GPU", k)
            assertEquals("two ${k?.kind} in a row are", 1, guard().record("gemma", "GPU")!!.strikes)
            assertTrue(guard().record("gemma", "GPU")!!.reason.startsWith("process ended 2 times while loading"))
            repeat(2) { dieWhileLoading("gemma", "GPU", k) }
            assertTrue("a loop of them still ends blocked: ${k?.kind}", guard().blocked("gemma", "GPU"))
        }
    }

    @Test
    fun theNextLadderAfterAKillLoopGoesStraightToTheCpu() {
        repeat(2) { dieWhileLoading("gemma", "GPU", exit(ExitKind.KILLED_FOR_MEMORY)) }
        val tape = LogTape()
        val tried = ArrayList<String>()
        val r = ladderLoad(tape) { tried += it; it }
        assertEquals("CPU", r.backend)
        assertEquals(listOf("CPU"), tried)
        tape.assertDeadClear("gemma", listOf("GPU" to "blocked", "CPU" to "proven"))
        assertTrue(tape.decisive("GPU").single()["record_reason"].toString().contains("killed_for_memory"))
    }

    @Test
    fun aUserSwipeMidAttemptIsNeverAStrikeHoweverOften() {
        repeat(20) { dieWhileLoading("gemma", "GPU", exit(ExitKind.BENIGN, foreground = true)) }
        assertEquals(0, guard().record("gemma", "GPU")!!.strikes)
        assertFalse(guard().blocked("gemma", "GPU"))
        assertTrue("the open attempt is closed by the start", start(null).second.isEmpty())
    }

    @Test
    fun aProvenEngineKilledInTheForegroundEveryTimeIsBlockedAfterThree() {
        val evts = (1..3).map { dieWhileLoaded("gemma", "GPU", exit(ExitKind.KILLED_FOR_MEMORY)).map { it.evt } }
        assertEquals(listOf(listOf("backend_died_while_loaded"), listOf("backend_died_while_loaded"), listOf("backend_blocked")), evts)
        val r = guard().record("gemma", "GPU")!!
        assertEquals(State.BLOCKED, r.state)
        assertTrue(r.everProven)
        val tape = LogTape()
        assertEquals("CPU", ladderLoad(tape).backend)
        tape.assertDeadClear("gemma", listOf("GPU" to "blocked", "CPU" to "proven"))
    }

    @Test
    fun aResidentEngineIsOnlyBlamedForAForegroundMemoryKillOrANativeCrash() {
        val notItsFault = listOf(exit(ExitKind.KILLED_FOR_MEMORY, foreground = false), exit(ExitKind.CRASH),
            exit(ExitKind.BENIGN), exit(ExitKind.UNKNOWN), null)
        notItsFault.forEach { k -> repeat(5) { assertTrue("${k?.kind}", dieWhileLoaded("gemma", "GPU", k).isEmpty()) } }
        assertEquals(0, guard().record("gemma", "GPU")!!.strikes)
        assertEquals(listOf("backend_died_while_loaded"), dieWhileLoaded("gemma", "GPU", exit(ExitKind.NATIVE_CRASH, foreground = false)).map { it.evt })
        // A hang in a native call on the loaded engine is the engine's doing too.
        assertEquals(listOf("backend_died_while_loaded"), dieWhileLoaded("gemma", "GPU", exit(ExitKind.ANR)).map { it.evt })
    }

    @Test
    fun theResidentSetIsForgottenAfterOneStartSoOneKillIsOneStrike() {
        dieWhileLoaded("gemma", "GPU", exit(ExitKind.NATIVE_CRASH))
        repeat(3) { assertTrue(start(exit(ExitKind.NATIVE_CRASH)).second.isEmpty()) }
        assertEquals(1, guard().record("gemma", "GPU")!!.strikes)
    }

    @Test
    fun aCleanUnloadBetweenKillsResetsTheCountForAProvenEngine() {
        repeat(10) {
            dieWhileLoaded("gemma", "GPU", exit(ExitKind.KILLED_FOR_MEMORY))
            dieWhileLoaded("gemma", "GPU", exit(ExitKind.KILLED_FOR_MEMORY))
            start(null)
            guard().apply { attempting("gemma", "GPU"); proven("gemma", "GPU"); loaded("gemma", "GPU"); unloaded("gemma", "GPU") }
        }
        assertFalse(guard().blocked("gemma", "GPU"))
        assertEquals(0, guard().record("gemma", "GPU")!!.strikes)
    }

    @Test
    fun theCpuDyingOverAndOverIsNeverBlockedAndTheLadderStillTriesIt() {
        listOf(ExitKind.KILLED_FOR_MEMORY, ExitKind.NATIVE_CRASH, ExitKind.CRASH, ExitKind.ANR, ExitKind.UNKNOWN).forEach { k ->
            repeat(4) { dieWhileLoading("gemma", "CPU", exit(k)) }
        }
        val r = guard().record("gemma", "CPU")!!
        assertEquals(State.DIED, r.state)
        // Four each of the three that are the backend's doing, and two each of the two that only count when repeated.
        assertEquals(4 * 3 + 2 * 2, r.strikes)
        // A loaded CPU engine is not resident: killing it in use is not counted either.
        assertTrue(dieWhileLoaded("gemma", "CPU", exit(ExitKind.NATIVE_CRASH)).isEmpty())
        val tape = LogTape()
        val r2 = BackendLadder(guard(), tape).load("gemma", listOf("CPU"), open = { it }, close = {})
        assertEquals("CPU", r2.backend)
        tape.assertDeadClear("gemma", listOf("CPU" to "proven"))
    }

    @Test
    fun aNewBuildForgivesABlockAndAnAttemptLeftOpen() {
        repeat(2) { dieWhileLoading("gemma", "GPU", exit(ExitKind.NATIVE_CRASH)) }
        guard().attempting("gemma", "NPU")
        val (g, events) = start(exit(ExitKind.NATIVE_CRASH), "1/200/fp")
        assertEquals(listOf("backend_guard_reset_new_build"), events.map { it.evt })
        assertNull(g.record("gemma", "GPU"))
        assertNull(g.record("gemma", "NPU"))
        assertTrue("the next start of the new build is quiet", BackendGuard(store, "1/200/fp").recover(exit(ExitKind.NATIVE_CRASH)).isEmpty())
    }

    @Test
    fun resetForgetsBlocksButNotTheBuild() {
        repeat(2) { dieWhileLoading("gemma", "GPU", exit(ExitKind.ANR)) }
        guard().reset()
        assertNull(guard().record("gemma", "GPU"))
        assertTrue(guard().overview().isEmpty())
        assertTrue("no reset_new_build after a manual reset", start(null).second.isEmpty())
    }

    @Test
    fun manyModelsAndBackendsInterleavedStayApart() {
        val models = listOf("gemma-4-e2b", "gemma-4-e2b+audio", "gemma-4-e4b", "emb@v2", "a@b@c")
        val backends = listOf("NPU", "GPU", "CPU")
        start(null)
        models.forEachIndexed { mi, m ->
            backends.forEachIndexed { bi, b ->
                repeat((mi + bi) % 3) { guard().apply { attempting(m, b); failed(m, b, "$m/$b") } }
            }
        }
        models.forEachIndexed { mi, m ->
            backends.forEachIndexed { bi, b ->
                val n = (mi + bi) % 3
                val r = guard().record(m, b)
                if (n == 0) assertNull("$m $b", r) else {
                    assertEquals("$m $b", n, r!!.strikes)
                    assertEquals("$m/$b", r.reason)
                    assertEquals(b != "CPU" && n >= 2, r.state == State.BLOCKED)
                }
            }
        }
        assertEquals(models.sorted(), guard().overview().map { it.model })
    }

    @Test
    fun modelIdsWithAtAndAudioSurviveThePendingAndResidentNotes() {
        dieWhileLoading("x@y+audio", "GPU", exit(ExitKind.KILLED_FOR_MEMORY))
        assertEquals(1, guard().record("x@y+audio", "GPU")!!.strikes)
        val events = dieWhileLoaded("x@y+audio", "NPU", exit(ExitKind.NATIVE_CRASH))
        assertEquals(listOf("x@y+audio" to "NPU"), events.map { it.model to it.backend })
        assertNull(guard().record("x", "y+audio@NPU"))
    }

    @Test
    fun aLowerCaseBackendNameIsTheSameRecord() {
        start(null)
        guard().apply { attempting("m", "gpu"); failed("m", "Gpu", "x"); attempting("m", "GPU"); failed("m", "gpu", "y") }
        assertTrue(guard().blocked("m", "GPU"))
        assertTrue(guard().blocked("m", "gpu"))
        assertEquals(setOf("GPU"), guard().overview().single().backends.keys)
    }

    @Test
    fun corruptRecordsDecodeAsUnknownNotAsACrash() {
        val junk = listOf(null, "", "PROVEN|x|y", "|||||", "BOGUS|1|true|0|r", "proven|1|true|0|r", "PROVEN|1|true|notlong|r",
            "PROVEN|99999999999|true|0|r", "PROVEN|1|true|0", "\u0000|\n|\t|  |")
        junk.forEach { assertNull("'$it'", Record.decode(it)) }
        val ok = Record.decode("BLOCKED|2|false|0|reason|with|pipes")!!
        assertEquals("reason|with|pipes", ok.reason)
    }

    @Test
    fun garbagePendingAndResidentNotesNeverStrikeAPhantomBackend() {
        start(null)
        for (pending in listOf("nonsense", "@GPU", "m@", "@", "")) {
            store.map["pending"] = pending
            store.map["resident"] = ",@,x@,@GPU,,"
            start(exit(ExitKind.NATIVE_CRASH))
            assertTrue("pending '$pending' left: ${store.map.keys}", store.map.keys.none { it.startsWith("b:") })
            assertNull(store.map["pending"])
            assertNull(store.map["resident"])
        }
    }

    @Test
    fun garbageKeysDoNotBreakTheOverview() {
        start(null)
        store.map["b:"] = "x"; store.map["b:@"] = "x"; store.map["s:m@GPU"] = "notatime"; store.map["s:m@NPU"] = "12|why|with|pipes"
        store.map["sel:m"] = "GPU|never"; store.map["sel:"] = ""
        val m = guard().overview().first { it.model == "m" }
        assertEquals("GPU", m.selected)
        assertNull(m.selectedAt)
        assertEquals("why|with|pipes", m.backends.getValue("NPU").second!!.reason)
        assertEquals(0L, m.backends.getValue("GPU").second!!.at)
        guard().all()
    }

    @Test
    fun aHostileReasonIsStoredShortAndDecodable() {
        start(null)
        val g = guard()
        g.attempting("m", "GPU")
        g.failed("m", "GPU", "a|b\nc=d " + "z".repeat(10_000))
        val r = g.record("m", "GPU")!!
        assertTrue(r.reason.length <= 300)
        assertFalse(r.reason.contains('|'))
        g.attempting("m", "NPU")
        assertEquals("error", g.failed("m", "NPU", null).reason)
    }

    @Test
    fun scatteredPromptErrorsNeverStrikeButARunOfThemDemotesTheBackend() {
        start(null)
        val g = guard()
        g.attempting("m", "GPU"); g.proven("m", "GPU")
        // A thousand bad prompts, each followed by a good one: the prompts' fault, not the GPU's.
        repeat(1000) { assertNull(g.inferenceFailed("m", "GPU", "too long $it")); g.inferenceOk("m", "GPU") }
        assertEquals(State.PROVEN, g.record("m", "GPU")!!.state)
        assertEquals(0, g.record("m", "GPU")!!.strikes)
        assertFalse(g.demoted("m", "GPU"))
        // Failing again and again with nothing in between is the backend: struck, and skipped for the rest of this run.
        repeat(BackendGuard.INFERENCE_STRIKE - 1) { assertNull(g.inferenceFailed("m", "GPU", "delegate error")) }
        val struck = g.inferenceFailed("m", "GPU", "delegate error")!!
        assertEquals(1, struck.strikes)
        assertTrue(struck.reason.contains("prompts in a row"))
        assertTrue(g.demoted("m", "GPU"))
        assertFalse("a new process gives it another chance", BackendGuard(store, build).demoted("m", "GPU"))
        assertNull("the CPU is never struck", g.inferenceFailed("m", "CPU", "x"))
    }

    @Test
    fun anUnloadOnlyForgivesAProvenRecord() {
        start(null)
        val g = guard()
        g.attempting("m", "GPU"); g.failed("m", "GPU", "x")
        g.unloaded("m", "GPU")
        assertEquals(1, g.record("m", "GPU")!!.strikes)
        g.unloaded("never", "GPU")
        assertNull(g.record("never", "GPU"))
    }

    @Test
    fun manyThreadsHammeringOneGuardLeaveDecodableRecords() {
        start(null)
        val g = guard()
        val pool = Executors.newFixedThreadPool(16)
        val go = CountDownLatch(1)
        val errors = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        val models = (0 until 4).map { "m$it" }
        val backends = listOf("NPU", "GPU", "CPU")
        repeat(16) { t ->
            pool.execute {
                go.await()
                try {
                    repeat(2_000) { i ->
                        val m = models[(t + i) % models.size]
                        val b = backends[(t * 7 + i) % backends.size]
                        when (i % 7) {
                            0 -> g.attempting(m, b)
                            1 -> g.proven(m, b)
                            2 -> g.failed(m, b, "t$t|$i")
                            3 -> g.loaded(m, b)
                            4 -> g.unloaded(m, b)
                            5 -> g.inferenceFailed(m, b, "x")
                            else -> { g.blocked(m, b); g.skipped(m, b, "mem"); g.selected(m, b) }
                        }
                    }
                } catch (e: Throwable) {
                    errors += e
                }
            }
        }
        go.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS))
        assertTrue("errors: ${errors.take(3)}", errors.isEmpty())
        store.map.filterKeys { it.startsWith("b:") }.forEach { (k, v) -> assertNotNull("$k='$v'", Record.decode(v)) }
        assertFalse(g.blocked("m0", "CPU"))
        // The next start must cope with whatever the hammering left open.
        start(exit(ExitKind.NATIVE_CRASH))
        store.map.filterKeys { it.startsWith("b:") }.forEach { (k, v) -> assertNotNull("$k='$v'", Record.decode(v)) }
    }
}
