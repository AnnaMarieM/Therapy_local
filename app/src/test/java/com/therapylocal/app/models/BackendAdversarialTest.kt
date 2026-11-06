package com.therapylocal.app.models

import android.content.ContextWrapper
import com.therapylocal.app.config.BackendPolicy
import com.therapylocal.app.models.BackendLadder.NoBackend
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every hostile thing a backend, a gate, a store or an accelerator registration can do to a load: it still loads, or says exactly why not. */
class BackendAdversarialTest {
    private val store = SharedStore()
    private val guard = BackendGuard(store, "b").also { it.recover(null) }
    private val tape = LogTape()
    private val ladder = BackendLadder(guard, tape)
    private val counter = Counter()
    private val tried = ArrayList<String>()

    @After
    fun cleanUp() {
        Accelerators.all().filter { it.id !in setOf("NPU", "GPU", "NNAPI", "CPU") }.forEach { Accelerators.unregister(it.id) }
        BackendPolicy.override = "auto"
    }

    /** Builds an engine on every backend; [throwing] gets what it throws, after closing what it built (as the real engines do). */
    private fun open(throwing: Map<String, () -> Throwable>): (String) -> Engine = { b ->
        tried += b
        val e = Engine(b, counter)
        throwing[b]?.let { t ->
            e.close()
            throw t()
        }
        e
    }

    private fun load(candidates: List<String>, throwing: Map<String, () -> Throwable> = emptyMap(), gate: (String) -> String? = { null }) =
        ladder.load("m", candidates, gate = gate, open = open(throwing), close = { it.close() })

    // ---- what open() throws

    @Test
    fun anOutOfMemoryErrorInOpenFallsToTheNextAndIsFlaggedAsOom() {
        val r = load(listOf("GPU", "CPU"), mapOf("GPU" to { OutOfMemoryError("Failed to allocate 3 GB") }))
        assertEquals("CPU", r.backend)
        tape.assertDeadClear("m", listOf("GPU" to "failed", "CPU" to "proven"))
        assertEquals(true, tape.of("backend_failed").single()["is_oom"])
        counter.assertNoLeak(alive = 1)
    }

    @Test
    fun openThatReallyRunsTheHeapOutFallsToTheCpu() {
        // A real exhaustion inside open(): the chunks are only reachable from the lambda, so they are garbage the moment it throws. At most
        // 1.5 GB is committed; on a bigger heap one 16 GB array makes the JVM itself throw, without committing anything.
        val chunk = 32 shl 20
        val bound = minOf(Runtime.getRuntime().maxMemory() / chunk + 8, 48L).toInt()
        var real = false
        val r = ladder.load("m", listOf("GPU", "CPU"), open = { b ->
            if (b == "GPU") {
                val hog = ArrayList<Any>()
                try {
                    repeat(bound) { hog += ByteArray(chunk) }
                    hog += LongArray(Int.MAX_VALUE - 8)
                } catch (oom: OutOfMemoryError) {
                    hog.clear()
                    real = true
                    throw oom
                }
                error("a 16 GB array fitted in the heap")
            }
            Engine(b, counter)
        }, close = { it.close() })
        assertEquals("CPU", r.backend)
        assertTrue("the heap really ran out", real)
        tape.assertDeadClear("m", listOf("GPU" to "failed", "CPU" to "proven"))
        assertEquals(true, tape.of("backend_failed").single()["is_oom"])
    }

    @Test
    fun aStackOverflowInOpenIsCaughtAndTheCpuLoads() {
        fun recurse(n: Int): Int = recurse(n + 1) + 1
        val r = ladder.load("m", listOf("NPU", "GPU", "CPU"), open = { b -> if (b != "CPU") recurse(0); Engine(b, counter) }, close = { it.close() })
        assertEquals("CPU", r.backend)
        tape.assertDeadClear("m", listOf("NPU" to "failed", "GPU" to "failed", "CPU" to "proven"))
        assertTrue(tape.of("backend_failed").all { it["error"] == StackOverflowError::class.java.name })
    }

