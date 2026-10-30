package com.therapylocal.app.memory

import android.content.Context
import com.therapylocal.app.data.MindStore
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
 * A leftover job.json is never read by the app itself, so it cannot reset clients or re-run old sessions.
 */
object DevHarness {
    fun start(context: Context) = MemoryService.start(context.applicationContext, debugJob = true)

    /** Clients in order and each client's sessions by date, after deleting those clients first if `resetProfiles` is set. */
    fun seed(context: Context) {
        val root = MemoryFiles.devRoot(context)
        val job = File(root, "job.json").takeIf { it.exists() }?.let { JSONObject(it.readText()) } ?: JSONObject()
        val listed = (job.optJSONArray("sessions") ?: JSONArray()).let { a -> (0 until a.length()).map { a.getJSONObject(it).let { s -> s.getString("file") to s.optString("date", "") } } }
        val sessions = listed.sortedWith(compareBy({ it.first.substringBefore('_') }, { it.second }))
        val store = MindStore.get(context)
        val queue = Jobs.queue(context)
        if (job.optBoolean("resetProfiles")) sessions.map { it.first.substringBefore('_') }.distinct().forEach { store.deleteClient(it) }
        for ((file, date) in sessions) {
            val client = file.substringBefore('_')
            val sid = file.removeSuffix(".md")
            if (store.clients().none { it.id == client }) store.createClient(client, client.replaceFirstChar { it.uppercase() })
            store.createSession(sid, client, startedAt(date))
            if (store.segments(sid).isEmpty()) {
                val raw = File(root, "in/$file").readText()
                val text = if (job.optBoolean("stripLabels")) TranscriptWindows.stripSpeakerLabels(raw) else raw
                store.replaceSegments(sid, text.lines().filter { it.isNotBlank() }.mapIndexed { i, line -> SegmentRow(i + 1, 0, 0, line.trim()) })
            }
            JobGraph.enqueueTranscript(queue, sid)
        }
    }

    fun markDone(context: Context) {
        File(MemoryFiles.devRoot(context), "out").apply { mkdirs() }.resolve("DONE").writeText("ok")
    }

    /** The date at midnight, or now when it is missing. */
    private fun startedAt(date: String): Long =
        runCatching { java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(date)!!.time }.getOrDefault(System.currentTimeMillis())
}
