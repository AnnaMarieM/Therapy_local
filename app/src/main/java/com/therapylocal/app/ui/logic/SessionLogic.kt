package com.therapylocal.app.ui.logic

import com.therapylocal.app.memory.JobStatus
import com.therapylocal.app.memory.SessionJob
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
     * Per-client "processing" banner info: Queued/Running jobs (a later job of the same client replaces an earlier one),
     * plus the not-yet-enqueued session ([pending] = client, sessionId, date) if that client has no job yet.
     */
    fun processingByClient(jobs: List<SessionJob>, pending: Triple<String, String, String>?): Map<String, ProcessingSessionInfo> {
        val map = mutableMapOf<String, ProcessingSessionInfo>()
        jobs.filter { it.status == JobStatus.Queued || it.status == JobStatus.Running }.forEach { job ->
            val stageText = when {
                job.stage.isNotBlank() -> job.stage
                job.status == JobStatus.Queued -> "Queued"
                job.recordingId != 0L -> "Transcribing audio"
                else -> "Extracting notes"
            }
            map[job.client] = ProcessingSessionInfo(
                sessionId = job.id,
                date = job.date.ifEmpty { "Today" },
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