    private class NativeAbort(msg: String) : Error(msg)

    @Test
    fun aCustomErrorIsCaughtLikeAnyException() {
        val r = load(listOf("GPU", "CPU"), mapOf("GPU" to { NativeAbort("SIGABRT in libOpenCL") }))
        assertEquals("CPU", r.backend)
        tape.assertDeadClear("m", listOf("GPU" to "failed", "CPU" to "proven"))
        assertTrue(r.skipped.single().contains("SIGABRT"))
    }

    @Test
    fun anExceptionWithoutAMessageIsStillNamedInTheReasons() {
        val e = assertThrows(NoBackend::class.java) { load(listOf("GPU", "CPU"), mapOf("GPU" to { IllegalStateException() }, "CPU" to { RuntimeException() })) }
        assertEquals(listOf("GPU: IllegalStateException", "CPU: RuntimeException"), e.reasons)
        tape.assertDeadClear("m", listOf("GPU" to "failed", "CPU" to "failed"))
        assertEquals(BackendGuard.State.FAILED, guard.record("m", "GPU")!!.state)
    }

    @Test
    fun aHostileMessageCannotBreakTheStoredRecordOrTheSummary() {
        val hostile = "a|b=c\nd;e,f@g " + "x".repeat(10_000)
        assertThrows(NoBackend::class.java) { load(listOf("GPU", "CPU"), mapOf("GPU" to { IllegalStateException(hostile) }, "CPU" to { IllegalStateException(hostile) })) }
        val last = tape.assertDeadClear("m", listOf("GPU" to "failed", "CPU" to "failed"))
        assertNoText("summary", last["summary"] as String, "a|b", "xxxx", "\n")
        for (b in listOf("GPU", "CPU")) {
            val r = guard.record("m", b)!!
            assertEquals(BackendGuard.State.FAILED, r.state)
            assertEquals(1, r.strikes)
            assertTrue("reason kept short: ${r.reason.length}", r.reason.length <= 300)
            assertFalse("no field separator in the reason", r.reason.contains('|'))
        }
        val t = tape.of("backend_failed").first().t!!
        assertNoText("causeChain(messages=false)", BackendLadder.causeChain(t, messages = false), "a|b", "xxxx")
        assertNoText("frames", BackendLadder.frames(t), "a|b", "xxxx")
        assertTrue("the chain with messages caps each message", BackendLadder.causeChain(t).length < 400)
    }

    @Test
    fun aCyclicCauseChainEndsAndNamesEveryThrowableOnce() {
        val a = IllegalStateException("outer")
        val b = RuntimeException("inner", a)
        a.initCause(b)
        assertThrows(NoBackend::class.java) { load(listOf("CPU"), mapOf("CPU" to { a })) }
        tape.assertDeadClear("m", listOf("CPU" to "failed"))
        assertEquals("java.lang.IllegalStateException <- java.lang.RuntimeException", BackendLadder.causeChain(a, messages = false))
        assertEquals("IllegalStateException: outer <- RuntimeException: inner", BackendLadder.causeChain(a))
    }

    @Test
    fun aDeepCauseChainIsCapped() {
        var t: Throwable = RuntimeException("root")
        repeat(50) { t = IllegalStateException("wrap $it", t) }
        assertEquals(8, BackendLadder.causeChain(t, messages = false).split(" <- ").size)
    }

    private class PoisonedMessage : RuntimeException() {
        override val message: String get() = throw IllegalStateException("the message getter itself throws")
    }

    @Test
    fun anExceptionWhoseMessageGetterThrowsDoesNotEscapeTheLadder() {
        val r = load(listOf("GPU", "CPU"), mapOf("GPU" to { PoisonedMessage() }))
        assertEquals("CPU", r.backend)
        tape.assertDeadClear("m", listOf("GPU" to "failed", "CPU" to "proven"))
        BackendLadder.causeChain(PoisonedMessage())
    }

