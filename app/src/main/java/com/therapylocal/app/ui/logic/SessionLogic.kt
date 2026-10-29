package com.therapylocal.app.ui.logic

import com.therapylocal.app.data.Job
import com.therapylocal.app.jobs.JobGraph
import com.therapylocal.app.jobs.JobPhase
import com.therapylocal.app.jobs.QueueSnapshot
import com.therapylocal.app.jobs.StepProgress
import com.therapylocal.app.ui.models.Patient
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

    /** Patients whose name, presenting issue, facts or session summaries/topics/quotes contain [query] (trimmed, case-insensitive). Blank query keeps everyone. */
    fun filterPatients(patients: List<Patient>, query: String): List<Patient> {
        if (query.isBlank()) return patients
        val q = query.trim().lowercase()
        return patients.filter { p ->
            p.name.lowercase().contains(q) ||
            p.presenting.lowercase().contains(q) ||
            p.facts.any { it.lowercase().contains(q) } ||
            p.sessions.any { s ->
                s.summary.lowercase().contains(q) ||
                s.topics.any { t -> t.lowercase().contains(q) } ||
                s.verbatimQuotes.any { v -> v.lowercase().contains(q) }
            }
        }
    }

    /**
     * Per-client "processing" banner info: sessions with work still to do and nothing failed (a later session of the same client replaces an
     * earlier one), plus the not-yet-enqueued session ([pending] = client, sessionId, date) if that client has none.
     */
    fun processingByClient(queue: QueueSnapshot, pending: Triple<String, String, String>?): Map<String, ProcessingSessionInfo> {
        val map = mutableMapOf<String, ProcessingSessionInfo>()
        queue.sessions.filter { !it.finished && it.failed == 0 }.forEach { s ->
            val stageText = when {
                s.running -> runningStage(queue.running, queue.progress)
                s.blocked > 0 -> "Waiting"
                else -> "Queued"
            }
            map[s.clientId] = ProcessingSessionInfo(
                sessionId = s.sessionId,
                date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(s.startedAt)),
                stage = stageText,
                isPendingEnqueue = false
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

    private fun runningStage(job: Job?, p: StepProgress?): String = when (p?.phase) {
        JobPhase.Transcribing -> "Transcribing audio"
        JobPhase.Reading -> "Reading ${p.step}/${p.steps}"
        JobPhase.Checking -> "Checking ${p.step}/${p.steps}"
        else -> if (job?.kind == JobGraph.TRANSCRIBE) "Transcribing audio" else "Extracting notes"
    }

    enum class SessionTabTarget { Live, Chooser }

    /** Tapping the Session tab: go to the live screen if a session with a patient is running, else ask which patient. */
    fun sessionTabTarget(isSessionActive: Boolean, hasSessionPatient: Boolean): SessionTabTarget =
        if (isSessionActive && hasSessionPatient) SessionTabTarget.Live else SessionTabTarget.Chooser

    enum class PauseAction { StartRecording, ResumeRecording, PauseRecording }

    data class PauseToggle(val paused: Boolean, val recordingStarted: Boolean, val action: PauseAction)

    /** Play/pause button. A session starts paused and not recording; the first play starts recording, later plays resume it. */
    fun togglePause(paused: Boolean, recordingStarted: Boolean): PauseToggle = when {
        !paused -> PauseToggle(paused = true, recordingStarted = recordingStarted, action = PauseAction.PauseRecording)
        !recordingStarted -> PauseToggle(paused = false, recordingStarted = true, action = PauseAction.StartRecording)
        else -> PauseToggle(paused = false, recordingStarted = true, action = PauseAction.ResumeRecording)
    }
}
