package com.therapylocal.app.models

import java.util.UUID

/**
 * Loads a model on the best backend this phone can run, trying [candidates] in order (NPU, GPU, CPU). Each attempt is noted in the [guard]
 * before it starts and marked proven once [open] (which must also run the test inference) returns; whatever [open] built is closed by
 * [open] itself or, if it returned and a later step threw, by [close]. Nothing a backend throws escapes: the next one is tried.
 *
 * A candidate is skipped without being tried when the guard has blocked it on this device ([respectBlocks]) or [gate] gives a reason (not
 * enough free memory, a missing runtime library). Only when every candidate failed or was skipped does [load] throw, with every reason.
 *
 * **Logging.** Devices differ most here and the field logs are all there is, so every step is logged, all lines of one load carrying the same
 * `load_id`: `ladder_start` (candidates, the whole guard history of the model, the memory picture), then per candidate `backend_skipped*`,
 * `backend_attempting` (memory before, the record before), and `backend_proven` or `backend_failed` (duration, memory after, the record after,
 * the error with its cause chain and stack trace), then `ladder_done` or `ladder_failed` with a one-line summary of every candidate.
 */
class BackendLadder(
    private val guard: BackendGuard?,
    private val log: Log = Log.NONE,
    /** The memory picture at this moment (available, low, PSS, graphics…), written on the attempt lines. */
    private val memory: () -> Array<Pair<String, Any?>> = { emptyArray() },
    private val clock: () -> Long = System::nanoTime,
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
        val order = candidates.map { it.uppercase() }.distinct()
        val t0 = clock()
        val reasons = ArrayList<String>()
        val summary = ArrayList<String>()
        fun base(backend: String?) = arrayOf<Pair<String, Any?>>("load_id" to loadId, "model" to model, "backend_try" to backend)
        fun recordFields(prefix: String, r: BackendGuard.Record?) = arrayOf<Pair<String, Any?>>(
            "${prefix}_state" to r?.state?.name?.lowercase(), "${prefix}_strikes" to r?.strikes, "${prefix}_ever_proven" to r?.everProven,
            "${prefix}_at" to r?.at, "${prefix}_reason" to r?.reason,
        )
        val history = order.joinToString(";") { b ->
            val r = safe { guard?.record(model, b) }
            "$b=${r?.state?.name?.lowercase() ?: "never_tried"}/${r?.strikes ?: 0}/${if (r?.everProven == true) "proven_before" else "never_proven"}"
        }
        log.write(Level.INFO, "ladder_start", base(null) + arrayOf("candidates" to order.joinToString(","), "respect_blocks" to respectBlocks, "history" to history) + safeMemory(), null)

        for ((index, backend) in order.withIndex()) {
            val attempt = arrayOf<Pair<String, Any?>>("attempt" to index + 1, "of" to order.size)
            if (respectBlocks && safe { guard?.blocked(model, backend) } == true) {
                val r = safe { guard?.record(model, backend) }
                log.write(Level.WARN, "backend_skipped_blocked", base(backend) + attempt + recordFields("record", r), null)
                reasons += "$backend: blocked on this device (${r?.reason})"
                summary += "$backend=blocked"
                continue
            }
            val skip = try {
                gate(backend)
            } catch (t: Throwable) {
                log.write(Level.WARN, "backend_gate_failed", base(backend) + attempt, t)
                "gate failed: ${t.javaClass.simpleName}: ${t.message}"
            }
            if (skip != null) {
                safe { guard?.skipped(model, backend, skip) }
                log.write(Level.INFO, "backend_skipped", base(backend) + attempt + arrayOf("reason" to skip) + safeMemory(), null)
                reasons += "$backend: $skip"
                summary += "$backend=skipped"
                continue
            }
            val before = safe { guard?.record(model, backend) }
            safe { guard?.attempting(model, backend) }
            log.write(
                Level.INFO, "backend_attempting",
                base(backend) + attempt + arrayOf("note" to "attempting", "proven_before" to (before?.everProven == true)) + recordFields("before", before) + safeMemory(),
                null,
            )
            val a0 = clock()
            var engine: T? = null
            try {
                engine = open(backend)
                val ms = (clock() - a0) / 1_000_000
                safe { guard?.proven(model, backend) }
                safe { guard?.loaded(model, backend) }
                safe { guard?.selected(model, backend) }
                log.write(
                    Level.INFO, "backend_proven",
                    base(backend) + attempt + arrayOf("note" to "proven on this device", "open_ms" to ms, "first_time" to (before?.everProven != true)) +
                        recordFields("after", safe { guard?.record(model, backend) }) + safeMemory(),
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
                engine?.let { e -> safe { close(e) } }
                val reason = "${t.javaClass.simpleName}: ${t.message}"
                val after = safe { guard?.failed(model, backend, reason) }
                log.write(
                    Level.ERROR, "backend_failed",
                    base(backend) + attempt + arrayOf(
                        "open_ms" to ms, "error" to t.javaClass.name, "reason" to t.message, "cause_chain" to causeChain(t),
                        "is_oom" to (t is OutOfMemoryError), "next" to order.getOrNull(index + 1),
                    ) + recordFields("after", after) + safeMemory(),
                    t,
                )
                if (after?.state == BackendGuard.State.BLOCKED) {
                    log.write(Level.WARN, "backend_blocked", base(backend) + recordFields("record", after), null)
                }
                reasons += "$backend: ${t.message ?: t.javaClass.simpleName}"
                summary += "$backend=failed(${t.javaClass.simpleName},${ms}ms)"
            }
        }
        val failure = NoBackend(model, reasons)
        log.write(
            Level.ERROR, "ladder_failed",
            base(null) + arrayOf("total_ms" to (clock() - t0) / 1_000_000, "summary" to summary.joinToString(";"), "reasons" to reasons.joinToString(" | ")) + safeMemory(),
            null,
        )
        throw failure
    }

    private fun safeMemory(): Array<Pair<String, Any?>> = runCatching { memory() }.getOrDefault(emptyArray())

    /** A broken guard (storage error) must never take the load down: the device's history is a hint, the load is the job. */
    private inline fun <R> safe(block: () -> R): R? = try {
        block()
    } catch (t: Throwable) {
        log.write(Level.WARN, "backend_guard_error", arrayOf("error" to t.javaClass.simpleName), t)
        null
    }

    companion object {
        /**
         * "IllegalStateException: x <- RuntimeException: y", the root last; native wrappers often only say what failed in the cause. Without
         * [messages] only the types, for errors that can quote patient text (inference).
         */
        fun causeChain(t: Throwable, messages: Boolean = true): String {
            val seen = HashSet<Throwable>()
            return generateSequence(t) { it.cause?.takeIf { c -> seen.add(c) } }.take(8)
                .joinToString(" <- ") { if (messages) "${it.javaClass.simpleName}: ${it.message?.take(200)}" else it.javaClass.name }
        }

        /** Where it was thrown, as "Class.method:line" frames: code locations only, safe to log whatever the message says. */
        fun frames(t: Throwable, n: Int = 8): String =
            t.stackTrace.take(n).joinToString(" < ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
    }
}