    @Test
    fun anAnonymousExceptionClassIsStillNamedInTheSummary() {
        val r = load(listOf("GPU", "CPU"), mapOf("GPU" to { object : IllegalStateException("anon") {} }))
        assertEquals("CPU", r.backend)
        val last = tape.assertDeadClear("m", listOf("GPU" to "failed", "CPU" to "proven"))
        assertFalse(last["summary"].toString(), last["summary"].toString().contains("failed(,"))
        assertFalse(BackendLadder.causeChain(object : RuntimeException("x") {}).startsWith(":"))
    }

    // ---- what happens after open() returned

    @Test
    fun aGuardThatThrowsAfterOpenReturnedDoesNotCostTheEngine() {
        val throwing = object : BackendGuard.Store by store {
            override fun put(key: String, value: String?) {
                if (value?.startsWith("PROVEN") == true) throw IllegalStateException("disk full")
                store.put(key, value)
            }
            override fun putAll(values: Map<String, String?>) {
                if (values.values.any { it?.startsWith("PROVEN") == true }) throw IllegalStateException("disk full")
                values.forEach { (k, v) -> store.put(k, v) }
            }
        }
        val l = BackendLadder(BackendGuard(throwing, "b"), tape)
        val r = l.load("m", listOf("GPU", "CPU"), open = { Engine(it, counter) }, close = { it.close() })
        assertEquals("GPU", r.backend)
        assertFalse(r.engine.closed)
        tape.assertDeadClear("m", listOf("GPU" to "proven"), candidates = listOf("GPU", "CPU"))
        assertTrue(tape.evts().contains("backend_guard_error"))
    }

    @Test
    fun aStepAfterOpenThatThrowsClosesTheEngineEvenWhenCloseItselfThrows() {
        // The only thing after open() that can throw is the log sink; the engine it built must still be closed, and the close failure named.
        var once = true
        val tape2 = LogTape()
        val log = BackendLadder.Log { level, evt, f, t ->
            tape2.write(level, evt, f, t)
            if (evt == "backend_proven" && once) {
                once = false
                throw IllegalStateException("log sink broke")
            }
        }
        val closedTried = ArrayList<String>()
        val r = BackendLadder(guard, log).load("m", listOf("GPU", "CPU"), open = { Engine(it, counter) }, close = {
            closedTried += it.backend
            it.close()
            throw IllegalStateException("close also broke")
        })
        assertEquals("CPU", r.backend)
        assertEquals(listOf("GPU"), closedTried)
        counter.assertNoLeak(alive = 1)
        val closeLine = tape2.of("backend_close_failed").single()
        assertEquals("GPU", closeLine["backend_try"])
        assertEquals(1, tape2.lines.map { it["load_id"] }.distinct().size)
    }

    @Test
    fun aSuccessfulLoadNeverClosesItsEngine() {
        var closes = 0
        val r = ladder.load("m", listOf("GPU", "CPU"), open = { Engine(it, counter) }, close = { closes++ })
        assertEquals(0, closes)
        assertEquals("GPU", r.engine.backend)
        assertFalse(r.engine.closed)
        tape.assertDeadClear("m", listOf("GPU" to "proven"), candidates = listOf("GPU", "CPU"))
    }

    // ---- the gate

    @Test
    fun aGateThatThrowsIsASkipWithItsReasonAndTheLogSaysSo() {
        val r = load(listOf("NPU", "GPU", "CPU"), gate = { if (it != "CPU") throw SecurityException("no permission") else null })
        assertEquals("CPU", r.backend)
        tape.assertDeadClear("m", listOf("NPU" to "skipped", "GPU" to "skipped", "CPU" to "proven"))
        assertEquals(2, tape.of("backend_gate_failed").size)
        assertTrue(tape.of("backend_skipped").all { (it["reason"] as String).startsWith("gate failed: SecurityException") })
        assertEquals(listOf("CPU"), tried)
    }

