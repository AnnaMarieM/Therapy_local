package com.therapylocal.app.memory

import com.therapylocal.app.jobs.JobPhase
import com.therapylocal.app.jobs.QueueSnapshot
import com.therapylocal.app.jobs.StepProgress

/** What the processing notification says. [progressMax] = 0 hides the bar; [indeterminate] shows a spinner-style bar. */
data class ProcessingNotice(
    val title: String,
    val text: String,
    val ongoing: Boolean,
    val progressMax: Int = 0,
    val progress: Int = 0,
    val indeterminate: Boolean = false,
) {
    companion object {
        const val TITLE_DONE = "Sessions processed"

        /**
         * Maps the queue to the notification. Only sessions of the current run ([run]) are counted, so sessions finished in earlier runs do not
         * inflate "session 1 of N". No client names are used: the text is safe to show wherever the private notification is shown.
         */
        fun running(s: QueueSnapshot, run: Set<String>): ProcessingNotice {
            val sessions = s.sessions.filter { it.sessionId in run }
            val total = sessions.size
            val done = sessions.count { it.finished }
            if (s.stopping) return ProcessingNotice("Stopping…", "Finishing the current step, then stopping", ongoing = true, indeterminate = true, progressMax = 1)

            val cur = s.running?.takeIf { it.sessionId in run }
                ?: return ProcessingNotice("Preparing to process ${plural(total - done, "session")}", "Loading the on-device models…", ongoing = true, progressMax = 1, indeterminate = true)
            val position = sessions.indexOfFirst { it.sessionId == cur.sessionId } + 1
            val of = if (total > 1) " $position of $total" else ""
            val p = s.progress ?: StepProgress(JobPhase.None, 0, 0)
            if (cur.attempts > 0 && p.phase == JobPhase.None) {
                return ProcessingNotice("Processing session$of", "Something went wrong, retrying (attempt ${cur.attempts + 1} of ${cur.maxAttempts})", ongoing = true, progressMax = 1, indeterminate = true)
            }
            val (title, detail) = when (p.phase) {
                JobPhase.Transcribing -> "Transcribing session$of" to "Converting audio to text${part(p, "part")}"
                JobPhase.Reading -> "Analysing session$of" to "Finding key points in the transcript${part(p, "section")}"
                JobPhase.Checking -> "Analysing session$of" to "Comparing new findings with the profile${part(p, "item")}"
                JobPhase.None -> "Processing session$of" to "Getting ready…"
            }
            val known = p.steps > 0
            return ProcessingNotice(
                title, detail, ongoing = true,
                progressMax = if (known) p.steps else 1, progress = if (known) p.step.coerceIn(0, p.steps) else 0, indeterminate = !known,
            )
        }

        /** The dismissible end-of-run notice, or null when there is nothing worth telling (nothing ran, or it is only waiting for setup). */
        fun finished(s: QueueSnapshot, run: Set<String>): ProcessingNotice? {
            val sessions = s.sessions.filter { it.sessionId in run }
            val total = sessions.size
            val done = sessions.count { it.finished }
            val failed = sessions.count { it.failed > 0 }
            return when {
                failed > 0 -> ProcessingNotice("${plural(failed, "session")} could not be processed", "$done of $total done · open the app to retry", ongoing = false)
                s.stopping && done < total -> ProcessingNotice("Processing stopped", "$done of $total done · open the app to resume", ongoing = false)
                done == 0 -> null
                else -> ProcessingNotice(TITLE_DONE, "${plural(done, "session")} done", ongoing = false)
            }
        }

        private fun part(p: StepProgress, noun: String) = if (p.steps > 0) " · $noun ${p.step.coerceIn(1, p.steps)} of ${p.steps}" else ""

        private fun plural(n: Int, word: String) = "$n $word${if (n == 1) "" else "s"}"
    }
}
