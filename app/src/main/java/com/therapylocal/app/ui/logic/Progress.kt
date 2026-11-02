package com.therapylocal.app.ui.logic

import com.therapylocal.app.R
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

    /** "3/12 chunks": how far within a phase, in the plural form that fits [steps]. */
    fun stepCount(t: UiText, phase: JobPhase, step: Int, steps: Int): String = t.plural(
        when (phase) {
            JobPhase.Transcribing -> R.plurals.progress_count_chunks
            JobPhase.Reading -> R.plurals.progress_count_passages
            JobPhase.Checking -> R.plurals.progress_count_facts
            JobPhase.None -> R.plurals.progress_count_steps
        },
        steps, step, steps,
    )

    /** "about 40 s", "about 3 min", "about 1 h 10 min"; always rounded up, so it never promises less than measured. */
    fun span(t: UiText, ms: Long): String {
        val s = ceil(ms.coerceAtLeast(0) / 1000.0).toLong()
        return when {
            s < 60 -> t.str(R.string.progress_about_seconds, (ceil(s / 5.0) * 5).toLong().coerceAtLeast(5))
            s < 3600 -> t.str(R.string.progress_about_minutes, ceil(s / 60.0).toLong())
            else -> {
                val m = ceil(s / 60.0).toLong()
                t.str(R.string.progress_about_hours, m / 60, m % 60)
            }
        }
    }

    /** [span] as time still to come: "about 3 min left". */
    fun left(t: UiText, ms: Long): String = t.str(R.string.progress_left, span(t, ms))

    /** A count to read at a glance: "14", "1,240", "12k". */
    fun count(t: UiText, x: Double): String {
        val n = x.coerceAtLeast(0.0)
        return when {
            n < 10 -> {
                val tenths = Math.round(n * 10)
                if (tenths % 10 == 0L) (tenths / 10).toString() else String.format(t.locale, "%.1f", tenths / 10.0)
            }
            n < 10_000 -> String.format(t.locale, "%,d", Math.round(n))
            else -> t.str(R.string.progress_count_thousands, Math.round(n / 1000))
        }
    }

    /** The detailed line: which job of the session ("Step 2/5"), how far within it, and the measured time left for it. */
    fun detail(t: UiText, jobIndex: Int, jobs: Int, phase: JobPhase, step: Int, steps: Int, leftMs: Long?): String = buildList {
        if (jobs > 0) add(t.str(R.string.progress_step, jobIndex.coerceIn(1, jobs), jobs))
        if (steps > 0) add(stepCount(t, phase, step.coerceIn(0, steps), steps))
        add(if (leftMs == null) t.str(R.string.progress_measuring) else left(t, leftMs))
    }.joinToString(" \u00b7 ")
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