    @Test
    fun aGateThatReturnsAnEmptyReasonStillSaysWhyItSkipped() {
        val r = load(listOf("GPU", "CPU"), gate = { if (it == "GPU") "" else null })
        assertEquals("CPU", r.backend)
        tape.assertDeadClear("m", listOf("GPU" to "skipped", "CPU" to "proven"))
        assertFalse(r.skipped.single(), r.skipped.single().trimEnd().endsWith(":"))
    }

    @Test
    fun aGateThatSkipsEverythingIsANoBackendWithEveryReason() {
        val e = assertThrows(NoBackend::class.java) { load(listOf("GPU", "CPU"), gate = { "no $it today" }) }
        assertEquals(listOf("GPU: no GPU today", "CPU: no CPU today"), e.reasons)
        tape.assertDeadClear("m", listOf("GPU" to "skipped", "CPU" to "skipped"))
        assertTrue(tried.isEmpty())
        assertNull("a skip is not a strike", guard.record("m", "GPU"))
    }

    // ---- odd candidate lists

    @Test
    fun noCandidatesIsANoBackendThatSaysSo() {
        val e = assertThrows(NoBackend::class.java) { load(emptyList()) }
        assertTrue("the reason is not empty: ${e.message}", e.reasons.isNotEmpty())
        tape.assertDeadClear("m", emptyList())
    }

    @Test
    fun duplicatedAndLowerCaseCandidatesAreTriedOnceEachInUpperCase() {
        val r = load(listOf("gpu", "GPU", "Gpu", "cpu", "CPU"), mapOf("GPU" to { IllegalStateException("x") }))
        assertEquals("CPU", r.backend)
        assertEquals(listOf("GPU", "CPU"), tried)
        tape.assertDeadClear("m", listOf("GPU" to "failed", "CPU" to "proven"))
    }

    @Test
    fun blankAndPaddedCandidatesAreNotTriedAsBackends() {
        val r = load(listOf("", "  ", " gpu ", "cpu"))
        assertEquals("GPU", r.backend)
        assertEquals(listOf("GPU"), tried)
        tape.assertDeadClear("m", listOf("GPU" to "proven"), candidates = listOf("GPU", "CPU"))
    }

    @Test
    fun onlyBlockedCandidatesFailWithoutTryingAnything() {
        repeat(BackendGuard.BLOCK_UNPROVEN) { guard.attempting("m", "NPU"); guard.failed("m", "NPU", "x"); guard.attempting("m", "GPU"); guard.failed("m", "GPU", "y") }
        val e = assertThrows(NoBackend::class.java) { load(listOf("NPU", "GPU")) }
        assertTrue(tried.isEmpty())
        assertTrue(e.reasons.all { it.contains("blocked on this device") })
        tape.assertDeadClear("m", listOf("NPU" to "blocked", "GPU" to "blocked"))
    }

    @Test
    fun aBlockedCandidateIsTriedWhenBlocksAreIgnored() {
        repeat(BackendGuard.BLOCK_UNPROVEN) { guard.attempting("m", "GPU"); guard.failed("m", "GPU", "x") }
        val r = ladder.load("m", listOf("GPU", "CPU"), respectBlocks = false, open = open(emptyMap()), close = { it.close() })
        assertEquals("GPU", r.backend)
        tape.assertDeadClear("m", listOf("GPU" to "proven"), candidates = listOf("GPU", "CPU"))
    }

    @Test
    fun whenEveryCandidateFailsEveryReasonIsListedInOrderAndNothingLeaks() {
        listOf("A1", "A2", "A3").forEachIndexed { i, id -> Accelerators.register(Accelerator(id = id, priority = 10 + i, litert = true)) }
        val order = Accelerators.order("m", ModelRuntime.LITERT)
        assertEquals(listOf("A1", "A2", "A3", "NPU", "GPU", "CPU"), order)
        val errors: Map<String, () -> Throwable> = mapOf(
            "A1" to { OutOfMemoryError("a1") }, "A2" to { NativeAbort("a2") }, "A3" to { IllegalArgumentException("a3") },
            "NPU" to { UnsatisfiedLinkError("a4") }, "GPU" to { IllegalStateException("a5") }, "CPU" to { RuntimeException("a6") },
        )
        val e = assertThrows(NoBackend::class.java) { load(order, errors) }
        assertEquals(order.mapIndexed { i, b -> "$b: a${i + 1}" }, e.reasons)
        order.forEachIndexed { i, b -> assertTrue(e.message!!.contains("$b: a${i + 1}")) }
        tape.assertDeadClear("m", order.map { it to "failed" })
        counter.assertNoLeak()
        assertEquals(order, tried)
    }

