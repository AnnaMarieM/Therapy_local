package com.therapylocal.app.engine

import java.util.PriorityQueue
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A deterministic [EngineScheduler] for N10 and every test that has to choose the order of events. Nothing runs by itself:
 *  - [execute] queues the task (or runs it at once with `inline = true`);
 *  - [schedule] queues a timer that is due at `clock.now + delay`;
 *  - the test runs tasks with [runNext]/[drain], and timers with [fireNext]/[advance].
 *
 * A thread that waits inside the connection (the clock's `await` with `autoAdvance`) first runs one ready task, then the earliest timer
 * that is due within its wait, so single-threaded tests that call `conn.call(...)` do not dead-lock.
 *
 *     val time = ManualTime()                                  // the clock (a ManualTime is a ManualClock)
 *     val steps = StepExecutor(time)                           // takes over its tasks and timers
 *     val rig = Rig(time = time, scheduler = steps)            // see EngineConnectionKit.Rig
 *     steps.fireNext()                                         // the idle timer fires now
 *     steps.drain()                                            // then everything that it queued
 *
 * Ordering explorer, for "all interleavings of {timer fires, request, host exit, binderDied}":
 *
 *     StepExecutor.interleave(
 *         actions = listOf("timer" to { r: Rig -> r.steps.fireNext() }, "exit" to { r -> r.binding.die(1, EXIT_SELF, 12) }),
 *         fresh = { newRig() },
 *     ) { r -> assertNoLostRequest(r) }                        // runs 2! = 2 orderings, each on a fresh rig
 */
internal class StepExecutor(val clock: ManualClock = ManualClock(), private val inline: Boolean = false) : EngineScheduler {
    private class Timer(val due: Long, val seq: Long, val run: Runnable) : Cancelable {
        @Volatile var cancelled = false
        override fun cancel() { cancelled = true }
    }

    private val ready = java.util.ArrayDeque<Runnable>()
    private val timers = PriorityQueue<Timer>(compareBy<Timer>({ it.due }, { it.seq }))
    private var seq = 0L

    /** What ran, in order: `task` and `timer@<due>`. For failure messages. */
    val trace = CopyOnWriteArrayList<String>()

    init { clock.steps = this }

    override fun execute(task: Runnable) {
        if (inline) { trace += "task"; task.run() } else synchronized(this) { ready.add(task) }
    }

    override fun schedule(delayMs: Long, task: Runnable): Cancelable = synchronized(this) {
        Timer(clock.now + delayMs.coerceAtLeast(0), seq++, task).also { timers.add(it) }
    }

    /** Tasks queued and not run yet. */
    val readyCount: Int get() = synchronized(this) { ready.size }

    /** Timers scheduled, not cancelled and not run yet. */
    val timerCount: Int get() = synchronized(this) { timers.count { !it.cancelled } }

    /** Due time of the earliest live timer, or null. */
    fun nextDue(): Long? = synchronized(this) { timers.filter { !it.cancelled }.minWithOrNull(compareBy({ it.due }, { it.seq }))?.due }

    /** Runs the oldest queued task. False when there is none. */
    fun runNext(): Boolean {
        val t = synchronized(this) { ready.poll() } ?: return false
        trace += "task"
        t.run()
        return true
    }

    /** Runs queued tasks (also those they queue) until none is left; returns how many ran. Bounded, so a loop fails loud. */
    fun drain(max: Int = 10_000): Int {
        var n = 0
        while (runNext()) check(++n <= max) { "drain ran $max tasks: a task keeps queueing itself" }
        return n
    }

    /** Moves time to the earliest live timer and runs it (not its follow-up tasks; call [drain]). False when there is no timer. */
    fun fireNext(): Boolean {
        val t = synchronized(this) {
            while (timers.isNotEmpty() && timers.peek().cancelled) timers.poll()
            timers.poll()
        } ?: return false
        if (t.due > clock.now) clock.now = t.due
        trace += "timer@${t.due}"
        t.run.run()
        return true
    }

    /** Moves time by [ms]: drains tasks, then fires every timer that falls due in order (draining after each), then lands on the end time. */
    fun advance(ms: Long) {
        val end = clock.now + ms
        drain()
        while (true) {
            val due = nextDue() ?: break
            if (due > end) break
            fireNext()
            drain()
        }
        clock.now = end
    }

    /** One step of a waiting thread (called by the clock): a ready task, else the earliest timer within [max], else time passes. */
    internal fun stepWithin(max: Long) {
        if (runNext()) return
        val due = nextDue()
        if (due != null && due <= clock.now + max) fireNext() else clock.now += max
    }

    companion object {
        /** Every ordering of [items] (n! lists, in a stable order). Keep n <= 6. */
        fun <T> permutations(items: List<T>): List<List<T>> =
            if (items.size <= 1) listOf(items)
            else items.indices.flatMap { i -> permutations(items.filterIndexed { j, _ -> j != i }).map { listOf(items[i]) + it } }

        /**
         * Runs [verify] after every ordering of the named [actions], each on a state made by [fresh]. A failure names the ordering that broke.
         * Returns the number of orderings run.
         */
        fun <S> interleave(actions: List<Pair<String, (S) -> Unit>>, fresh: () -> S, verify: (S) -> Unit): Int {
            val all = permutations(actions)
            for (order in all) {
                val s = fresh()
                try {
                    order.forEach { it.second(s) }
                    verify(s)
                } catch (t: Throwable) {
                    throw AssertionError("ordering ${order.map { it.first }}: ${t.message}", t)
                }
            }
            return all.size
        }
    }
}
