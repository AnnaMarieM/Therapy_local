package com.therapylocal.app.memory

import android.content.res.Resources
import com.therapylocal.app.R
import com.therapylocal.app.jobs.JobPhase
import com.therapylocal.app.jobs.QueueSnapshot
import com.therapylocal.app.jobs.StepProgress

/** A notification line as a resource plus arguments, so it is worded in the language active when it is posted. */
sealed interface NoticeText {
    fun get(r: Resources): String

    data class Str(val id: Int, val args: List<Any> = emptyList()) : NoticeText {
        override fun get(r: Resources): String = r.getString(id, *args.toTypedArray())
    }

    /** A plural resource; [count] picks the form and is the first argument. */
    data class Plural(val id: Int, val count: Int, val args: List<Any> = emptyList()) : NoticeText {
        override fun get(r: Resources): String = r.getQuantityString(id, count, count, *args.toTypedArray())
    }
}

/** What the processing notification says. [progressMax] = 0 hides the bar; [indeterminate] shows a spinner-style bar. */
data class ProcessingNotice(
    val title: NoticeText,
    val text: NoticeText,
    val ongoing: Boolean,
    val progressMax: Int = 0,
    val progress: Int = 0,
    val indeterminate: Boolean = false,
    val success: Boolean = false,
) {
    companion object {
        private fun str(id: Int, vararg args: Any) = NoticeText.Str(id, args.toList())

        /**
         * Maps the queue to the notification. Only sessions of the current run ([run]) are counted, so sessions finished in earlier runs do not
         * inflate "session 1 of N". No client names are used: the text is safe to show wherever the private notification is shown.
         */
        fun running(s: QueueSnapshot, run: Set<String>): ProcessingNotice {
            val sessions = s.sessions.filter { it.sessionId in run }
            val total = sessions.size
            val done = sessions.count { it.finished }
            if (s.stopping) return ProcessingNotice(str(R.string.processing_stopping_title), str(R.string.processing_stopping_text), ongoing = true, indeterminate = true, progressMax = 1)

            val cur = s.running?.takeIf { it.sessionId in run }
                ?: return ProcessingNotice(
                    NoticeText.Plural(R.plurals.processing_preparing_title, total - done), str(R.string.processing_preparing_text),
                    ongoing = true, progressMax = 1, indeterminate = true,
                )
            val position = sessions.indexOfFirst { it.sessionId == cur.sessionId } + 1
            val ofN = total > 1
            fun title(plain: Int, withPosition: Int) = if (ofN) str(withPosition, position, total) else str(plain)
            val p = s.progress ?: StepProgress(JobPhase.None, 0, 0)
            if (cur.attempts > 0 && p.phase == JobPhase.None) {
                return ProcessingNotice(
                    title(R.string.processing_title_processing, R.string.processing_title_processing_of),
                    str(R.string.processing_retry_text, cur.attempts + 1, cur.maxAttempts), ongoing = true, progressMax = 1, indeterminate = true,
                )
            }
            val step = p.step.coerceIn(1, p.steps.coerceAtLeast(1))
            fun detail(plain: Int, withStep: Int) = if (p.steps > 0) str(withStep, step, p.steps) else str(plain)
            val (title, detail) = when (p.phase) {
                JobPhase.Transcribing -> title(R.string.processing_title_transcribing, R.string.processing_title_transcribing_of) to
                    detail(R.string.processing_detail_transcribing, R.string.processing_detail_transcribing_part)
                JobPhase.Reading -> title(R.string.processing_title_analysing, R.string.processing_title_analysing_of) to
                    detail(R.string.processing_detail_reading, R.string.processing_detail_reading_section)
                JobPhase.Checking -> title(R.string.processing_title_analysing, R.string.processing_title_analysing_of) to
                    detail(R.string.processing_detail_checking, R.string.processing_detail_checking_item)
                JobPhase.None -> title(R.string.processing_title_processing, R.string.processing_title_processing_of) to str(R.string.processing_detail_starting)
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
                failed > 0 -> ProcessingNotice(NoticeText.Plural(R.plurals.processing_failed_title, failed), str(R.string.processing_failed_text, done, total), ongoing = false)
                s.stopping && done < total -> ProcessingNotice(str(R.string.processing_stopped_title), str(R.string.processing_stopped_text, done, total), ongoing = false)
                done == 0 -> null
                else -> ProcessingNotice(str(R.string.processing_done_title), NoticeText.Plural(R.plurals.processing_done_text, done), ongoing = false, success = true)
            }
        }
    }
}