    // ---- deliberately wrong accelerators, through the real availability check

    private val ctx by lazy { ContextWrapper(null) }
    private fun accGate(model: String, runtime: ModelRuntime = ModelRuntime.LITERT): (String) -> String? = { Accelerators.unavailable(ctx, it, model, runtime) }

    @Test
    fun anAcceleratorDedicatedToAnotherModelIsSkippedWithAClearReason() {
        Accelerators.register(Accelerator(id = "OTHER_CHIP", priority = 1, models = setOf("other-model"), litert = true))
        val r = ladder.load("m", listOf("OTHER_CHIP", "CPU"), gate = accGate("m"), open = open(emptyMap()), close = { it.close() })
        assertEquals("CPU", r.backend)
        tape.assertDeadClear("m", listOf("OTHER_CHIP" to "skipped", "CPU" to "proven"))
        assertEquals("OTHER_CHIP is dedicated to other-model", tape.decisive("OTHER_CHIP").single()["reason"])
        assertFalse("never offered for the wrong model", "OTHER_CHIP" in Accelerators.order("m", ModelRuntime.LITERT))
    }

    @Test
    fun anAcceleratorWithoutABindingForTheRuntimeIsSkipped() {
        Accelerators.register(Accelerator(id = "ONNX_ONLY", priority = 1, onnxProvider = "qnn"))
        val r = ladder.load("m", listOf("ONNX_ONLY", "CPU"), gate = accGate("m"), open = open(emptyMap()), close = { it.close() })
        assertEquals("CPU", r.backend)
        assertEquals("ONNX_ONLY has no LITERT binding", tape.decisive("ONNX_ONLY").single()["reason"])
        tape.assertDeadClear("m", listOf("ONNX_ONLY" to "skipped", "CPU" to "proven"))
    }

    @Test
    fun anAvailabilityCheckThatThrowsIsASkipNotACrash() {
        Accelerators.register(Accelerator(id = "FLAKY", priority = 1, litert = true, available = { throw UnsatisfiedLinkError("dlopen failed") }))
        val r = ladder.load("m", listOf("FLAKY", "CPU"), gate = accGate("m"), open = open(emptyMap()), close = { it.close() })
        assertEquals("CPU", r.backend)
        assertTrue(tape.decisive("FLAKY").single()["reason"].toString().startsWith("availability check failed: UnsatisfiedLinkError"))
        tape.assertDeadClear("m", listOf("FLAKY" to "skipped", "CPU" to "proven"))
    }

    @Test
    fun anAvailabilityCheckThatSpinsAndReturnsAHugeReasonIsStoredShort() {
        Accelerators.register(Accelerator(id = "SPINNER", priority = 1, litert = true, available = {
            var x = 0L
            repeat(1_000_000) { x += it }
            "busy $x " + "y".repeat(10_000)
        }))
        val r = ladder.load("m", listOf("SPINNER", "CPU"), gate = accGate("m"), open = open(emptyMap()), close = { it.close() })
        assertEquals("CPU", r.backend)
        tape.assertDeadClear("m", listOf("SPINNER" to "skipped", "CPU" to "proven"))
        val skip = guard.overview().single().backends.getValue("SPINNER").second!!
        assertTrue(skip.reason.length <= 300)
    }

