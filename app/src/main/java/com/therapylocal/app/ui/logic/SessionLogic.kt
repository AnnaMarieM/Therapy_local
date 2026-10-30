package com.therapylocal.app.ui.logic

import com.therapylocal.app.data.Job
import com.therapylocal.app.jobs.JobGraph
import com.therapylocal.app.jobs.JobPhase
import com.therapylocal.app.jobs.QueueSnapshot
import com.therapylocal.app.jobs.SessionProgress
import com.therapylocal.app.jobs.StepProgress
import com.therapylocal.app.ui.models.ProcessingSessionInfo

/** Pure session/UI rules pulled out of composables so they can be unit tested. No Android imports. */
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
    fun processingByClient(queue: QueueSnapshot, pending: Triple<String, String, String>?, evidence: Evidence = Evidence()): Map<String, ProcessingSessionInfo> {
        val map = mutableMapOf<String, ProcessingSessionInfo>()
        val open = queue.sessions.filter { !it.finished && it.failed == 0 }
        val runningNow = open.count { it.running }
        var waiting = 0
        open.forEach { s ->
            // Its place in line (the one being processed counts as ahead): evidence that it is queued, not stuck.
            val ahead = if (s.running || s.blocked > 0) 0 else runningNow + waiting++
            val stageText = when {
                s.running -> runningStage(queue.running, queue.progress)
                s.blocked > 0 -> "Waiting"
                ahead == 0 -> "Queued · next"
                else -> "Queued · $ahead ahead"
            }
            val details = evidenceLines(s, ahead, queue.progress, evidence)
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
                    date = date.ifEmpty { "Today" },
                    stage = "Finalizing recording...",
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
    fun evidenceLines(s: SessionProgress, ahead: Int, p: StepProgress?, e: Evidence): List<String> {
        val step = "Step ${(s.done + 1).coerceAtMost(s.total)}/${s.total}"
        val audioMin = e.audioMs[s.sessionId]?.takeIf { it > 0 }?.let { it / 60_000.0 }
        val whole = e.perMinute?.let { m -> audioMin?.let { (it * m.processingMs * Progress.PADDING).toLong() } }
        val noHistory = "Time left: after ${com.therapylocal.app.perf.PerfStats.MIN_SESSIONS} sessions are measured (${e.measured} so far)"
        if (!s.running) {
            val first = when {
                s.blocked > 0 -> "$step · waits for a job to be retried"
                ahead == 0 -> "$step · starts next"
                else -> "$step · starts after ${if (ahead == 1) "1 session" else "$ahead sessions"}"
            }
            val second = when {
                whole != null -> "${audioMin!!.let { kotlin.math.ceil(it).toInt() }} min of interview · ${Progress.left(whole).removeSuffix(" left")} to process once started"
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
        val lines = mutableListOf(Progress.detail(s.done + 1, s.total, phase, done, steps, readingLeft ?: e.stepLeftMs))
        e.live?.let { lines += "Reading ${Progress.count(it.prefillTokS)} tokens/s · writing ${Progress.count(it.decodeTokS)} tokens/s" }
        e.perPassage?.takeIf { phase == JobPhase.Reading && steps > done }?.let { (_, tokens) ->
            lines += "About ${Progress.count((steps - done) * tokens.toDouble())} tokens left to read"
        }
        lines += when {
            e.perMinute != null -> "Usually ${Progress.count(e.perMinute.promptTokens)} tokens read and ${Progress.count(e.perMinute.decodeTokens)} written per interview minute"
            else -> noHistory
        }
        return lines
    }

    private fun runningStage(job: Job?, p: StepProgress?): String = when (p?.phase) {
        JobPhase.Transcribing -> "Transcribing audio"
        JobPhase.Reading -> "Reading ${p.step}/${p.steps}"
        JobPhase.Checking -> "Checking ${p.step}/${p.steps}"
        else -> if (job?.kind == JobGraph.TRANSCRIBE) "Transcribing audio" else "Extracting facts"
    }
}
