package com.therapylocal.app.memory

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
         * Maps the queue to the notification. Only jobs of the current run ([runIds]) are counted, so sessions finished in earlier runs do not
         * inflate "session 1 of N". No client names are used: the text is safe to show wherever the private notification is shown.
         */
        fun running(state: QueueState, runIds: Set<String>, stopping: Boolean): ProcessingNotice {
            val run = state.jobs.filter { it.id in runIds }
            val total = run.size
            val done = run.count { it.status == JobStatus.Done }
            if (stopping) return ProcessingNotice("Stopping…", "Finishing the current step, then stopping", ongoing = true, indeterminate = true, progressMax = 1)

            val cur = run.firstOrNull { it.status == JobStatus.Running }
            if (cur == null) {
                val loading = state.message == "Loading models" || !state.running
                return ProcessingNotice(
                    if (loading) "Preparing to process ${plural(total - done, "session")}" else "Processing sessions",
                    if (loading) "Loading the on-device models…" else "Starting the next session…",
                    ongoing = true, progressMax = 1, indeterminate = true,
                )
            }
            val position = run.indexOf(cur) + 1
            val of = if (total > 1) " $position of $total" else ""
            val (title, detail) = when (cur.phase) {
                JobPhase.Transcribing -> "Transcribing session$of" to "Converting audio to text${part(cur, "part")}"
                JobPhase.Reading -> "Analysing session$of" to "Finding key points in the transcript${part(cur, "section")}"
                JobPhase.Checking -> "Analysing session$of" to "Comparing new findings with the profile${part(cur, "item")}"
                JobPhase.Retrying -> "Analysing session$of" to "Something went wrong, retrying (attempt ${cur.step} of ${cur.steps})"
                JobPhase.None -> "Processing session$of" to "Getting ready…"
            }
            val known = cur.steps > 0 && cur.phase != JobPhase.Retrying
            return ProcessingNotice(
                title, detail, ongoing = true,
                progressMax = if (known) cur.steps else 1, progress = if (known) cur.step.coerceIn(0, cur.steps) else 0, indeterminate = !known,
            )
        }

        /** The dismissible end-of-run notice, or null when there is nothing worth telling (nothing ran, or it is only waiting for setup). */
        fun finished(state: QueueState, runIds: Set<String>): ProcessingNotice? {
            val run = state.jobs.filter { it.id in runIds }
            val total = run.size
            val done = run.count { it.status == JobStatus.Done }
            val failed = run.count { it.status == JobStatus.Failed }
            val stopped = run.count { it.status == JobStatus.Cancelled }
            val added = run.filter { it.status == JobStatus.Done }.sumOf { it.added }
            if (state.message == "Waiting for setup" || (total == 0 && !state.message.startsWith("Failed"))) return null
            val facts = if (added > 0) " · ${plural(added, "new fact")} added" else ""
            return when {
                state.message.startsWith("Failed") -> ProcessingNotice("Processing failed", "Open the app to retry", ongoing = false)
                failed > 0 -> ProcessingNotice("${plural(failed, "session")} could not be processed", "${done} of $total done$facts · open the app to retry", ongoing = false)
                stopped > 0 || state.message == "Stopped" -> ProcessingNotice("Processing stopped", "${done} of $total done · open the app to resume", ongoing = false)
                else -> ProcessingNotice(TITLE_DONE, "${plural(done, "session")} done$facts", ongoing = false)
            }
        }

        private fun part(j: SessionJob, noun: String) = if (j.steps > 0) " · $noun ${j.step.coerceIn(1, j.steps)} of ${j.steps}" else ""

        private fun plural(n: Int, word: String) = "$n $word${if (n == 1) "" else "s"}"
    }
}
