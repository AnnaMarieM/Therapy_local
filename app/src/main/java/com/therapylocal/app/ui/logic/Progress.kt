package com.therapylocal.app.ui.logic

import com.therapylocal.app.jobs.JobPhase
import kotlin.math.ceil

/**
 * Progress that is only ever what was measured. A status says what is happening; looked at for a while it says how far along it is
 * ("Step 2/5 · 3/12 chunks · about 2 min left"), and the time left comes from how fast this very step has gone so far, never from a guess.
 * Until there is something to measure it says so ("measuring…").
 */
object Progress {
    /** Estimates are stretched by this: finishing early is a pleasant surprise, finishing late is a broken promise. */
    const val PADDING = 1.2

    /** What one step of a phase is called, so the numbers have a unit. */
    fun unit(phase: JobPhase, n: Int): String = when (phase) {
        JobPhase.Transcribing -> if (n == 1) "chunk" else "chunks"
        JobPhase.Reading -> if (n == 1) "passage" else "passages"
        JobPhase.Checking -> if (n == 1) "fact" else "facts"
        JobPhase.None -> if (n == 1) "step" else "steps"
    }

    /** "about 40 s left", "about 3 min left", "about 1 h 10 min left"; always rounded up, so it never promises less than measured. */
    fun left(ms: Long): String {
        val s = ceil(ms.coerceAtLeast(0) / 1000.0).toLong()
        return when {
            s < 60 -> "about ${(ceil(s / 5.0) * 5).toLong().coerceAtLeast(5)} s left"
            s < 3600 -> "about ${ceil(s / 60.0).toLong()} min left"
            else -> {
                val m = ceil(s / 60.0).toLong()
                "about ${m / 60} h ${m % 60} min left"
            }
        }
    }

    /** A count to read at a glance: "14", "1,240", "12k". */
    fun count(x: Double): String {
        val n = x.coerceAtLeast(0.0)
        return when {
            n < 10 -> "%.1f".format(java.util.Locale.US, n).removeSuffix(".0")
            n < 10_000 -> "%,d".format(java.util.Locale.US, Math.round(n))
            else -> "${Math.round(n / 1000)}k"
        }
    }

    /** The detailed line: which job of the session ("Step 2/5"), how far within it, and the measured time left for it. */
    fun detail(jobIndex: Int, jobs: Int, phase: JobPhase, step: Int, steps: Int, leftMs: Long?): String = buildList {
        if (jobs > 0) add("Step ${jobIndex.coerceIn(1, jobs)}/$jobs")
        if (steps > 0) add("${step.coerceIn(0, steps)}/$steps ${unit(phase, steps)}")
        add(if (leftMs == null) "measuring…" else left(leftMs))
    }.joinToString(" · ")
}

/** What has been measured on this phone, for the evidence lines of a session being processed or waiting. */
data class Evidence(
    /** Measured time left for the running step, already padded. */
    val stepLeftMs: Long? = null,
    /** Speed of the latest model call, if recent. */
    val live: com.therapylocal.app.perf.LiveSpeed? = null,
    /** Average wall time and prompt tokens of one passage being read, on the model in use. */
    val perPassage: Pair<Long, Long>? = null,
    /** What a minute of interview takes, once enough sessions were measured. */
    val perMinute: com.therapylocal.app.perf.PerMinute? = null,
    /** Sessions measured so far. */
    val measured: Int = 0,
    /** Interview length per session id. */
    val audioMs: Map<String, Long> = emptyMap(),
)

/**
 * Measures how fast something countable goes ([done] of [total], chunks or bytes) and so how long the rest will take. Fed every time the
 * count is seen; it starts over when the thing being counted changes ([key]: another job or phase), or the count goes backwards.
 * Needs two different counts a moment apart before it says anything: one point is not a rate.
 */
class RateMeter {
    private var key: Any? = null
    private var firstAt = 0L
    private var firstDone = 0L
    private var lastAt = 0L
    private var lastDone = 0L
    private var total = 0L

    fun see(key: Any, done: Long, total: Long, now: Long) {
        if (key != this.key || done < lastDone) {
            this.key = key
            firstAt = now; firstDone = done
            lastAt = now; lastDone = done
        } else if (done != lastDone) {
            lastAt = now; lastDone = done
        }
        this.total = total
    }

    /** Measured time left, padded by [Progress.PADDING]; null until a rate has been measured. */
    fun leftMs(): Long? {
        val did = lastDone - firstDone
        if (did <= 0 || lastAt <= firstAt) return null
        val msPerUnit = (lastAt - firstAt).toDouble() / did
        val remaining = (total - lastDone).coerceAtLeast(0)
        return (remaining * msPerUnit * Progress.PADDING).toLong()
    }
}
