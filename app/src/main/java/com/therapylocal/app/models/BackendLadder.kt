package com.therapylocal.app.models

import java.util.Collections
import java.util.IdentityHashMap
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Loads a model on the best backend this phone can run, trying [candidates] in order (NPU, GPU, CPU). Each attempt is noted in the [guard]
 * before it starts and marked proven once [open] (which must also run the test inference) returns; whatever [open] built is closed by
 * [open] itself or, if it returned and a later step threw, by [close]. Nothing a backend throws escapes: the next one is tried.
 *
 * A candidate is skipped without being tried when the guard has blocked it on this device or demoted it in this run ([respectBlocks]), or
 * [gate] gives a reason (not enough free memory, a missing runtime library). Only when every candidate failed or was skipped does [load]
 * throw, with every reason. An attempt that takes long logs `backend_open_slow` every [heartbeatMs], so a hang never looks like silence.
 *
 * **Logging.** Devices differ most here and the field logs are all there is, so every step is logged, all lines of one load carrying the same
 * `load_id`: `ladder_start` (candidates, the whole guard history of the model, the memory picture), then per candidate `backend_skipped*`,
 * `backend_attempting` (memory before, the record before), and `backend_proven` or `backend_failed` (duration, memory after, the record after,
 * the error with its cause chain and stack trace), then `ladder_done` or `ladder_failed` with a one-line summary of every candidate.
 */
