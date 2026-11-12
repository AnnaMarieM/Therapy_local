package com.therapylocal.app.jobs

import android.content.Context
import com.therapylocal.app.config.EmbeddingConfig
import com.therapylocal.app.facts.Prompts
import com.therapylocal.app.config.ProcessingLanguageSetting
import com.therapylocal.app.data.MindStore
import com.therapylocal.app.facts.ExtractionDialogue
import com.therapylocal.app.i18n.LocaleStore
import com.therapylocal.app.memory.MemoryFiles
import com.therapylocal.app.memory.MemoryService
import com.therapylocal.app.models.ModelSetup
import com.therapylocal.app.perf.PerfStats
import com.therapylocal.app.translate.Pivot
import com.therapylocal.app.ui.logic.Progress

/**
 * Rebuilding what was derived from stored transcripts, e.g. after the app language or the model changed (docs/AD/AD-10.md). A session is stale
 * when a window or fact of it is flagged stale (made from another transcript, prompt or model than now), or when the facts cached for any of
 * its windows were made with other prompts or another model (that also covers a session whose extraction gave no fact to flag). Sessions are
 * listed oldest first per client, the order the facts must be committed in.
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
        val mode = ProcessingLanguageSetting.get(context)
        val model = ModelSetup.gemma(context).id
        val sessions = mutableListOf<String>()
        var ms = 0L
        for (client in store.clients()) {
            val lang = Pivot.promptLanguage(mode, app, client.language)
            // The pivot tag is part of the versions (as in Pipeline), so a switch of the processing language offers a reprocess.
            val tag = Pivot.tag(mode, client.language)
            val version = Pivot.version(
                tag,
                MemoryFiles.prompt(context, Prompts.named(Prompts.EXTRACT_SYSTEM, lang)),
                MemoryFiles.prompt(context, Prompts.named(Prompts.EXTRACT_USER, lang)),
            )
            for (s in store.sessions(client.id)) {
                if (s.id in busy) continue
                val windows = store.windows(s.id)
                if (windows.isEmpty()) continue
                store.refreshStale(s.id, "windows", Pivot.windowCutVersion(tag), EmbeddingConfig.MODEL_ID)
                store.refreshStale(s.id, "facts", version, model)
                if (!store.hasStale(s.id) && windows.all { w -> store.extractCache(s.id, w.idx)?.first == ExtractionDialogue.cacheKey(version, model, w.speakerMapVersion, w.text) }) continue
                sessions += s.id
                ms += store.transcriptLengthMs(s.id)
            }
        }
        return Plan(sessions, ms)
    }

    /** Queues the plan's sessions from the translate job on (their transcripts are stored; translate does nothing outside the pivot mode) and starts the run. */
    fun start(context: Context, plan: Plan) {
        val queue = Jobs.queue(context)
        plan.sessions.forEach { JobGraph.enqueueTranscript(queue, it) }
        MemoryService.resumePending(context)
    }
}
