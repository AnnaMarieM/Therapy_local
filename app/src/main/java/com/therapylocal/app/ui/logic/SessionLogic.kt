package com.therapylocal.app.ui.logic

import com.therapylocal.app.R
import com.therapylocal.app.data.Job
import com.therapylocal.app.jobs.JobGraph
import com.therapylocal.app.jobs.JobPhase
import com.therapylocal.app.jobs.QueueSnapshot
import com.therapylocal.app.jobs.SessionProgress
import com.therapylocal.app.jobs.StepProgress
import com.therapylocal.app.ui.models.ProcessingSessionInfo

/** Pure session/UI rules pulled out of composables so they can be unit tested. Text goes through [UiText]. */
object SessionLogic {
    /** Digits are always ASCII (padStart, never locale-formatted). */
    private fun two(n: Long) = n.toString().padStart(2, '0')

    /** "mm:ss", minutes not capped (4500 s -> "75:00"). Negative input counts as 0. */
    fun formatMinSec(secs: Long): String {
        val s = secs.coerceAtLeast(0)
        return "${two(s / 60)}:${two(s % 60)}"
    }

    /** "hh:mm:ss", hours not capped. Negative input counts as 0. */
    fun formatHourMinSec(secs: Long): String {
        val s = secs.coerceAtLeast(0)
        return "${two(s / 3600)}:${two((s / 60) % 60)}:${two(s % 60)}"
    }

    /** What ending a session of [durationMs] leads to: too short to be one is thrown away, a short one is asked about, the rest saved. */
    enum class EndOutcome { Discard, Ask, Save }

    /** Under this a session is a slip of the finger, not a session. */
    const val DISCARD_UNDER_MS = 10_000L

    /** Under this a session is short enough to ask whether it was meant. */
    const val ASK_UNDER_MS = 5 * 60_000L

    fun endOutcome(durationMs: Long): EndOutcome = when {
        durationMs < DISCARD_UNDER_MS -> EndOutcome.Discard
        durationMs < ASK_UNDER_MS -> EndOutcome.Ask
        else -> EndOutcome.Save
    }

    /**
     * Per-client "processing" banner info: sessions with work still to do and nothing failed (a later session of the same client replaces an
     * earlier one), plus the not-yet-enqueued session ([pending] = client, sessionId, date) if that client has none.
     */
    fun processingByClient(t: UiText, queue: QueueSnapshot, pending: Triple<String, String, String>?, evidence: Evidence = Evidence()): Map<String, ProcessingSessionInfo> {
        val map = mutableMapOf<String, ProcessingSessionInfo>()
        val open = queue.sessions.filter { !it.finished && it.failed == 0 }
        val runningNow = open.count { it.running }
        var waiting = 0
        open.forEach { s ->
            // Its place in line (the one being processed counts as ahead): evidence that it is queued, not stuck.
            val ahead = if (s.running || s.blocked > 0) 0 else runningNow + waiting++
            val stageText = when {
                s.running -> runningStage(t, queue.running, queue.progress)
                s.blocked > 0 -> t.str(R.string.sessionlogic_stage_waiting)
                ahead == 0 -> t.str(R.string.sessionlogic_stage_queued_next)
                else -> t.str(R.string.sessionlogic_stage_queued_ahead, ahead)
            }
            val details = evidenceLines(t, s, ahead, queue.progress, evidence)
            map[s.clientId] = ProcessingSessionInfo(
                sessionId = s.sessionId,
                date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(s.startedAt)),
                stage = stageText,
                isPendingEnqueue = false,
                details = details,
            )
        }
        pending?.let { (client, sid, date) ->
            if (!map.containsKey(client)) {
                map[client] = ProcessingSessionInfo(
                    sessionId = sid,
                    date = date.ifEmpty { t.str(R.string.mindvault_today) },
                    stage = t.str(R.string.sessionlogic_stage_finalizing),
                    isPendingEnqueue = true
                )
            }
        }
        return map
    }

    /**
     * The evidence for one session, each line only what was measured: where it is, how fast the model is going, how much is left to read,
     * and how long it should take, padded. Where nothing has been measured yet, it says what will be shown and when.
     */
    fun evidenceLines(t: UiText, s: SessionProgress, ahead: Int, p: StepProgress?, e: Evidence): List<String> {
        val stepNo = (s.done + 1).coerceAtMost(s.total)
        val step = t.str(R.string.progress_step, stepNo, s.total)
        val audioMin = e.audioMs[s.sessionId]?.takeIf { it > 0 }?.let { it / 60_000.0 }
        val whole = e.perMinute?.let { m -> audioMin?.let { (it * m.processingMs * Progress.PADDING).toLong() } }
        val noHistory = t.str(R.string.sessionlogic_no_history, com.therapylocal.app.perf.PerfStats.MIN_SESSIONS, e.measured)
        if (!s.running) {
            val first = when {
                s.blocked > 0 -> t.str(R.string.sessionlogic_waits_retry, step)
                ahead == 0 -> t.str(R.string.sessionlogic_starts_next, step)
                else -> t.plural(R.plurals.sessionlogic_starts_after, ahead, step, ahead)
            }
            val second = when {
                whole != null -> t.str(R.string.sessionlogic_interview_time, kotlin.math.ceil(audioMin!!).toInt(), Progress.span(t, whole))
                e.perMinute == null -> noHistory
                else -> null
            }
            return listOfNotNull(first, second)
        }
        val phase = p?.phase ?: JobPhase.None
        val steps = p?.steps ?: 0
        val done = p?.step ?: 0
        // While reading, time left comes from how long a passage has taken on this phone; otherwise from this step's own pace.
        val readingLeft = e.perPassage?.takeIf { phase == JobPhase.Reading && steps > 0 }?.let { (ms, _) -> ((steps - done).coerceAtLeast(0) * ms * Progress.PADDING).toLong() }
        val lines = mutableListOf(Progress.detail(t, s.done + 1, s.total, phase, done, steps, readingLeft ?: e.stepLeftMs))
        e.live?.let { lines += t.str(R.string.sessionlogic_live_speed, Progress.count(t, it.prefillTokS), Progress.count(t, it.decodeTokS)) }
        e.perPassage?.takeIf { phase == JobPhase.Reading && steps > done }?.let { (_, tokens) ->
            lines += t.str(R.string.sessionlogic_tokens_left, Progress.count(t, (steps - done) * tokens.toDouble()))
        }
        lines += when {
            e.perMinute != null -> t.str(R.string.sessionlogic_usually, Progress.count(t, e.perMinute.promptTokens), Progress.count(t, e.perMinute.decodeTokens))
            else -> noHistory
        }
        return lines
    }

    private fun runningStage(t: UiText, job: Job?, p: StepProgress?): String = when (p?.phase) {
        JobPhase.Transcribing -> t.str(R.string.sessionlogic_stage_transcribing)
        JobPhase.Reading -> t.str(R.string.sessionlogic_stage_reading, p.step, p.steps)
        JobPhase.Checking -> t.str(R.string.sessionlogic_stage_checking, p.step, p.steps)
        else -> if (job?.kind == JobGraph.TRANSCRIBE) t.str(R.string.sessionlogic_stage_transcribing) else t.str(R.string.sessionlogic_stage_extracting)
    }
}
