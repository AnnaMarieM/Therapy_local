package com.therapylocal.app.ui.data

import android.content.Context
import com.therapylocal.app.data.JobStatus
import com.therapylocal.app.data.MindStore
import com.therapylocal.app.facts.PromptLanguage
import com.therapylocal.app.i18n.LocaleStore
import com.therapylocal.app.jobs.JobGraph
import com.therapylocal.app.jobs.Jobs
import com.therapylocal.app.log.AppLog
import com.therapylocal.app.log.LogCtx
import com.therapylocal.app.memory.MemoryFiles
import com.therapylocal.app.memory.MemoryService
import com.therapylocal.app.models.ModelSetup
import com.therapylocal.app.summary.SessionSummaries
import com.therapylocal.app.summary.SessionSummary
import com.therapylocal.app.summary.SummaryPrompts
import com.therapylocal.app.summary.SummaryRepo

/**
 * The last-session summary as the screens ask for it (docs/AD/AD-12.md). No screen decides anything here: [SessionSummary.state] says none,
 * generating, ready or stale. Blocking calls: run them off the main thread. Seams are for tests; [create] wires the app.
 */
class SessionSummaryRepository(
    private val repo: () -> SummaryRepo,
    /** The id of the latest session of a client that has a transcript, or null. */
    private val lastSession: (client: String) -> String?,
    /** The prompt version a summary of the session would get now (language, prompt files, format and limits). */
    private val promptVersion: (session: String) -> String,
    /** The model a summary would be made with now (the one in the provenance of a summary). */
    private val model: () -> String,
    /** Whether a SUMMARY job is queued or running for the session. */
    private val generating: (session: String) -> Boolean,
    /** Queues a SUMMARY job (once per session) and starts the runner. */
    private val enqueue: (session: String) -> Unit,
) {
    private fun summaries() = SessionSummaries(repo())

    /** The summary of the client's latest session with a transcript; null when the client has none. */
    fun lastSessionSummary(client: String): SessionSummary? = lastSession(client)?.let(::summary)

    fun summary(session: String): SessionSummary = summaries().view(session, promptVersion(session), model(), generating(session))

    /** Makes the summary again from the current transcript, roles and prompts, replacing the old one when done. A no-op while one is being made. */
    fun regenerate(session: String) {
        if (!generating(session)) enqueue(session)
    }

    /**
     * Called after the "Is this incorrect?" swap stored a new speaker-map version: the stored summary is flagged stale (its speaker-map version
     * is no longer the current one) and, when there is one, made again. A session without a summary is left alone (its first one, queued with the session, reads the current roles).
     */
    fun onRolesSwapped(session: String) {
        val had = repo().summary(session) != null
        summaries().refreshStale(session, promptVersion(session), model())
        if (had) regenerate(session)
        AppLog.i(TAG, "summary_roles_swapped", LogCtx.NONE, "had_summary" to had)
    }

    companion object {
        private const val TAG = "SessionSummary"

        fun create(context: Context): SessionSummaryRepository {
            val app = context.applicationContext
            val store = { MindStore.get(app) }
            return SessionSummaryRepository(
                repo = store,
                lastSession = { client -> store().sessions(client).lastOrNull { store().transcriptLengthMs(it.id) > 0 }?.id },
                promptVersion = { session ->
                    val language = PromptLanguage.choose(LocaleStore.current(app).tag, store().clientLanguage(store().clientOfSession(session)))
                    SummaryPrompts.load({ MemoryFiles.prompt(app, it) }, language).version()
                },
                model = { ModelSetup.gemma(app).id },
                // A failed job is not "generating": the state falls back to none or stale and regenerate replaces it.
                generating = { session -> Jobs.queue(app).unfinished(JobGraph.SUMMARY, session)?.let { it.status != JobStatus.Failed } == true },
                enqueue = { session ->
                    val queue = Jobs.queue(app)
                    queue.unfinished(JobGraph.SUMMARY, session)?.takeIf { it.status == JobStatus.Failed }?.let(queue::cancel)
                    JobGraph.enqueueSummary(queue, session)
                    MemoryService.resumePending(app)
                },
            )
        }
    }
}
