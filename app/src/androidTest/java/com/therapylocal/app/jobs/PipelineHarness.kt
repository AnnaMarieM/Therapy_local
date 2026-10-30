package com.therapylocal.app.jobs

import android.content.Context
import com.therapylocal.app.audio.AudioChunk
import com.therapylocal.app.data.ChunkRow
import com.therapylocal.app.data.JobStatus
import com.therapylocal.app.data.MindStore
import com.therapylocal.app.memory.Prompts
import org.json.JSONObject
import kotlin.math.sqrt

/** A fact the fake LLM extracts from any window that contains its [evidence]. */
data class Said(val heading: String, val content: String, val evidence: String)

/**
 * The real store, queue, pipeline and runner on a test database (its own password, no vault), with deterministic fakes for the models and
 * the pending audio kept in memory. No native model library is loaded.
 */
class PipelineHarness(private val context: Context, val name: String, private val catalogue: List<Said>) : AutoCloseable {
    val store = MindStore(context, name, "test password".toByteArray())
    val queue = JobQueue(store)

    /** recording id -> its saved chunks; the "files" cleanup deletes. */
    val audio = LinkedHashMap<Long, List<AudioChunk>>()

    /** Chunk texts the fake ASR "hears": a chunk's single sample is its index here. */
    private val spoken = ArrayList<String>()

    /** Kind and session of every job a handler ran, in order. */
    val ran = ArrayList<Pair<String, String>>()

    val pipeline = Pipeline(
        store, queue, PROMPTS::getValue,
        chunks = { rid -> audio[rid].orEmpty().map { it.idx } },
        chunk = { rid, idx -> audio[rid]?.firstOrNull { it.idx == idx } },
        deleteAudio = { audio.remove(it) },
    )

    val handlers: Map<String, JobHandler> = pipeline.handlers.mapValues { (kind, h) ->
        JobHandler { job, run ->
            ran += kind to job.sessionId
            h.run(job, run)
        }
    }

    fun pool() = ModelPool({ FakeLlm(catalogue) }, { FakeEmbed }, { FakeAsr(spoken) }, { FakeLlm.MODEL })

    /** A session with a stopped recording: one chunk per line, saved as pending audio with untranscribed chunk rows, and its jobs queued. */
    fun record(client: String, session: String, startedAt: Long, lines: List<String>): Long {
        if (store.clients().none { it.id == client }) store.createClient(client, "Client $client")
        store.createSession(session, client, startedAt)
        val rid = store.createRecording(session, "fake", "fake-asr", 10)
        audio[rid] = lines.mapIndexed { i, line ->
            spoken += line
            val start = i * 10_000L
            store.putChunk(ChunkRow(rid, i, start, start + 10_000, 0, null))
            AudioChunk(i, start, start + 10_000, 0, shortArrayOf((spoken.size - 1).toShort()))
        }
        JobGraph.enqueueRecording(queue, session, rid)
        return rid
    }

    fun drain(canRun: (String) -> Boolean = { true }) {
        queue.clearStop()
        JobRunner(queue, handlers, pool(), canRun).drain()
    }

    fun allJobsDone() = store.jobs().isNotEmpty() && store.jobs().all { it.status == JobStatus.Done }

    fun count(table: String, where: String = "1", vararg args: String): Int =
        store.readableDatabase.rawQuery("SELECT COUNT(*) FROM $table WHERE $where", args.takeIf { it.isNotEmpty() }).use { it.moveToFirst(); it.getInt(0) }

    /** What the pipeline stored, without ids and timestamps, so two runs can be compared. */
    fun outcome(client: String): Outcome {
        val facts = store.facts(client)
        return Outcome(
            segments = store.sessions(client).associate { it.id to store.segments(it.id).map { s -> s.text } },
            windows = count("windows"),
            facts = facts.map { listOf(it.sessionId, it.heading, it.content, it.evidence, "${it.segIdx}") }.sortedBy { it.toString() },
            mentions = facts.flatMap { f -> store.factOccurrences(f.id).filter { !it.first }.map { listOf(f.heading, it.sessionId, it.evidence, "${it.segIdx}") } }
                .sortedBy { it.toString() },
            recordings = count("recordings"),
            chunks = count("chunks"),
        )
    }

    data class Outcome(
        val segments: Map<String, List<String>>,
        val windows: Int,
        val facts: List<List<String>>,
        val mentions: List<List<String>>,
        val recordings: Int,
        val chunks: Int,
    )

    /** Closes and deletes the test database. */
    override fun close() {
        store.close()
        context.deleteDatabase(name)
    }

    companion object {
        /** Plain prompts the fake LLM can parse: the window text for extraction, the known facts and the candidate for review. */
        val PROMPTS = mapOf(
            Prompts.EXTRACT_SYSTEM to "extract",
            Prompts.EXTRACT_USER to "{{TEXT}}",
            Prompts.REVIEW_SYSTEM to "review",
            Prompts.REVIEW_USER to "{{KNOWN}}\n---\n{{FACT}}",
        )

        /** Every table of the database. */
        val TABLES = listOf(
            "clients", "sessions", "recordings", "chunks", "segments", "windows", "facts", "mentions", "extract_cache", "review_cache", "jobs", "job_deps",
        )
    }
}

/** Extraction: one JSON line per catalogue fact whose quote is in the window. Review: a duplicate of the known fact with the same heading. */
class FakeLlm(private val catalogue: List<Said>) : Llm {
    override val model = MODEL

    override fun ask(system: String, user: String, schema: String?, maxOutputTokens: Int, temperature: Double, topK: Int): String = when (system) {
        "extract" -> catalogue.filter { user.contains(it.evidence, ignoreCase = true) }
            .joinToString("\n") { JSONObject().put("heading", it.heading).put("content", it.content).put("evidence", it.evidence).toString() }
        "review" -> {
            val (known, fact) = user.split("\n---\n")
            val heading = fact.substringBefore(": ")
            val k = known.lines().indexOfFirst { it.substringAfter(": ").substringBefore(": ") == heading }
            if (k < 0) """{"duplicate": false}""" else """{"duplicate": true, "of": "K${k + 1}"}"""
        }
        else -> error("unexpected prompt")
    }

    override fun close() {}

    companion object {
        const val MODEL = "fake-llm"
    }
}

/** A hashed bag of words, normalised: the same text gives the same vector, texts sharing no words are far apart. */
object FakeEmbed : Embed {
    override val model = "fake-embed"

    override fun embed(text: String): FloatArray {
        val v = FloatArray(64)
        for (w in text.lowercase().split(Regex("[^a-z0-9]+"))) if (w.isNotEmpty()) v[(w.hashCode() and 0x7fffffff) % v.size] += 1f
        if (v.all { it == 0f }) v[0] = 1f
        val norm = sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
        return FloatArray(v.size) { v[it] / norm }
    }

    override fun close() {}
}

class FakeAsr(private val spoken: List<String>) : Asr {
    override fun transcribe(pcm: ShortArray): String = spoken[pcm[0].toInt()]
}
