package com.therapylocal.app.ui.data

import android.content.Context
import com.therapylocal.app.config.SummaryConfig
import com.therapylocal.app.config.ProcessingLanguageSetting
import com.therapylocal.app.config.SummaryFormatSetting
import com.therapylocal.app.data.JobStatus
import com.therapylocal.app.data.MindStore
import com.therapylocal.app.translate.Display
import com.therapylocal.app.translate.DisplayLanguage
import com.therapylocal.app.translate.Pivot
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
    /** The ids of every client in the vault (for rebuilding after a format change). */
    private val clients: () -> List<String> = { emptyList() },
    /** The id of the summary format in use, and storing a new one (the vault-wide setting). */
    private val format: () -> String = { SummaryConfig.DEFAULT_FORMAT_ID },
    private val saveFormat: (String) -> Unit = {},
    /** The display translations of [texts] into the language the summary is shown in (docs/AD/AD-17.md); nothing found shows the English text as stored. */
    private val display: (texts: List<String>) -> Display = { Display.NONE },
) {
    private fun summaries() = SessionSummaries(repo())

    /** The summary of the client's latest session with a transcript; null when the client has none. */
    fun lastSessionSummary(client: String): SessionSummary? = lastSession(client)?.let(::summary)

    fun summary(session: String): SessionSummary = shown(summaries().view(session, promptVersion(session), model(), generating(session)))

    /**
     * The summary as it is shown: in the pivot mode it is stored in English and each section body is replaced by its translation when the LOCALIZE job
     * has made one. The English labels stay (the parser and the screens rely on them), and [SessionSummary.text] is put together from the shown sections.
     */
    private fun shown(s: SessionSummary): SessionSummary {
        if (s.text == null) return s
        val shown = display(s.sections.map { it.text })
        if (shown.isEmpty) return s
        val sections = s.sections.map { it.copy(text = shown.text(it.text)) }
        val text = sections.joinToString("\n") { if (it.label.isEmpty()) it.text else "${it.label}: ${it.text}" }
        return s.copy(text = text, sections = sections)
    }

    /** Makes the summary again from the current transcript, roles and prompts, replacing the old one when done. A no-op while one is being made. */
    fun regenerate(session: String) {
        if (!generating(session)) enqueue(session)
    }

    /** The id of the summary format in use. */
    fun format(): String = format.invoke()

    /**
     * Chooses the summary format for the whole vault. A change is stored first (the prompt version of every summary now differs, so each shows
     * as stale) and then the summary of every client's last session that has one is made again with the existing SUMMARY job. Older sessions
     * are only flagged stale when viewed. Returns false, and does nothing, when [id] is the format in use or is not registered.
     */
    fun changeFormat(id: String): Boolean {
        if (id !in SummaryConfig.FORMATS || id == format()) return false
        saveFormat(id)
        var rebuilt = 0
        for (client in clients()) {
            val session = lastSession(client) ?: continue
            if (repo().summary(session) == null) continue // nothing to replace; its first one is made with the format then in use
            summaries().refreshStale(session, promptVersion(session), model())
            regenerate(session)
            rebuilt++
        }
        AppLog.i(TAG, "summary_format_changed", LogCtx.NONE, "format" to id, "rebuilt" to rebuilt)
        return true
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
                    val mode = ProcessingLanguageSetting.get(app)
                    val client = store().clientLanguage(store().clientOfSession(session))
                    val language = Pivot.promptLanguage(mode, LocaleStore.current(app).tag, client)
                    SummaryPrompts.load({ MemoryFiles.prompt(app, it) }, language, SummaryFormatSetting.get(app), Pivot.tag(mode, client)).version()
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
                clients = { store().clients().map { it.id } },
                format = { SummaryFormatSetting.get(app) },
                saveFormat = { SummaryFormatSetting.set(app, it) },
                display = { texts -> Display.of(DisplayLanguage.target(app), texts, store()::displayTranslations) },
            )
        }
    }
}