    @Test
    fun anUnknownIdIsSkippedByTheAvailabilityCheck() {
        val r = ladder.load("m", listOf("NOPE", "CPU"), gate = accGate("m"), open = open(emptyMap()), close = { it.close() })
        assertEquals("CPU", r.backend)
        assertEquals("unknown accelerator NOPE", tape.decisive("NOPE").single()["reason"])
        tape.assertDeadClear("m", listOf("NOPE" to "skipped", "CPU" to "proven"))
    }

    // ---- a broken store: the guard is a hint, the load is the job

    private class ExplodingStore : BackendGuard.Store {
        override fun get(key: String): String? = throw IllegalStateException("prefs corrupt")
        override fun put(key: String, value: String?) = throw IllegalStateException("disk full")
        override fun keys(): Set<String> = throw IllegalStateException("prefs corrupt")
    }

    @Test
    fun aStoreThatThrowsOnEveryCallStillLoadsAndEveryGuardErrorIsPartOfTheLoad() {
        val l = BackendLadder(BackendGuard(ExplodingStore(), "b"), tape)
        val r = l.load("m", listOf("GPU", "CPU"), open = open(mapOf("GPU" to { IllegalStateException("x") })), close = { it.close() })
        assertEquals("CPU", r.backend)
        assertTrue(tape.of("backend_guard_error").isNotEmpty())
        tape.assertDeadClear("m", listOf("GPU" to "failed", "CPU" to "proven"))
    }

    @Test
    fun aStoreWithGarbageRecordsLoadsAsIfNothingWasKnown() {
        listOf("PROVEN|x|y", "", "BOGUS|1|true|0|r", "BLOCKED|two|false|0|r", "BLOCKED|2|false|later|r", "||||").forEachIndexed { i, junk ->
            val s = SharedStore().apply { map["build"] = "b"; map["b:m@GPU"] = junk; map["pending"] = "@@@"; map["resident"] = ",,," }
            val t = LogTape()
            val r = BackendLadder(BackendGuard(s, "b"), t).load("m", listOf("GPU", "CPU"), open = { Engine(it, counter) }, close = { it.close() })
            assertEquals("garbage #$i '$junk' is not a block", "GPU", r.backend)
            t.assertDeadClear("m", listOf("GPU" to "proven"), candidates = listOf("GPU", "CPU"))
            assertTrue(t.of("ladder_start").single()["history"].toString().startsWith("GPU=never_tried/0/never_proven"))
        }
    }

    @Test
    fun aStoreThatLosesEveryWriteNeverBlocksAndNeverStopsALoad() {
        val forgetful = object : BackendGuard.Store {
            override fun get(key: String): String? = null
            override fun put(key: String, value: String?) {}
            override fun keys(): Set<String> = emptySet()
        }
        val l = BackendLadder(BackendGuard(forgetful, "b"), tape)
        repeat(5) {
            tape.lines.clear()
            val r = l.load("m", listOf("GPU", "CPU"), open = open(mapOf("GPU" to { IllegalStateException("x") })), close = { it.close() })
            assertEquals("CPU", r.backend)
            tape.assertDeadClear("m", listOf("GPU" to "failed", "CPU" to "proven"))
        }
    }

    @Test
    fun noGuardAtAllStillLoadsAndLogs() {
        val r = BackendLadder(null, tape).load("m", listOf("GPU", "CPU"), open = open(mapOf("GPU" to { IllegalStateException("x") })), close = { it.close() })
        assertEquals("CPU", r.backend)
        tape.assertDeadClear("m", listOf("GPU" to "failed", "CPU" to "proven"))
    }

    @Test
    fun aMemoryProbeThatThrowsDoesNotStopTheLoad() {
        val r = BackendLadder(guard, tape, memory = { throw IllegalStateException("no /proc") })
            .load("m", listOf("GPU", "CPU"), open = open(emptyMap()), close = { it.close() })
        assertEquals("GPU", r.backend)
        tape.assertDeadClear("m", listOf("GPU" to "proven"), candidates = listOf("GPU", "CPU"))
    }

