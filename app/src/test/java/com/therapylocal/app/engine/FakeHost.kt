package com.therapylocal.app.engine

import android.app.ApplicationExitInfo
import android.os.DeadObjectException
import com.therapylocal.app.config.EmbeddingConfig
import com.therapylocal.app.perf.LlmCall
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * A pure-Kotlin [EngineHost] whose behaviour a test scripts (catalogue N, "FakeHost"). No Android classes are loaded except the
 * `DeadObjectException` stub, which `isReturnDefaultValues` allows.
 *
 * It has three layers:
 *  - the engine script: [failBackends], [onOpen], [ask], [vector], [embedGate]/[embedEntered] (what `EngineProxyTest` has always used);
 *  - generations and death: [die] fails every outstanding call and every future call of the current generation with
 *    [DeadObjectException] and fires [onDied] (which is `binderDied`); [revive] starts the next generation, with empty handles;
 *  - blocking: [hangOn] makes a matching call wait until the generation dies (a hang, N02 and N12).
 *
 * Use it alone as the `EngineHost` of an `EngineProxy`/`MemoryLlm`, or behind a [FakeBinding] (`binding.useHost(host)`) under a real
 * `EngineConnection`:
 *
 *     val host = FakeHost()
 *     host.hangOn = { it is EngineOp.Ask }
 *     val f = pool.submit { host.call(askOp) }       // blocks
 *     host.awaitInFlight(1); host.die()               // f fails with DeadObjectException, host.gen stays 1 until revive()
 *     host.revive()                                    // gen 2, handles start again at 1
 */
internal class FakeHost(override val role: EngineRole = EngineRole.BATCH) : EngineHost {
    val ops = CopyOnWriteArrayList<EngineOp>()
    val closed = CopyOnWriteArrayList<Int>()
    var nextHandle = 1
    var failBackends = mutableMapOf<String, Throwable>()
    var onOpen: (EngineOp.Open) -> Unit = {}
    var ask: (EngineOp.Ask) -> EngineReply = { EngineReply.Text("ok", LlmCall(10, 100.0, 5, 20.0, 50)) }
    var vector: (Int) -> FloatArray = { FloatArray(EmbeddingConfig.DIM) { 0.01f } }
    var embedGate: CountDownLatch? = null
    var embedEntered = CountDownLatch(0)
    var maxTokens = 512

    /** Phases an `Open` reports before it answers. The host script of the J10 parity test uses create and test. */
    var phaseNames: List<String> = listOf("create", "test")

    // ------------------------------------------------------------------------------------------------ generations and death

    /** The current generation, from 1. [revive] increments it. */
    @Volatile var gen = 1
        private set
    @Volatile var dead = false
        private set
    private var deadLatch = CountDownLatch(1)
    private val inFlightNow = AtomicInteger(0)
    private val inFlightLatch = java.util.concurrent.Semaphore(0)

    /** Number of calls that are inside [call] right now (blocked by [hangOn] or running). */
    val inFlight: Int get() = inFlightNow.get()

    /** Which calls hang until the generation dies. Checked after the engine script would have started. */
    @Volatile var hangOn: (EngineOp) -> Boolean = { false }

    /** How the process ends when a [FakeBinding] routes to this host: the exit record's reason and status. Default: a native crash, status 11. */
    @Volatile var exitReason: Int = ApplicationExitInfo.REASON_CRASH_NATIVE
    @Volatile var exitStatus: Int = 11

    /** Fired once per death (the `binderDied` of the fake). A [FakeBinding.useHost] sets it; a test may set its own. */
    @Volatile var onDied: (Int) -> Unit = {}

    /** Counts calls that were refused or raised because the host was dead, for assertions. */
    val deadCalls = AtomicInteger(0)

    /** The process dies: every call in flight and every later call of this generation throws [DeadObjectException]. Idempotent. */
    fun die(reason: Int = exitReason, status: Int = exitStatus) {
        synchronized(this) {
            if (dead) return
            dead = true
            exitReason = reason
            exitStatus = status
        }
        deadLatch.countDown()
        onDied(gen)
    }

    /** The next generation: a fresh process with no handles. Calls work again. */
    @Synchronized fun revive() {
        gen++
        dead = false
        deadLatch = CountDownLatch(1)
        nextHandle = 1
    }

    /** Blocks (bounded) until [n] calls are in flight, so a test can kill the host while its callers are inside. */
    fun awaitInFlight(n: Int, timeoutMs: Long = 5_000) {
        val end = System.nanoTime() + timeoutMs * 1_000_000
        while (inFlightNow.get() < n) {
            check(System.nanoTime() < end) { "only ${inFlightNow.get()} of $n calls in flight" }
            inFlightLatch.tryAcquire(5, TimeUnit.MILLISECONDS)
        }
    }

    override fun call(op: EngineOp, phases: PhaseSink): EngineReply {
        ops += op
        if (dead) { deadCalls.incrementAndGet(); throw DeadObjectException() }
        val latch = deadLatch
        inFlightNow.incrementAndGet()
        inFlightLatch.release()
        try {
            if (hangOn(op)) {
                check(latch.await(20, TimeUnit.SECONDS)) { "a hanging call was never released by die()" }
                deadCalls.incrementAndGet()
                throw DeadObjectException()
            }
            val reply = run(op, phases)
            if (dead) { deadCalls.incrementAndGet(); throw DeadObjectException() }
            return reply
        } finally {
            inFlightNow.decrementAndGet()
        }
    }

    private fun run(op: EngineOp, phases: PhaseSink): EngineReply = when (op) {
        is EngineOp.Open -> {
            onOpen(op)
            phaseNames.forEachIndexed { i, p ->
                phases.phase(op.tag, op.model, op.backend, p)
                // The first phase (create) is where a scripted backend failure surfaces, as in the original script.
                if (i == 0) failBackends[op.backend]?.let { throw it }
            }
            EngineReply.Opened(nextHandle++, op.backend, maxTokens, null)
        }
        is EngineOp.Ask -> ask(op)
        is EngineOp.EmbedText -> {
            embedEntered.countDown()
            embedGate?.await(5, TimeUnit.SECONDS)
            EngineReply.Vector(vector(op.handle))
        }
        is EngineOp.EmbedAudio -> EngineReply.Vector(vector(op.handle))
        is EngineOp.Transcribe -> EngineReply.Text("hello world", null)
        is EngineOp.Close -> { closed += op.handle; EngineReply.Done }
        is EngineOp.Status -> EngineReply.HostStatus(1, 0, 1234, emptyList())
        else -> EngineReply.Refused
    }

    override fun closeAsync(op: EngineOp.Close) {
        closed += op.handle
    }

    /** With [deferClose] the close is acknowledged only when the test calls [ackCloses] (a real host answers later, on its close thread). */
    @Volatile var deferClose = false
    private val deferred = CopyOnWriteArrayList<(Boolean) -> Unit>()

    /** What happened around the closes, in order: `close`, `await`, for ordering assertions. */
    val order = CopyOnWriteArrayList<String>()

    override fun closeAsync(op: EngineOp.Close, onClosed: (acked: Boolean) -> Unit) {
        closeAsync(op)
        order += "close"
        if (deferClose) deferred += onClosed else onClosed(true)
    }

    fun ackCloses(acked: Boolean = true) {
        val l = deferred.toList()
        deferred.clear()
        l.forEach { it(acked) }
    }

    override fun awaitPendingCloses() {
        order += "await"
    }
}
