package com.therapylocal.app.memory

import android.content.Context
import com.therapylocal.app.audio.ChunkRecorder
import com.therapylocal.app.audio.TestAudio
import com.therapylocal.app.data.ChunkRow
import com.therapylocal.app.data.MindStore
import com.therapylocal.app.data.PendingAudio
import com.therapylocal.app.log.AppLog
import com.therapylocal.app.log.LogCtx
import kotlinx.coroutines.runBlocking
import com.therapylocal.app.data.SegmentRow
import com.therapylocal.app.facts.TranscriptWindows
import com.therapylocal.app.jobs.JobGraph
import com.therapylocal.app.jobs.Jobs
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Developer harness, for debug launches only (the `memory_lab` intent or the Memory tab). The dev root's job.json lists text transcripts
 * pushed to its in/ folder:
 *   {"resetProfiles": false, "stripLabels": false, "sessions": [{"file": "<client>_<n>.md", "date": "yyyy-MM-dd"}]}
 * Each becomes a session whose segments are the transcript's non-blank lines, queued from the window job on. out/DONE marks the end of the run.
 * A file ending in .wav (16 kHz mono, in in/) is a recorded session instead: it goes through the real chunker, transcribe and everything after.
 * Optional "settings" ({"language": "cs", "processing_language": "native"}) are written to the app's settings first (the removed speech-engine settings are ignored), and
 * "clientLanguages" ({"<client>": "cs"}, default "en") give the speech language of clients the harness creates, and
 * "queries" ([{"client": "...", "q": "..."}]) are answered after the run. Both transcripts and answers land in out/results.json ([DevResults]).
 * A leftover job.json is never read by the app itself, so it cannot reset clients or re-run old sessions.
 */
object DevHarness {
    /** Speech-engine settings from before Canary became the only recogniser (docs/AD/AD-02.md); a dev job.json that still sets them is ignored. */
    private val LEGACY_SETTINGS = setOf("engine", "whisper_size")

    fun start(context: Context) = MemoryService.start(context.applicationContext, debugJob = true)

    /** Clients in order and each client's sessions by date, after deleting those clients first if `resetProfiles` is set. */
    fun seed(context: Context) {
        val root = MemoryFiles.devRoot(context)
        val job = File(root, "job.json").takeIf { it.exists() }?.let { JSONObject(it.readText()) } ?: JSONObject()
        val listed = (job.optJSONArray("sessions") ?: JSONArray()).let { a -> (0 until a.length()).map { a.getJSONObject(it).let { s -> s.getString("file") to s.optString("date", "") } } }
        val sessions = listed.sortedWith(compareBy({ it.first.substringBefore('_') }, { it.second }))
        job.optJSONObject("settings")?.let { st ->
            val (ignored, kept) = st.keys().asSequence().toList().partition { it in LEGACY_SETTINGS }
            ignored.forEach { AppLog.w(MemoryLlm.TAG, "dev_setting_ignored", LogCtx.NONE, "key" to it, "reason" to "speech_engine_removed") }
            context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().apply { kept.forEach { putString(it, st.getString(it)) } }.apply()
        }
        val languages = job.optJSONObject("clientLanguages")
        val store = MindStore.get(context)
        val queue = Jobs.queue(context)
        if (job.optBoolean("resetProfiles")) sessions.map { it.first.substringBefore('_') }.distinct().forEach { store.deleteClient(it) }
        for ((file, date) in sessions) {
            val client = file.substringBefore('_')
            val sid = file.removeSuffix(".md").removeSuffix(".wav")
            if (store.clients().none { it.id == client }) store.createClient(client, client.replaceFirstChar { it.uppercase() }, languages?.optString(client, "en") ?: "en")
            store.createSession(sid, client, startedAt(date))
            if (file.endsWith(".wav")) {
                if (store.segments(sid).isEmpty() && store.recordings().none { it.sessionId == sid }) seedAudio(context, store, queue, sid, File(root, "in/$file"))
                continue
            }
            if (store.segments(sid).isEmpty()) {
                val raw = File(root, "in/$file").readText()
                val text = if (job.optBoolean("stripLabels")) TranscriptWindows.stripSpeakerLabels(raw) else raw
                store.appendTranscript(sid, text.lines().filter { it.isNotBlank() }.mapIndexed { i, line -> SegmentRow(i + 1, 0, 0, line.trim()) })
            }
            JobGraph.enqueueTranscript(queue, sid)
        }
    }

    /** Replays the WAV through the real chunker at 60x, saves the chunks like a recording would, and queues it from transcribe on. */
    internal fun seedAudio(context: Context, store: MindStore, queue: com.therapylocal.app.jobs.JobQueue, sid: String, wav: File) {
        val rid = store.createRecording(sid, "canary", "")
        val rec = ChunkRecorder.recording(LogCtx(rid))
        rec.startFromPcm(TestAudio.load(wav), speed = 60f)
        runBlocking {
            for (chunk in rec.chunks) {
                store.putChunk(ChunkRow(rid, chunk.idx, chunk.startMs, chunk.endMs, chunk.overlapMs, null))
                PendingAudio.save(context, rid, chunk)
            }
        }
        rec.stop()
        JobGraph.enqueueRecording(queue, sid, rid)
    }

    fun markDone(context: Context) {
        val out = File(MemoryFiles.devRoot(context), "out").apply { mkdirs() }
        runCatching { DevResults.write(context, out) }.onFailure { out.resolve("ERROR").writeText(it.stackTraceToString()) }
        out.resolve("DONE").writeText("ok")
    }

    /** The date at midnight, or now when it is missing. */
    private fun startedAt(date: String): Long =
        runCatching { java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(date)!!.time }.getOrDefault(System.currentTimeMillis())
}
