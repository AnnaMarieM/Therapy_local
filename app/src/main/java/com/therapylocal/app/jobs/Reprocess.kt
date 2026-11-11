package com.therapylocal.app.jobs

import android.content.Context
import com.therapylocal.app.config.EmbeddingConfig
import com.therapylocal.app.config.MemoryConfig
import com.therapylocal.app.data.Derived
import com.therapylocal.app.data.Expectation
import com.therapylocal.app.data.MindStore
import com.therapylocal.app.data.Rebuild
import com.therapylocal.app.data.SessionStamp
import com.therapylocal.app.facts.PromptLanguage
import com.therapylocal.app.facts.Prompts
import com.therapylocal.app.i18n.LocaleStore
import com.therapylocal.app.memory.MemoryFiles
import com.therapylocal.app.memory.MemoryService
import com.therapylocal.app.models.ModelSetup
import com.therapylocal.app.perf.PerfStats
import com.therapylocal.app.ui.logic.Progress

/**
 * Rebuilding what was derived from stored transcripts, e.g. after the app language changed (the facts are written in the language of the
 * prompts) or the model changed (docs/AD/AD-10.md). A session is stale when a window or fact of it carries the stored stale flag, which is
 * refreshed here by comparing each item's provenance (transcript hash, speaker map, prompt, model) with what is in use now, or when the facts
 * cached for any of its windows were made with other prompts or another model (the cache key covers a session whose extraction gave no fact,
 * which has no fact row to flag). The rebuild queues the sessions oldest first per client ([Rebuild.order]).
 */
object Reprocess {
    /** The [sessions] to read again (oldest first per client) and what [transcriptMs] of interview that is. */
    data class Plan(val sessions: List<String>, val transcriptMs: Long) {
        /** Padded estimate from what finished sessions took on this phone; null until one has finished. */
        fun estimateMs(context: Context): Long? =
            PerfStats.perMinute(ModelSetup.gemma(context).id)?.rereadMs(transcriptMs)?.let { (it * Progress.PADDING).toLong() }
    }

    /** Sessions without open jobs that need a rebuild, oldest first per client. Needs the vault unlocked. */
    fun plan(context: Context): Plan {
        val store = MindStore.get(context)
        val queue = Jobs.queue(context)
        val busy = queue.openJobs().map { it.sessionId }.toSet()
        val app = LocaleStore.current(context).tag
        val model = ModelSetup.gemma(context).id
        val stale = mutableListOf<SessionStamp>()
        for (client in store.clients()) {
            val lang = PromptLanguage.choose(app, client.language)
            val version = Prompts.version(
                MemoryFiles.prompt(context, Prompts.named(Prompts.EXTRACT_SYSTEM, lang)),
                MemoryFiles.prompt(context, Prompts.named(Prompts.EXTRACT_USER, lang)),
            )
            val expected = mapOf(
                Derived.FACTS to Expectation(version, model),
                Derived.WINDOWS to Expectation(MemoryConfig.WINDOW_CUT_VERSION, EmbeddingConfig.MODEL_ID),
            )
            for (s in store.sessions(client.id)) {
                if (s.id in busy) continue
                val windows = store.windows(s.id)
                if (windows.isEmpty()) continue
                val flagged = store.refreshStale(s.id, expected) > 0
                if (flagged || windows.any { w -> store.extractCache(s.id, w.idx)?.first != "$version|$model|${Prompts.hash(w.text)}" }) stale += SessionStamp(s.id, client.id, s.startedAt)
            }
        }
        val sessions = Rebuild.order(stale, busy)
        return Plan(sessions, sessions.sumOf { store.transcriptLengthMs(it) })
    }

    /** Queues the plan's sessions from the window job on (their transcripts are stored) and starts the run. */
    fun start(context: Context, plan: Plan) {
        val queue = Jobs.queue(context)
        plan.sessions.forEach { JobGraph.enqueueTranscript(queue, it) }
        MemoryService.resumePending(context)
    }
}
