package com.therapylocal.app.engine

import android.app.ApplicationExitInfo
import com.therapylocal.app.config.EngineTuning
import com.therapylocal.app.models.BackendGuard.ExitKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/** N01-N14, N18, N24-N31, N33-N35 and N37 of the catalogue: the connection against a scripted binding and a virtual clock. */
class EngineConnectionTest {
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "test-caller").apply { isDaemon = true } }

    private fun <T> async(f: () -> T): Future<T> = pool.submit<T> { f() }
    private fun <T> Future<T>.result(): T = get(20, TimeUnit.SECONDS)
    private fun <T> Future<T>.failure(): Throwable = try { get(20, TimeUnit.SECONDS); throw AssertionError("expected a failure") } catch (e: java.util.concurrent.ExecutionException) { e.cause!! }

    private inline fun <reified T : Throwable> expect(block: () -> Unit): T {
        try { block() } catch (t: Throwable) { if (t is T) return t else throw t }
        throw AssertionError("expected ${T::class.java.simpleName}")
    }

    private fun crashOnOpenGpu(r: Rig, reason: Int = ApplicationExitInfo.REASON_CRASH_NATIVE, status: Int = 11) {
        r.binding.script = { p, op ->
            if (op is EngineOp.Open && op.backend == "GPU") {
                r.binding.die(p, reason, status)
                throw BindingFailure(BindingFailure.Kind.DEATH)
            }
            null
        }
    }

    // --- N01, N04 (the S9 part), N03

    @Test fun deathMidCallCarriesRoleGenerationExitPhaseAndOp() {
        val r = Rig()
        r.binding.script = { p, op ->
            if (op is EngineOp.Open) {
                r.binding.listener.phase(op.tag.requestId, "L", "m", op.backend, "initialize")
                r.binding.die(p, ApplicationExitInfo.REASON_CRASH_NATIVE, 11)
                throw BindingFailure(BindingFailure.Kind.DEATH)
            }
            null
        }
        val phases = CopyOnWriteArrayList<String>()
        val d = expect<EngineDied> {
            r.conn.call(EngineOp.Open(r.tag("open", "L"), EngineKind.LLM, "m", "g", "GPU"), PhaseSink { _, _, _, ph -> phases += ph })
        }
        assertEquals(EngineRole.BATCH, d.role)
        assertEquals(1, d.generation)
        assertEquals("open", d.op)
        assertEquals("initialize", d.phase)
        assertEquals(ExitKind.NATIVE_CRASH, d.exit.kind)
        assertEquals(listOf("initialize"), phases)
        assertEquals(1, r.log.count(EngineEvents.DIED))
        assertEquals("open", r.log.all(EngineEvents.DIED).single().f["op"])
        assertEquals(0, r.settler.died.size) // no handle was open
    }

    @Test fun deathDuringOpenRestartsAtOnceAndTheNextBackendRunsOnTheNextGeneration() {
        val r = Rig()
        crashOnOpenGpu(r)
        expect<EngineDied> { r.open("GPU") }
        val deadAt = r.time.now
        val opened = r.open("CPU")
        assertEquals(listOf(1, 2), r.binding.binds)
        assertEquals(deadAt, r.binding.bindAt[2]) // backoffMs[0] = 0
        assertEquals(1, opened.handle)
        assertEquals(1, r.log.count(EngineEvents.RESTART))
        assertEquals(0L, r.log.all(EngineEvents.RESTART).single().f["backoff_ms"])
    }

    @Test fun handleNumbersAreUniqueAcrossGenerationsAndAStaleHandleNeverReachesTheNewProcess() {
        val r = Rig()
        val a = r.open()
        r.binding.die(1, ApplicationExitInfo.REASON_CRASH, 1)
        val b = r.open() // gen 2; the host numbers its first handle 1 again
        assertNotEquals(a.handle, b.handle)
        val before = r.binding.calls.size
        val d = expect<EngineDied> { r.ask(a.handle) }
        assertEquals(1, d.generation)
        assertEquals(ExitKind.CRASH, d.exit.kind)
        assertEquals(before, r.binding.calls.size) // never sent
        assertEquals("ok", r.ask(b.handle).text)
        val failed = r.log.all(EngineEvents.CALL_FAILED).single()
        assertEquals("stale_handle", failed.f["why"])
        assertEquals(1, failed.f["gen_had"])
        assertEquals(2, failed.f["gen_now"])
    }

    // --- N02: many callers, one death

    @Test fun thirtyTwoConcurrentCallersAreAllReleasedByOneDeath() {
        val r = Rig(autoAdvance = false)
        val h = r.open()
        r.binding.script = { p, op -> if (op is EngineOp.Ask) r.binding.block(p) else null }
        val futures = (1..32).map { async { r.ask(h.handle) } }
        // each caller is inside the (blocked) transact before the process dies
        val deadline = System.nanoTime() + 20_000_000_000L
        while (r.binding.calls.count { it.second is EngineOp.Ask } < 32) {
            if (System.nanoTime() > deadline) throw AssertionError("callers never reached the host")
            Thread.yield()
        }
        val t0 = r.time.now
        r.binding.die(1, ApplicationExitInfo.REASON_LOW_MEMORY, 0)
        futures.forEach {
            val e = it.failure() as EngineDied
            assertEquals(ExitKind.KILLED_FOR_MEMORY, e.exit.kind)
        }
        assertEquals(t0, r.time.now)
        assertEquals(1, r.log.count(EngineEvents.DIED))
        assertEquals(32, r.log.all(EngineEvents.DIED).single().f["calls"])
    }

    // --- N05, N07, N06

    @Test fun backoffIsExactAndTheStormCapOpensAndClosesAgain() {
        val r = Rig()
        crashOnOpenGpu(r)
        val expected = listOf(0L, 500, 1000, 2000, 4000, 4000)
        for (i in 1..6) {
            expect<EngineDied> { r.open("GPU") }
            if (i < 6) {
                val deadAt = r.time.now
                expect<EngineDied> { r.open("GPU") } // waits its backoff, binds, dies again
                assertEquals("death $i", expected[i - 1], r.binding.bindAt.getValue(i + 1) - deadAt)
                // the call above was death number i + 1: step back so each loop turn counts one more death exactly once
                return backoffTail(r, i + 1, expected)
            }
        }
    }

    private fun backoffTail(r: Rig, deathsSoFar: Int, expected: List<Long>) {
        var deaths = deathsSoFar
        while (deaths < 6) {
            val deadAt = r.time.now
            expect<EngineDied> { r.open("GPU") }
            deaths++
            assertEquals("bind after death ${deaths - 1}", expected[deaths - 2], r.binding.bindAt.getValue(deaths) - deadAt)
        }
        assertEquals(1, r.log.count(EngineEvents.CIRCUIT_OPEN))
        val u = expect<EngineUnavailable> { r.open("CPU") }
        assertEquals("storm", u.why)
        assertTrue(u.retryInMs in 1..120_000)
        assertEquals(6, r.binding.binds.size) // no bind while the circuit is open
        r.time.advance(u.retryInMs + 1)
        assertEquals("ok", r.ask(r.open("CPU").handle).text) // the window has passed
    }

    @Test fun backoffResetsAfterAStableRunWithASuccessfulCall() {
        val r = Rig()
        crashOnOpenGpu(r)
        expect<EngineDied> { r.open("GPU") }
        val h = r.open("CPU") // gen 2
        r.time.advance(r.tuning.stableMs + 1)
        r.ask(h.handle) // a successful call after the stable period
        r.binding.die(2, ApplicationExitInfo.REASON_CRASH, 1)
        val deadAt = r.time.now
        r.open("CPU")
        assertEquals(0L, r.binding.bindAt.getValue(3) - deadAt) // as if it were the first death
    }

    // --- N24: AMS redelivery and the five callbacks

    @Test fun callbacksOfAnOlderConnectionObjectAreIgnoredAndEveryEndUnbinds() {
        val r = Rig()
        r.open()
        r.binding.die(1, ApplicationExitInfo.REASON_CRASH, 1, callbacks = listOf("disconnected"))
        assertTrue(1 in r.binding.unbinds)
        r.open() // gen 2
        // AMS restarts the dead service and redelivers on the OLD connection object
        r.binding.listener.disconnected(1)
        r.binding.listener.connected(1, 4242)
        r.binding.listener.binderDied(1)
        assertEquals(3, r.log.count(EngineEvents.STALE_CALLBACK))
        assertEquals(1, r.log.count(EngineEvents.DIED)) // gen 2 untouched
        assertEquals("ok", r.ask(r.open().handle).text)
        assertEquals(listOf(1, 2), r.binding.binds)
    }

    @Test fun nullBindingBindingDiedAndAttachFailureAreStartFailuresThatNeverStrike() {
        for (mode in listOf("null" to "bind_null_binding", "died" to "bind_binding_died", "attach_failed" to "bind_attach_failed", "refuse" to "bind_refused")) {
            val r = Rig()
            r.binding.onBind = { mode.first }
            val u = expect<EngineUnavailable> { r.open() }
            assertEquals(mode.second, u.why)
            assertEquals(EngineRole.BATCH, u.role)
            assertEquals(1, r.log.count(EngineEvents.BIND_TIMEOUT))
            assertTrue(r.settler.died.isEmpty())
            assertEquals(0, r.binding.calls.size) // no Open was ever attempted
        }
    }

    @Test fun aBindTimeoutIsAStartFailureAndTheCapAppliesAfterwards() {
        val r = Rig()
        r.binding.onBind = { "silent" }
        val t0 = r.time.now
        val u = expect<EngineUnavailable> { r.open() }
        assertEquals("bind_timeout", u.why)
        assertEquals(r.tuning.bindTimeoutMs, r.time.now - t0)
        repeat(5) { expect<EngineUnavailable> { r.open() } }
        assertEquals(6, r.binding.binds.size)
        val storm = expect<EngineUnavailable> { r.open() }
        assertEquals("storm", storm.why)
        assertEquals(6, r.binding.binds.size)
        assertEquals(1, r.log.count(EngineEvents.CIRCUIT_OPEN))
        assertTrue(r.log.count(EngineEvents.UNAVAILABLE) >= 1)
    }

    // --- N13, N25

    @Test fun aDoubleBinderDiedGivesOneEngineDied() {
        val r = Rig()
        val h = r.open()
        r.binding.die(1, ApplicationExitInfo.REASON_CRASH_NATIVE, 6)
        r.binding.listener.binderDied(1)
        r.binding.listener.disconnected(1)
        assertEquals(1, r.log.count(EngineEvents.DIED))
        assertEquals(1, r.settler.died.size)
        assertEquals("g-m" to "CPU", r.settler.died.single().first to r.settler.died.single().second)
        assertEquals(h.handle, 1)
    }

    @Test fun aDeathWithNoCallStillLooksUpTheExitAndLogsCallNone() {
        val r = Rig()
        r.open()
        val ran = r.time.executed.get()
        r.binding.die(1, ApplicationExitInfo.REASON_LOW_MEMORY, 0, lag = 2)
        r.time.advance(r.tuning.exitLookupMs) // nobody waits: the poll is a task of the connection executor
        assertTrue(r.time.executed.get() > ran) // the work went through the connection executor
        val died = r.log.all(EngineEvents.DIED).single()
        assertEquals("none", died.f["call"])
        assertEquals("killed_for_memory", died.f["exit_kind"])
        assertEquals("low_memory", died.f["reason_name"])
        assertEquals(1, r.log.count(EngineEvents.EXIT_REASON_PENDING)) // the record lagged two polls
        assertEquals(1, r.binding.released.size)
    }

    // --- N18: exit lookup, expected exits

    @Test fun anExitRecordThatNeverArrivesGivesUnknownAfterTheLookupBound() {
        val r = Rig()
        r.open()
        val t0 = r.time.now
        r.binding.die(1, null)
        r.time.advance(r.tuning.exitLookupMs)
        val d = r.log.all(EngineEvents.DIED).single()
        assertEquals("unknown", d.f["exit_kind"])
        assertTrue(d.f["description"] == "exit record not found")
        assertEquals(r.tuning.exitLookupMs, r.time.now - t0)
        assertEquals(1, r.log.count(EngineEvents.EXIT_REASON_PENDING))
    }

    @Test fun anOlderRecordOfARecycledPidIsNeverUsed() {
        val r = Rig()
        r.open()
        // a record of the same pid and process name from before this generation was bound
        r.binding.records += ExitRecord(ApplicationExitInfo.REASON_LOW_MEMORY, 0, r.time.wallMs() - 10_000, "pkg:batch", 1001, 100, 0, 0, "old") to 0
        r.binding.die(1, ApplicationExitInfo.REASON_CRASH, 1)
        assertEquals("crash", r.log.all(EngineEvents.DIED).single().f["exit_kind"])
    }

    // --- N27: transport

    @Test fun aTransportErrorIsRetriedOnceThenUnavailableAndNeverAStrike() {
        val r = Rig()
        val h = r.open()
        var fails = 1
        r.binding.script = { _, op -> if (op is EngineOp.Ask && fails-- > 0) throw BindingFailure(BindingFailure.Kind.TRANSPORT) else null }
        assertEquals("ok", r.ask(h.handle).text)
        assertEquals("transport", r.log.all(EngineEvents.RETRY).single().f["why"])
        fails = 2
        val u = expect<EngineUnavailable> { r.ask(h.handle) }
        assertEquals("transport", u.why)
        assertTrue(r.settler.died.isEmpty())
        assertEquals(0, r.log.count(EngineEvents.DIED))
        assertEquals("ok", r.ask(h.handle).text) // same host, same handle
    }

    @Test fun aTooLargeTransactionKeepsTheHostAndIsNoDeath() {
        val r = Rig()
        val h = r.open()
        r.binding.script = { _, op -> if (op is EngineOp.Ask) throw BindingFailure(BindingFailure.Kind.TOO_LARGE, 2_000_000) else null }
        val e = expect<ReplyTooLarge> { r.ask(h.handle) }
        assertEquals(2_000_000, e.bytes)
        r.binding.script = { _, _ -> null }
        assertEquals("ok", r.ask(h.handle).text)
        assertEquals(0, r.log.count(EngineEvents.DIED))
    }

    @Test fun anEngineFailureIsPassedThroughUntouched() {
        val r = Rig()
        r.binding.script = { _, op -> if (op is EngineOp.Open) throw IllegalStateException("exceeds maximum") else null }
        assertEquals("exceeds maximum", expect<IllegalStateException> { r.open() }.message)
        assertEquals(1, r.binding.binds.size)
    }

    // --- N30, N33, N34: how a host ends

    @Test fun haltCodesAndFreezerAreBenignNotDeathsAndNeverCounted() {
        for ((reason, status, why) in listOf(
            Triple(ApplicationExitInfo.REASON_EXIT_SELF, 10, "client_died"), Triple(ApplicationExitInfo.REASON_EXIT_SELF, 11, "unbind"),
            Triple(ApplicationExitInfo.REASON_EXIT_SELF, 12, "shutdown"), Triple(ApplicationExitInfo.REASON_FREEZER, 0, "freezer"),
        )) {
            val r = Rig()
            r.open()
            r.binding.die(1, reason, status)
            assertEquals(0, r.log.count(EngineEvents.DIED))
            val exit = r.log.all(EngineEvents.EXIT).single()
            assertEquals(why, exit.f["why"])
            assertEquals(1, r.settler.unloaded.size) // benign: unloaded, never diedWhileLoaded
            assertTrue(r.settler.died.isEmpty())
            assertEquals(0, r.conn.view.deaths)
        }
    }

    @Test fun foregroundForAHostDeathComesFromMainsImportance() {
        val r = Rig()
        r.importance = 125 // IMPORTANCE_FOREGROUND_SERVICE
        r.open()
        val h = r.open()
        r.binding.script = { p, op -> if (op is EngineOp.Ask) { r.binding.die(p, ApplicationExitInfo.REASON_LOW_MEMORY, 0); throw BindingFailure(BindingFailure.Kind.DEATH) } else null }
        val d = expect<EngineDied> { r.ask(h.handle) }
        assertTrue(d.exit.foreground)
        val line = r.log.all(EngineEvents.DIED).single()
        assertEquals(125, line.f["client_importance"])
        assertEquals(100, line.f["host_importance"])
        val r2 = Rig()
        r2.importance = 400
        val h2 = r2.open()
        r2.binding.script = { p, op -> if (op is EngineOp.Ask) { r2.binding.die(p, ApplicationExitInfo.REASON_LOW_MEMORY, 0); throw BindingFailure(BindingFailure.Kind.DEATH) } else null }
        assertFalse(expect<EngineDied> { r2.ask(h2.handle) }.exit.foreground)
    }

    // --- N31: the handle registry

    @Test fun aDeathSettlesEveryRemainingHandleOnceBeforeAnyCallerIsReleased() {
        val order = CopyOnWriteArrayList<String>()
        val r = Rig(events = order)
        val a = r.open("GPU", "m1")
        val b = r.open("GPU", "m2")
        val c = r.open("GPU", "m3")
        r.conn.call(EngineOp.Close(r.tag("close"), c.handle)) // closed: not settled again
        r.binding.script = { p, op -> if (op is EngineOp.Ask) { r.binding.die(p, ApplicationExitInfo.REASON_CRASH_NATIVE, 11); throw BindingFailure(BindingFailure.Kind.DEATH) } else null }
        try { r.ask(a.handle) } catch (e: EngineDied) { order += "released" }
        assertEquals(setOf("g-m1", "g-m2"), r.settler.died.map { it.first }.toSet())
        assertEquals(2, r.settler.died.size)
        assertTrue(r.settler.unloaded.isEmpty())
        assertTrue(order.indexOf("settle.died") < order.indexOf("released"))
        // after the end, nothing is settled twice
        r.binding.listener.binderDied(1)
        assertEquals(2, r.settler.died.size)
        assertEquals(b.handle, 2)
    }

    @Test fun aCloseThatWasSubmittedIsNotSettledByALaterDeath() {
        val r = Rig()
        val h = r.open("GPU")
        r.conn.closeAsync(EngineOp.Close(r.tag("close"), h.handle))
        r.binding.die(1, ApplicationExitInfo.REASON_CRASH_NATIVE, 11)
        assertTrue(r.settler.died.isEmpty())
        assertTrue(r.settler.unloaded.isEmpty())
    }

    // --- idle stop: N09, N10, I01, I03, N29

    @Test fun anIdleStopSettlesBenignlyAndTheNextUseRebindsWithoutAStrike() {
        val r = Rig()
        val h = r.open("GPU")
        r.time.advance(r.tuning.idleMs - 1)
        assertEquals(0, r.log.count(EngineEvents.IDLE_STOP))
        r.time.advance(1)
        assertEquals(1, r.log.count(EngineEvents.IDLE_STOP))
        assertEquals(listOf(1 to "idle"), r.binding.shutdowns)
        assertEquals(1, r.settler.unloaded.size)
        assertTrue(r.settler.died.isEmpty())
        assertEquals(0, r.log.count(EngineEvents.DIED))
        val stale = expect<EngineDied> { r.ask(h.handle) } // the handle belongs to a host that ended on purpose
        assertEquals(ExitKind.BENIGN, stale.exit.kind)
        assertEquals("ok", r.ask(r.open("GPU").handle).text)
        assertEquals(listOf(1, 2), r.binding.binds)
        assertEquals(0, r.conn.view.deaths)
    }

    @Test fun theIdleTimerUsesTheMonotonicClockAndEveryCallRestartsIt() {
        val r = Rig()
        val h = r.open()
        r.time.wallOffset += 3_600_000 // a wall-clock jump has no effect
        r.time.advance(r.tuning.idleMs - 10)
        assertEquals(0, r.log.count(EngineEvents.IDLE_STOP))
        r.ask(h.handle)
        r.time.advance(r.tuning.idleMs - 10)
        assertEquals(0, r.log.count(EngineEvents.IDLE_STOP))
        r.time.advance(10)
        assertEquals(1, r.log.count(EngineEvents.IDLE_STOP))
    }

    @Test fun aBusyHostIsNeverIdleStopped() {
        val r = Rig(autoAdvance = false)
        val h = r.open()
        r.binding.script = { p, op -> if (op is EngineOp.Ask) r.binding.block(p) else null }
        val f = async { r.ask(h.handle) }
        r.binding.inCall.await(10, TimeUnit.SECONDS)
        r.time.advance(r.tuning.idleMs * 2 - 1) // below the watchdog's limit, far past the idle deadline
        assertEquals(0, r.log.count(EngineEvents.IDLE_STOP))
        r.binding.die(1, ApplicationExitInfo.REASON_CRASH, 1)
        f.failure()
    }

    @Test fun aRequestAfterTheIdleStopWaitsForTheOldProcessAndSucceedsOnTheNextGeneration() {
        val r = Rig()
        r.binding.onShutdown = { false } // the old process lingers
        r.open()
        r.time.advance(r.tuning.idleMs)
        assertTrue(r.binding.pidAlive(1001))
        val t0 = r.time.now
        // the old process exits 700 ms later
        r.time.schedule(700) { r.binding.die(1, ApplicationExitInfo.REASON_EXIT_SELF, 12) }
        val h = r.open()
        assertEquals(2, r.binding.binds.last())
        assertTrue(r.binding.bindAt.getValue(2) - t0 in 700..800)
        assertTrue(r.binding.killed.isEmpty())
        assertEquals("ok", r.ask(h.handle).text)
        assertEquals(0, r.log.count(EngineEvents.DIED))
    }

    @Test fun aBindAfterAStopKillsAnOldProcessThatNeverLeaves() {
        val r = Rig()
        r.binding.onShutdown = { false }
        r.open()
        r.time.advance(r.tuning.idleMs)
        val t0 = r.time.now
        r.open()
        val kill = r.log.all(EngineEvents.KILL).single()
        assertEquals("stopping_wait", kill.f["why"])
        assertEquals(listOf(1001), r.binding.killed)
        assertEquals(r.tuning.liveShutdownMs, r.binding.bindAt.getValue(2) - t0)
        assertEquals(0, r.log.count(EngineEvents.DIED)) // our own stop, not a death
    }

    @Test fun aCallThatMeetsTheBenignEndOfAHostGetsOneTransparentRetry() {
        val r = Rig()
        var first = true
        r.binding.script = { p, op ->
            if (op is EngineOp.Open && first) {
                first = false
                r.binding.die(p, ApplicationExitInfo.REASON_EXIT_SELF, 11)
                throw BindingFailure(BindingFailure.Kind.DEATH)
            }
            null
        }
        assertEquals(1, r.open().handle)
        assertEquals("benign_exit", r.log.all(EngineEvents.RETRY).single().f["why"])
        assertEquals(listOf(1, 2), r.binding.binds)
        // not twice
        val r2 = Rig()
        r2.binding.script = { p, op -> if (op is EngineOp.Open) { r2.binding.die(p, ApplicationExitInfo.REASON_EXIT_SELF, 11); throw BindingFailure(BindingFailure.Kind.DEATH) } else null }
        assertEquals(ExitKind.BENIGN, expect<EngineDied> { r2.open() }.exit.kind)
        assertEquals(1, r2.log.count(EngineEvents.RETRY))
    }

    // --- N11, N28: :live before :batch

    private class Pair2(val live: Rig, val batch: Rig, val events: MutableList<String>)

    private fun livePlusBatch(liveIgnoresShutdown: Boolean = false, autoAdvance: Boolean = true): Pair2 {
        val events = CopyOnWriteArrayList<String>()
        val time = ManualTime()
        val log = ListLog()
        val live = Rig(EngineRole.LIVE, processName = "pkg:live", pidBase = 2000, events = events, tag = "live", time = time, log = log, autoAdvance = autoAdvance)
        // the batch connection's beforeOpen is what Engines.stopLive does to :live
        val batch = Rig(
            EngineRole.BATCH, processName = "pkg:batch", pidBase = 3000, events = events, tag = "batch", time = time, log = log, autoAdvance = autoAdvance,
            beforeOpen = { live.conn.shutdownAndAwait("batch_starting") },
        )
        if (liveIgnoresShutdown) live.binding.onShutdown = { false }
        return Pair2(live, batch, events)
    }

    @Test fun aLiveOpenInFlightIsNotRetriedIntoAFreshLiveWhileBatchLoads() {
        val p = livePlusBatch(autoAdvance = false)
        p.live.binding.script = { proc, op -> if (op is EngineOp.Open) p.live.binding.block(proc) else null }
        val liveOpen = async { p.live.open() }
        p.live.binding.inCall.await(10, TimeUnit.SECONDS)
        val batchOpen = async { p.batch.open() }
        assertEquals(1, batchOpen.result().handle)
        val e = liveOpen.failure() as EngineDied
        assertEquals(ExitKind.BENIGN, e.exit.kind)
        assertEquals(listOf(1), p.live.binding.binds) // the shutdown we asked for is not undone by the transparent retry
        assertEquals(0, p.live.log.count(EngineEvents.RETRY))
    }

    @Test fun batchOpenStopsLiveFirstEvenWhenBatchWasAlreadyBoundAndIdle() {
        val p = livePlusBatch()
        p.batch.open() // :batch bound, then idle ... while the user opens search:
        p.live.open()
        p.events.clear()
        p.batch.open()
        val i = p.events
        assertTrue(i.indexOf("live.shutdown") in 0 until i.indexOf("batch.open"))
        assertEquals(1, p.live.log.count(EngineEvents.LIVE_SHUTDOWN_REQUESTED))
        val done = p.live.log.all(EngineEvents.LIVE_SHUTDOWN_DONE).single()
        assertEquals(false, done.f["forced"])
        assertFalse(p.live.binding.pidAlive(2001))
    }

    @Test fun withLiveNotBoundTheCheckIsANoOp() {
        val p = livePlusBatch()
        p.batch.open()
        assertEquals(0, p.live.log.count(EngineEvents.LIVE_SHUTDOWN_REQUESTED))
        assertTrue(p.live.binding.shutdowns.isEmpty())
        assertEquals(1, p.batch.binding.calls.count { it.second is EngineOp.Open })
    }

    @Test fun aLiveThatIgnoresTheShutdownIsKilledThenBatchOpens() {
        val p = livePlusBatch(liveIgnoresShutdown = true)
        p.live.open()
        p.events.clear()
        p.batch.open()
        val kill = p.live.log.all(EngineEvents.KILL).single()
        assertEquals("shutdown_timeout", kill.f["why"])
        assertEquals(true, p.live.log.all(EngineEvents.LIVE_SHUTDOWN_DONE).single().f["forced"])
        assertTrue(p.events.indexOf("batch.open") > p.events.indexOf("live.shutdown"))
        assertFalse(p.live.binding.pidAlive(2001))
    }

    @Test fun onlyBatchOpensRunTheLiveShutdown() {
        var n = 0
        val r = Rig(EngineRole.BATCH, beforeOpen = { n++ })
        val h = r.open()
        r.ask(h.handle)
        r.embed(h.handle)
        assertEquals(1, n)
        val live = Rig(EngineRole.LIVE, beforeOpen = { n += 10 })
        live.open()
        assertEquals(1, n)
    }

    // --- N12, N35, D09: the watchdog

    private val fast = EngineTuning(askMinTimeoutMs = 1000, openTimeoutMs = 1000, embedTimeoutMs = 1000, hangNoProgressMs = 400, closeTimeoutMs = 500, idleMs = 10_000_000)

    private fun runUntil(r: Rig, f: Future<*>, stepMs: Long = 50, max: Int = 100_000) {
        var i = 0
        while (!f.isDone && i++ < max) {
            r.time.advance(stepMs)
            Thread.yield()
        }
        assertTrue("the call was never released", f.isDone)
    }

    @Test fun aSilentCallIsKilledAfterItsLimitAsAnAnrHangAndTheKillIsIdempotent() {
        val r = Rig(tuning = fast, autoAdvance = false)
        val h = r.open()
        r.binding.script = { p, op -> if (op is EngineOp.Ask) r.binding.block(p) else null }
        val f = async { r.ask(h.handle) }
        r.binding.inCall.await(10, TimeUnit.SECONDS)
        runUntil(r, f)
        val d = f.failure() as EngineDied
        assertEquals(ExitKind.ANR, d.exit.kind) // overrides the SIGNALED 9 record
        assertEquals("hang: ask exceeded 1000 ms", d.exit.description)
        assertEquals(1, r.log.count(EngineEvents.KILL))
        assertEquals("hang", r.log.all(EngineEvents.KILL).single().f["why"])
        val t = r.log.all(EngineEvents.CALL_TIMEOUT).single()
        assertEquals(1000L, t.f["limit_ms"])
        assertEquals(false, t.f["hard_cap"])
        assertEquals(true, r.log.all(EngineEvents.DIED).single().f["hang"])
        assertEquals(listOf(1001), r.binding.killed)
        assertEquals(1, r.settler.died.size)
        assertTrue("killed no earlier than the limit", r.time.now >= 1000)
    }

    @Test fun aSlowCallThatKeepsUsingCpuSurvivesUntilTheHardCap() {
        val r = Rig(tuning = fast, autoAdvance = false)
        val h = r.open()
        r.binding.script = { p, op -> if (op is EngineOp.Ask) r.binding.block(p) else null }
        val f = async { r.ask(h.handle) }
        r.binding.inCall.await(10, TimeUnit.SECONDS)
        var cpu = 0L
        var killedAt = -1L
        while (!f.isDone && r.time.now < 10_000) {
            cpu += 5; r.cpu = cpu // the host burns CPU all the time
            r.time.advance(50)
            Thread.yield()
            if (killedAt < 0 && r.binding.killed.isNotEmpty()) killedAt = r.time.now
        }
        f.failure()
        assertTrue("killed at $killedAt", killedAt > 3000 && killedAt <= 3200)
        assertEquals(true, r.log.all(EngineEvents.CALL_TIMEOUT).single().f["hard_cap"])
    }

    @Test fun theAskLimitScalesWithMaxOutputTokensAndTheObservedDecodeSpeed() {
        val r = Rig(tuning = fast, autoAdvance = false)
        r.decodeTps = 10.0
        val h = r.open()
        r.binding.script = { p, op -> if (op is EngineOp.Ask) r.binding.block(p) else null }
        val f = async { r.ask(h.handle, tokens = 10_000) } // 10000 / (10 * 0.25) = 4000 s
        r.binding.inCall.await(10, TimeUnit.SECONDS)
        r.time.advance(100_000) // far past askMinTimeoutMs, silent CPU
        assertTrue(r.binding.killed.isEmpty())
        r.time.advance(4_000_000 - 100_000 + 1000)
        f.failure()
        assertEquals(4_000_000L, r.log.all(EngineEvents.CALL_TIMEOUT).single().f["limit_ms"])
        // an unknown speed uses 0.5 tokens per second
    }

    // N26 hung close: closeAsync returns at once, one engine_kill close_timeout, BENIGN, no strike

    @Test fun aHungCloseReturnsAtOnceAndIsKilledAtTheCloseLimitWithoutAStrike() {
        val closer = Executors.newSingleThreadExecutor { r -> Thread(r, "test-close").apply { isDaemon = true } }
        val r = Rig(tuning = fast, autoAdvance = false, closeExecutor = closer)
        val h = r.open("GPU")
        r.binding.script = { p, op -> if (op is EngineOp.Close) r.binding.block(p) else null }
        val t0 = System.nanoTime()
        r.conn.closeAsync(EngineOp.Close(r.tag("close"), h.handle))
        assertTrue("closeAsync must not wait", System.nanoTime() - t0 < 2_000_000_000L)
        r.binding.inCall.await(10, TimeUnit.SECONDS)
        var i = 0
        while (r.binding.killed.isEmpty() && i++ < 10_000) { r.time.advance(50); Thread.yield() }
        assertEquals("close_timeout", r.log.all(EngineEvents.KILL).single().f["why"])
        var j = 0
        while (r.log.count(EngineEvents.EXIT) + r.log.count(EngineEvents.DIED) == 0 && j++ < 10_000) { r.time.advance(50); Thread.yield() }
        assertEquals(0, r.conn.view.deaths)
        assertTrue(r.settler.died.isEmpty())
        // the close never got its ack: the registry settled the entry itself, as a benign end (the proxy does not, see MemoryLlm.close)
        assertEquals(1, r.settler.unloaded.size)
    }

    // --- N25: threads, N14, view

    @Test fun neverTwoLiveProcessesForOneRoleAcrossDeathsAndRestarts() {
        val r = Rig()
        crashOnOpenGpu(r)
        repeat(3) {
            expect<EngineDied> { r.open("GPU") }
            r.open("CPU")
            r.binding.die(r.binding.binds.last(), ApplicationExitInfo.REASON_CRASH, 1)
        }
        assertTrue(r.binding.maxAlive <= 1)
    }

    @Test fun theViewFollowsTheStateAndTheLoadedHandles() {
        val r = Rig()
        assertEquals(HostState.UNBOUND, r.conn.view.state)
        r.open("GPU")
        val v = r.conn.view
        assertEquals(HostState.BOUND, v.state)
        assertEquals(1001, v.pid)
        assertEquals(listOf("g-m@GPU"), v.loaded)
        r.binding.die(1, ApplicationExitInfo.REASON_CRASH_NATIVE, 11)
        val d = r.conn.view
        assertEquals(HostState.DEAD, d.state)
        assertEquals(1, d.deaths)
        assertEquals(ExitKind.NATIVE_CRASH, d.lastExit?.kind)
        assertTrue(d.loaded.isEmpty())
        assertTrue(r.views.isNotEmpty())
    }

    @Test fun callIfBoundNeverBindsAndIsNotActivity() {
        val r = Rig()
        assertNull(r.conn.statusIfBound())
        assertTrue(r.binding.binds.isEmpty())
        r.open()
        r.time.advance(r.tuning.idleMs - 100)
        assertNotNull(r.conn.statusIfBound())
        r.time.advance(100)
        assertEquals(1, r.log.count(EngineEvents.IDLE_STOP)) // the poll did not keep the host alive
    }

    @Test fun aReleaseDoesNotBlockAndEndsTheHostBenignly() {
        val r = Rig()
        r.open("GPU")
        r.conn.release()
        assertEquals(listOf(1 to "release"), r.binding.shutdowns)
        assertEquals(1, r.settler.unloaded.size)
        r.conn.release() // nothing bound any more
        assertEquals(1, r.binding.shutdowns.size)
    }

    @Test fun aCloseOfAHandleFromAnEndedGenerationNeedsNoCall() {
        val r = Rig()
        val h = r.open()
        r.binding.die(1, ApplicationExitInfo.REASON_CRASH, 1)
        val before = r.binding.calls.size
        assertEquals(EngineReply.Done, r.conn.call(EngineOp.Close(r.tag("close"), h.handle)))
        r.conn.closeAsync(EngineOp.Close(r.tag("close"), h.handle))
        assertEquals(before, r.binding.calls.size)
        assertFalse(r.binding.binds.size > 1) // a close never starts a host
    }

    // --- N10: idle stop against a request, the host's exit and the death callback, in every order

    @Test fun everyOrderOfIdleTimerRequestHostExitAndDeathCallbackLosesNoRequestAndStrikesNothing() {
        val steps = listOf("timer", "request", "exit", "callback")
        fun perms(l: List<String>): List<List<String>> =
            if (l.size <= 1) listOf(l) else l.flatMap { x -> perms(l - x).map { listOf(x) + it } }
        var ran = 0
        for (order in perms(steps)) {
            if (order.indexOf("exit") > order.indexOf("callback")) continue // a death callback follows the exit it reports
            ran++
            val r = Rig()
            r.binding.onShutdown = { false } // the host leaves only when the script says so, or when it is killed
            r.open()
            var request: EngineReply.Opened? = null
            for (s in order) when (s) {
                "timer" -> r.time.advance(r.tuning.idleMs)
                "request" -> request = r.open()
                "exit" -> r.binding.die(1, ApplicationExitInfo.REASON_EXIT_SELF, HostExit.SHUTDOWN.code, callbacks = emptyList())
                "callback" -> r.binding.listener.binderDied(1)
            }
            val ctx = "order $order"
            assertNotNull(ctx, request)
            assertTrue(ctx, r.settler.died.isEmpty())
            assertEquals(ctx, 0, r.conn.view.deaths)
            assertTrue(ctx, r.log.all(EngineEvents.DIED).all { it.f["exit_kind"] == "benign" })
            assertTrue(ctx, r.binding.maxAlive <= 1 || r.binding.procs.values.count { it.alive } <= 1)
        }
        assertEquals(12, ran)
    }
    // --- a requested shutdown is an exit, not a death

    @Test fun aCloseInFlightWhenWeShutTheHostDownIsAnExpectedExitAndNoStrike() {
        val r = Rig()
        val h = r.open()
        r.binding.script = { p, op -> if (op is EngineOp.Close) r.binding.block(p) else null }
        val f = async { r.conn.call(EngineOp.Close(r.tag("close", "L"), h.handle)) }
        r.binding.inCall.await(10, TimeUnit.SECONDS)
        r.conn.shutdownAndAwait("batch_starting")
        f.failure()
        assertEquals(0, r.log.count(EngineEvents.DIED))
        val exit = r.log.all(EngineEvents.EXIT).single()
        assertEquals("exit_self", exit.f["reason"])
        assertEquals("batch_starting", exit.f["why"])
        assertEquals(12, exit.f["status"])
        assertTrue(r.settler.died.isEmpty())
    }

    @Test fun aNativeCrashDuringARequestedShutdownIsStillADeathAndStruck() {
        val r = Rig()
        val h = r.open()
        r.binding.script = { p, op -> if (op is EngineOp.Close) r.binding.block(p) else null }
        r.binding.onShutdown = { p -> r.binding.die(p, ApplicationExitInfo.REASON_EXIT_SELF, HostExit.NATIVE_BASE + 11); false }
        val f = async { r.conn.call(EngineOp.Close(r.tag("close", "L"), h.handle)) }
        r.binding.inCall.await(10, TimeUnit.SECONDS)
        r.conn.shutdownAndAwait("batch_starting")
        f.failure()
        val died = r.log.all(EngineEvents.DIED).single()
        assertEquals("native_crash", died.f["exit_kind"])
        assertEquals("close", died.f["op"])
        assertEquals(0, r.log.count(EngineEvents.EXIT))
        assertEquals(ExitKind.NATIVE_CRASH, r.settler.died.single().third.kind)
    }

    @Test fun aJavaCrashDuringARequestedShutdownIsStillADeath() {
        val r = Rig()
        r.open()
        r.binding.onShutdown = { p -> r.binding.die(p, ApplicationExitInfo.REASON_EXIT_SELF, HostExit.JAVA_CRASH.code); false }
        r.conn.shutdownAndAwait("batch_starting")
        assertEquals("crash", r.log.all(EngineEvents.DIED).single().f["exit_kind"])
    }

    @Test fun anAskInFlightWhenWeShutTheHostDownStaysADeathLine() {
        val r = Rig()
        val h = r.open()
        r.binding.script = { p, op -> if (op is EngineOp.Ask) r.binding.block(p) else null }
        val f = async { r.ask(h.handle) }
        r.binding.inCall.await(10, TimeUnit.SECONDS)
        r.conn.shutdownAndAwait("batch_starting")
        f.failure()
        assertEquals("ask", r.log.all(EngineEvents.DIED).single().f["op"])
    }
}