class BackendLadder(
    private val guard: BackendGuard?,
    private val log: Log = Log.NONE,
    /** The full memory picture (PSS, graphics…), written on the outcome lines: it can take a while to collect with a large model mapped. */
    private val memory: () -> Array<Pair<String, Any?>> = { emptyArray() },
    /** A cheap memory picture (available, low), written on the frequent lines: start, skips, attempts, heartbeats. */
    private val memoryLight: () -> Array<Pair<String, Any?>> = memory,
    private val clock: () -> Long = System::nanoTime,
    private val heartbeatMs: Long = 30_000,
) {
    /** Where the lines go. [t] is written with its stack trace. */
    fun interface Log {
        fun write(level: Level, evt: String, fields: Array<Pair<String, Any?>>, t: Throwable?)

        companion object {
            val NONE = Log { _, _, _, _ -> }
        }
    }

    enum class Level { DEBUG, INFO, WARN, ERROR }

    class Loaded<T>(val backend: String, val engine: T, val skipped: List<String>, val loadId: String)

    class NoBackend(val model: String, val reasons: List<String>) : IllegalStateException("No backend could load $model. " + reasons.joinToString("; "))

    fun <T : Any> load(
        model: String,
        candidates: List<String>,
        gate: (backend: String) -> String? = { null },
        respectBlocks: Boolean = true,
        open: (backend: String) -> T,
        close: (T) -> Unit,
    ): Loaded<T> {
        val loadId = UUID.randomUUID().toString().take(8)
        val order = candidates.map { it.trim().uppercase() }.filter { it.isNotEmpty() }.distinct()
        val t0 = clock()
        val reasons = ArrayList<String>()
        if (order.isEmpty()) reasons += "no backend to try (no candidates)"
        val summary = ArrayList<String>()
        fun base(backend: String?) = arrayOf<Pair<String, Any?>>("load_id" to loadId, "model" to model, "backend_try" to backend)
        fun recordFields(prefix: String, r: BackendGuard.Record?) = arrayOf<Pair<String, Any?>>(
            "${prefix}_state" to r?.state?.name?.lowercase(), "${prefix}_strikes" to r?.strikes, "${prefix}_ever_proven" to r?.everProven,
            "${prefix}_at" to r?.at, "${prefix}_reason" to r?.reason,
        )
        val history = order.joinToString(";") { b ->
            val r = safe(base(b)) { guard?.record(model, b) }
            "$b=${r?.state?.name?.lowercase() ?: "never_tried"}/${r?.strikes ?: 0}/${if (r?.everProven == true) "proven_before" else "never_proven"}"
        }
        log.write(Level.INFO, "ladder_start", base(null) + arrayOf("candidates" to order.joinToString(","), "respect_blocks" to respectBlocks, "history" to history) + safeMemory(memoryLight), null)

        for ((index, backend) in order.withIndex()) {
            val attempt = arrayOf<Pair<String, Any?>>("attempt" to index + 1, "of" to order.size)
            if (respectBlocks && safe(base(backend)) { guard?.blocked(model, backend) } == true) {
                val r = safe(base(backend)) { guard?.record(model, backend) }
                log.write(Level.WARN, "backend_skipped_blocked", base(backend) + attempt + recordFields("record", r), null)
                reasons += "$backend: blocked on this device (${r?.reason})"
                summary += "$backend=blocked"
                continue
            }
            if (respectBlocks && safe(base(backend)) { guard?.demoted(model, backend) } == true) {
                log.write(Level.WARN, "backend_skipped_demoted", base(backend) + attempt + arrayOf("reason" to "failed on real prompts earlier in this run"), null)
                reasons += "$backend: failed on real prompts earlier in this run"
                summary += "$backend=demoted"
                continue
            }
            val skip = try {
                gate(backend)?.ifBlank { "skipped by the gate, no reason given" }
            } catch (t: Throwable) {
                log.write(Level.WARN, "backend_gate_failed", base(backend) + attempt, t)
                "gate failed: ${name(t)}: ${message(t)}"
            }
            if (skip != null) {
                safe(base(backend)) { guard?.skipped(model, backend, skip) }
                log.write(Level.INFO, "backend_skipped", base(backend) + attempt + arrayOf("reason" to skip) + safeMemory(memoryLight), null)
                reasons += "$backend: $skip"
                summary += "$backend=skipped"
                continue
            }
            val before = safe(base(backend)) { guard?.record(model, backend) }
            safe(base(backend)) { guard?.attempting(model, backend) }
            log.write(
                Level.INFO, "backend_attempting",
                base(backend) + attempt + arrayOf("note" to "attempting", "proven_before" to (before?.everProven == true)) + recordFields("before", before) + safeMemory(memoryLight),
                null,
            )
            val a0 = clock()
            var engine: T? = null
            val heartbeat = heartbeat(base(backend) + attempt, a0)
            try {
                try {
                    engine = open(backend)
                } finally {
                    heartbeat?.cancel(false)
                }
                val ms = (clock() - a0) / 1_000_000
                safe(base(backend)) { guard?.proven(model, backend) }
                safe(base(backend)) { guard?.loaded(model, backend) }
                safe(base(backend)) { guard?.selected(model, backend) }
                log.write(
                    Level.INFO, "backend_proven",
                    base(backend) + attempt + arrayOf("note" to "proven on this device", "open_ms" to ms, "first_time" to (before?.everProven != true)) +
                        recordFields("after", safe(base(backend)) { guard?.record(model, backend) }) + safeMemory(memory),
                    null,
                )
                summary += "$backend=proven(${ms}ms)"
                log.write(
                    Level.INFO, "ladder_done",
                    base(backend) + arrayOf("backend" to backend, "total_ms" to (clock() - t0) / 1_000_000, "fallbacks" to reasons.size, "summary" to summary.joinToString(";")),
                    null,
                )
                return Loaded(backend, engine, reasons, loadId)
            } catch (t: Throwable) {
                val ms = (clock() - a0) / 1_000_000
                engine?.let { e ->
                    try {
                        close(e)
                    } catch (c: Throwable) {
                        log.write(Level.WARN, "backend_close_failed", base(backend) + arrayOf("error" to c.javaClass.name, "cause_chain" to causeChain(c)), c)
                    }
                }
                val msg = message(t)?.take(MAX_MESSAGE)
                val reason = "${name(t)}: $msg"
                val after = safe(base(backend)) { guard?.failed(model, backend, reason) }
                log.write(
                    Level.ERROR, "backend_failed",
                    base(backend) + attempt + arrayOf(
                        "open_ms" to ms, "error" to t.javaClass.name, "reason" to msg, "cause_chain" to causeChain(t),
                        "is_oom" to (t is OutOfMemoryError), "next" to order.getOrNull(index + 1),
                    ) + recordFields("after", after) + safeMemory(memory),
                    t,
                )
                if (after?.state == BackendGuard.State.BLOCKED) {
                    log.write(Level.WARN, "backend_blocked", base(backend) + recordFields("record", after), null)
                }
                reasons += "$backend: ${msg ?: name(t)}"
                summary += "$backend=failed(${name(t)},${ms}ms)"
            }
        }
        val failure = NoBackend(model, reasons)
        safe(base(null)) { guard?.selectedNone(model) }
        log.write(
            Level.ERROR, "ladder_failed",
            base(null) + arrayOf("total_ms" to (clock() - t0) / 1_000_000, "summary" to summary.joinToString(";"), "reasons" to reasons.joinToString(" | ")) + safeMemory(memory),
            null,
        )
        throw failure
    }

    private fun safeMemory(source: () -> Array<Pair<String, Any?>>): Array<Pair<String, Any?>> = runCatching { source() }.getOrDefault(emptyArray())

    /** Logs `backend_open_slow` every [heartbeatMs] while an attempt runs; a driver that hangs then shows how long and with how much memory. */
    private fun heartbeat(where: Array<Pair<String, Any?>>, a0: Long) = if (heartbeatMs <= 0) null else runCatching {
        WATCHDOG.scheduleAtFixedRate({
            runCatching { log.write(Level.WARN, "backend_open_slow", where + arrayOf("elapsed_ms" to (clock() - a0) / 1_000_000) + safeMemory(memoryLight), null) }
        }, heartbeatMs, heartbeatMs, TimeUnit.MILLISECONDS)
    }.getOrNull()

    /**
     * A broken guard (storage error) must never take the load down: the device's history is a hint, the load is the job. [where] is the
     * load's `load_id`, model and backend, so the error reads as part of that load.
     */
    private inline fun <R> safe(where: Array<Pair<String, Any?>>, block: () -> R): R? = try {
        block()
    } catch (t: Throwable) {
        log.write(Level.WARN, "backend_guard_error", where + arrayOf("error" to name(t)), t)
        null
    }

    companion object {
        /** Longest error message kept on a line or in [NoBackend]: a native wrapper can put kilobytes there. */
        const val MAX_MESSAGE = 1000

        private val WATCHDOG: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "backend-watchdog").apply { isDaemon = true } }

        /**
         * "IllegalStateException: x <- RuntimeException: y", the root last; native wrappers often only say what failed in the cause. Without
         * [messages] only the types, for errors that can quote patient text (inference).
         */
        fun causeChain(t: Throwable, messages: Boolean = true): String {
            val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>()).apply { add(t) }
            return generateSequence(t) { runCatching { it.cause }.getOrNull()?.takeIf { c -> seen.add(c) } }.take(8)
                .joinToString(" <- ") { if (messages) "${name(it)}: ${message(it)?.take(200)}" else it.javaClass.name }
        }

        /** The simple class name, or the full one for an anonymous class (whose simple name is empty). */
        internal fun name(t: Throwable): String = t.javaClass.simpleName.ifEmpty { t.javaClass.name }

        /** [Throwable.message], which is code and can itself throw. */
        internal fun message(t: Throwable): String? = try {
            t.message
        } catch (e: Throwable) {
            "(message unreadable: ${name(e)})"
        }

        /** Where it was thrown, as "Class.method:line" frames: code locations only, safe to log whatever the message says. */
        fun frames(t: Throwable, n: Int = 8): String =
            t.stackTrace.take(n).joinToString(" < ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
    }
}