    @Test
    fun aModelIdWithAtAndAudioSuffixKeepsItsOwnRecords() {
        val model = "org@gemma-4-e2b+audio"
        assertThrows(NoBackend::class.java) {
            ladder.load(model, listOf("GPU", "CPU"), open = open(mapOf("GPU" to { IllegalStateException("x") }, "CPU" to { IllegalStateException("y") })), close = { it.close() })
        }
        tape.assertDeadClear(model, listOf("GPU" to "failed", "CPU" to "failed"))
        assertEquals(BackendGuard.State.FAILED, guard.record(model, "GPU")!!.state)
        assertNull(guard.record("gemma-4-e2b+audio", "GPU"))
        assertEquals(listOf(model), guard.overview().map { it.model })
    }

    // ---- the CPU and blocks, through the ladder

    @Test
    fun theCpuFailingOverAndOverIsNeverBlockedAndAlwaysTried() {
        repeat(10) {
            tried.clear()
            assertThrows(NoBackend::class.java) { load(listOf("CPU"), mapOf("CPU" to { IllegalStateException("cpu $it") })) }
            assertEquals(listOf("CPU"), tried)
        }
        assertFalse(guard.blocked("m", "CPU"))
        assertEquals(10, guard.record("m", "CPU")!!.strikes)
    }

    @Test
    fun aBackendThatFailsTwiceIsBlockedAndTheNextLoadsGoStraightToTheCpu() {
        repeat(2) { load(listOf("GPU", "CPU"), mapOf("GPU" to { IllegalStateException("OpenCL") })) }
        assertTrue(tape.evts().contains("backend_blocked"))
        tape.lines.clear()
        tried.clear()
        val r = load(listOf("GPU", "CPU"))
        assertEquals("CPU", r.backend)
        assertEquals(listOf("CPU"), tried)
        tape.assertDeadClear("m", listOf("GPU" to "blocked", "CPU" to "proven"))
    }

    // ---- the debug override

    @Test
    fun theOverrideAutoIsCaseInsensitiveInOrderAndAllows() {
        val preferred = listOf("NPU", "GPU", "CPU")
        for (auto in listOf("auto", "AUTO", "Auto")) {
            assertEquals(auto, preferred, BackendPolicy.order(preferred, auto))
            BackendPolicy.override = auto
            assertTrue("allows GPU on '$auto'", BackendPolicy.allows("GPU"))
        }
    }

    @Test
    fun anEmptyOrBlankOverrideMeansAuto() {
        val preferred = listOf("NPU", "GPU", "CPU")
        for (o in listOf("", "  ")) {
            assertEquals("'$o'", preferred, BackendPolicy.order(preferred, o))
            BackendPolicy.override = o
            assertTrue(BackendPolicy.allows("NPU"))
        }
        assertEquals(listOf("GPU", "CPU"), BackendPolicy.order(preferred, " gpu "))
    }

    @Test
    fun anUnknownForcedIdFallsBackToTheCpuThroughTheLadder() {
        val candidates = BackendPolicy.order(Accelerators.order("m", ModelRuntime.LITERT), "warp_drive")
        assertEquals(listOf("WARP_DRIVE", "CPU"), candidates)
        val r = ladder.load("m", candidates, gate = accGate("m"), open = open(emptyMap()), close = { it.close() })
        assertEquals("CPU", r.backend)
        tape.assertDeadClear("m", listOf("WARP_DRIVE" to "skipped", "CPU" to "proven"))
    }

    @Test
    fun forcingTheCpuOrAListWithoutTheCpuDoesWhatItSays() {
        assertEquals(listOf("CPU"), BackendPolicy.order(listOf("NPU", "GPU", "CPU"), "CPU"))
        assertEquals("no fallback to keep when the caller asked for none", listOf("NPU"), BackendPolicy.order(listOf("GPU"), "npu"))
        assertEquals(listOf("GPU", "CPU"), BackendPolicy.order(listOf("cpu"), "gpu"))
    }
}
