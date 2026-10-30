package com.therapylocal.app.jobs

import com.therapylocal.app.audio.AudioChunk
import com.therapylocal.app.config.MemoryConfig
import com.therapylocal.app.data.ChunkRow
import com.therapylocal.app.data.Job
import com.therapylocal.app.data.MindStore
import com.therapylocal.app.data.NewFact
import com.therapylocal.app.data.NewMention
import com.therapylocal.app.data.SegmentBuilder
import com.therapylocal.app.data.WindowRow
import com.therapylocal.app.facts.Candidate
import com.therapylocal.app.facts.CommitPlanner
import com.therapylocal.app.facts.Fact
import com.therapylocal.app.facts.FactParser
import com.therapylocal.app.facts.FactSchemas
import com.therapylocal.app.facts.Known
import com.therapylocal.app.facts.Prompts
import com.therapylocal.app.facts.TranscriptWindows
import com.therapylocal.app.facts.Verdict
import com.therapylocal.app.log.AppLog
import com.therapylocal.app.log.LogCtx
import org.json.JSONArray
import java.io.IOException
import java.security.GeneralSecurityException
import kotlin.math.sqrt

/**
 * The handlers of the session pipeline (see [JobGraph] for how they connect). Each can be run again after a crash or a retry and ends in
 * the same state: results are written whole (delete then insert) and the LLM's answers are cached under the prompt version and model.
 */
class Pipeline(
    private val store: MindStore,
    private val queue: JobQueue,
    /** A prompt file's text: a developer copy wins over the one shipped in the app. */
    private val prompt: (String) -> String,
    /** The indices of a recording's saved chunks, in order. */
    private val chunks: (Long) -> List<Int>,
    /** One saved chunk of a recording, null if it is missing; throws if it cannot be read. */
    private val chunk: (Long, Int) -> AudioChunk?,
    private val deleteAudio: (Long) -> Unit,
) {
    val handlers: Map<String, JobHandler> = mapOf(
        JobGraph.TRANSCRIBE to JobHandler(::transcribe),
        JobGraph.WINDOW to JobHandler(::window),
        JobGraph.EXTRACT to JobHandler(::extract),
        JobGraph.COMMIT to JobHandler(::commit),
        JobGraph.CLEANUP to JobHandler(::cleanup),
    )

    /**
     * Transcribes the chunks that have no text yet, one loaded at a time, then rebuilds the session's segments from all of them. A chunk that
     * cannot be read fails the job (code AUDIO), so the audio is kept and the session is not processed with a hole in it.
     */
    private fun transcribe(job: Job, run: JobContext) {
        val rid = job.recordingId ?: throw JobFailure("NO_RECORDING", permanent = true)
        val done = store.chunks(rid).filter { it.text != null }.map { it.idx }.toSet()
        val todo = chunks(rid).filter { it !in done }
        run.progress(JobPhase.Transcribing, 0, todo.size)
        todo.forEachIndexed { i, idx ->
            run.checkStop()
            val c = try {
                chunk(rid, idx)
            } catch (_: IOException) {
                null
            } catch (_: GeneralSecurityException) {
                null
            } ?: throw JobFailure("AUDIO")
            store.putChunk(ChunkRow(rid, c.idx, c.startMs, c.endMs, c.overlapMs, run.pool.asr().transcribe(c.pcm)))
            run.progress(JobPhase.Transcribing, i + 1, todo.size)
        }
        // A recording without chunks (gone, or too short to have any) leaves the segments as they are.
        val chunks = store.chunks(rid)
        if (chunks.isNotEmpty()) store.replaceSegments(job.sessionId, SegmentBuilder.build(chunks))
    }

    /** Cuts the transcript into embedded windows, then queues the rest of the session's graph. */
    private fun window(job: Job, run: JobContext) {
        val words = TranscriptWindows.wordsFor(MemoryConfig.WINDOW_TOKENS)
        val spans = TranscriptWindows.bySegments(store.segments(job.sessionId), words, TranscriptWindows.wordsFor(MemoryConfig.WINDOW_OVERLAP_TOKENS))
        run.progress(JobPhase.Reading, 0, spans.size)
        val rows = spans.mapIndexed { i, s ->
            run.checkStop()
            val embed = run.pool.embed()
            WindowRow(i + 1, s.segFrom, s.segTo, s.text, embedFitting(embed, s.text), embed.model)
        }
        store.replaceWindows(job.sessionId, rows)
        val sessions = store.sessions(job.clientId).map { it.id }
        val at = sessions.indexOf(job.sessionId)
        if (at >= 0) JobGraph.expand(queue, job, rows.size, sessions.getOrNull(at - 1), sessions.getOrNull(at + 1))
    }

    /** The facts of window [Job.ref], grounded in it and mapped to their segment, cached for [commit]. */
    private fun extract(job: Job, run: JobContext) {
        val idx = job.ref.toInt()
        val windows = store.windows(job.sessionId)
        val window = windows.firstOrNull { it.idx == idx } ?: return
        run.progress(JobPhase.Reading, idx, windows.size)
        val system = prompt(Prompts.EXTRACT_SYSTEM)
        val template = prompt(Prompts.EXTRACT_USER)
        // Prompt version | model | window text: a window cut or transcribed differently is read again.
        val key = "${Prompts.version(system, template)}|${run.pool.llmModel()}|${Prompts.hash(window.text)}"
        if (store.extractCache(job.sessionId, idx)?.first == key) return
        val client = store.clients().firstOrNull { it.id == job.clientId }?.name ?: job.clientId
        val user = Prompts.fill(
            template,
            mapOf("CLIENT" to client, "DATE" to date(job.sessionStartedAt), "PART" to "$idx", "PARTS" to "${windows.size}", "TEXT" to window.text),
        )
        val segments = store.segments(job.sessionId).filter { it.idx in window.segFrom..window.segTo }
        val facts = extractFacts(run.pool.llm(), system, user, window.text).map { f -> f.copy(segIdx = FactParser.segmentOf(f.evidence, segments) ?: window.segFrom) }
        store.putExtractCache(job.sessionId, idx, key, JSONArray(facts.map { it.toJson() }).toString())
    }

    /**
     * Up to three attempts: one JSON object per line is asked for twice, then a constrained schema as a last resort (constrained decoding
     * costs about 4 s per conversation and loops on the GPU, so not first). Facts whose quote is not in the window are dropped.
     */
    private fun extractFacts(llm: Llm, system: String, user: String, window: String): List<Fact> {
        var answered = false
        for (attempt in 1..3) {
            val reply = try {
                llm.ask(system, user, if (attempt == 3) FactSchemas.EXTRACT_STRICT else null, MemoryConfig.EXTRACT_MAX_OUTPUT_TOKENS, MemoryConfig.EXTRACT_TEMPERATURE, MemoryConfig.EXTRACT_TOP_K)
            } catch (t: Throwable) {
                AppLog.w(JobRunner.TAG, "extract_attempt_failed", LogCtx.NONE, "attempt" to attempt, "type" to t.javaClass.simpleName)
                continue
            }
            answered = true
            val grounded = FactParser.anyFacts(reply).filter { FactParser.evidenceHolds(it, window) }
            if (grounded.isNotEmpty()) return grounded
        }
        // Replies without a usable fact are a real answer (nothing to note); no reply at all is a failure, retried by the queue.
        if (!answered) throw JobFailure("LLM")
        return emptyList()
    }

    /**
     * Phase A, outside any transaction: plans every candidate against what was known before this session ([CommitPlanner]). Phase B, one short
     * transaction: the session's facts and mentions are replaced by the plan. Then the client's later sessions are committed again ([JobGraph.recommit]).
     */
    private fun commit(job: Job, run: JobContext) {
        val sid = job.sessionId
        val extracted = store.windows(sid).flatMap { w ->
            val (key, json) = store.extractCache(sid, w.idx) ?: throw JobFailure("NO_EXTRACT")
            JSONArray(json).let { a -> (0 until a.length()).map { Fact.fromJson(a.getJSONObject(it)) to key } }
        }
        val keyOf = HashMap<Candidate, String>()
        val candidates = extracted.map { (f, key) ->
            run.checkStop()
            Candidate(f, embedFitting(run.pool.embed(), "${f.heading}. ${f.content}")).also { keyOf[it] = key }
        }
        val system = prompt(Prompts.REVIEW_SYSTEM)
        val template = prompt(Prompts.REVIEW_USER)
        val version = "${Prompts.version(system, template)}|${run.pool.llmModel()}"
        val plan = CommitPlanner.plan(
            candidates,
            earlier = { v -> store.nearestFacts(job.clientId, v, MemoryConfig.NEIGHBOURS, beforeSession = sid).map { (f, s) -> Known(f.id, f.heading, f.content) to s } },
            review = { fact, known -> review(run, sid, system, template, version, fact, known) },
            minSimilarity = MemoryConfig.MIN_SIMILARITY,
            neighbours = MemoryConfig.NEIGHBOURS,
            step = { i ->
                run.checkStop()
                run.progress(JobPhase.Checking, i, candidates.size)
            },
        )
        store.replaceSessionFacts(
            sid,
            plan.facts.map { c ->
                val (promptVersion, model) = keyOf.getValue(c).split('|')
                NewFact(job.clientId, sid, c.fact.heading, c.fact.content, c.fact.evidence, c.fact.segIdx, c.vec, model, promptVersion)
            },
            plan.mentions.map { (factId, f) -> NewMention(factId, sid, f.evidence, f.segIdx) },
        )
        AppLog.i(JobRunner.TAG, "commit_done", LogCtx.NONE, "candidates" to candidates.size, "facts" to plan.facts.size, "mentions" to plan.mentions.size)
        val sessions = store.sessions(job.clientId).map { it.id }
        val at = sessions.indexOf(sid)
        if (at >= 0) JobGraph.recommit(queue, job, sessions.drop(at + 1).filter(store::hasWindows))
    }

    /** The LLM's duplicate verdict, cached per session under the prompt version, model, candidate and the known facts it was shown. */
    private fun review(run: JobContext, session: String, system: String, template: String, version: String, fact: Fact, known: List<Known>): Verdict? {
        val listed = known.mapIndexed { i, k -> "K${i + 1}: ${k.heading}: ${k.content}" }.joinToString("\n")
        val key = Prompts.hash(version, fact.heading, fact.content, listed)
        store.reviewVerdict(session, key)?.let(Verdict::decode)?.let { return it }
        val llm = run.pool.llm()
        val user = Prompts.fill(template, mapOf("KNOWN" to listed, "FACT" to "${fact.heading}: ${fact.content}"))
        // Not schema-constrained: turning constrained decoding on makes createConversation take about 4 s (measured), against 2 ms without it.
        val reply = try {
            llm.ask(system, user, null, MemoryConfig.REVIEW_MAX_OUTPUT_TOKENS, MemoryConfig.REVIEW_TEMPERATURE, MemoryConfig.REVIEW_TOP_K)
        } catch (t: Throwable) {
            AppLog.w(JobRunner.TAG, "review_failed", LogCtx.NONE, "type" to t.javaClass.simpleName)
            return null
        }
        return FactParser.verdict(reply, known.size)?.also { store.putReviewVerdict(session, key, it.encode()) }
    }

    /** Only now, with everything stored, is the audio deleted. The recording's raw chunks go with it; the segments stay. */
    private fun cleanup(job: Job, @Suppress("UNUSED_PARAMETER") run: JobContext) {
        val rid = job.recordingId ?: return
        deleteAudio(rid)
        store.deleteRecording(rid)
    }

    /**
     * The embedder rejects texts over its token limit, and the words-to-tokens estimate used for windows can be a few percent low. A text
     * that is too long is split in half by words, each half embedded, and the vectors averaged and renormalised, so it never fails for its length.
     */
    private fun embedFitting(embed: Embed, text: String, depth: Int = 0): FloatArray = try {
        embed.embed(text)
    } catch (t: Throwable) {
        val words = text.split(' ')
        if (depth >= 3 || words.size < 20 || t.message?.contains("exceeds maximum") != true) throw t
        val half = words.size / 2
        val a = embedFitting(embed, words.take(half).joinToString(" "), depth + 1)
        val b = embedFitting(embed, words.drop(half).joinToString(" "), depth + 1)
        val m = FloatArray(a.size) { a[it] + b[it] }
        val norm = sqrt(m.sumOf { (it * it).toDouble() }).toFloat().coerceAtLeast(1e-9f)
        FloatArray(m.size) { m[it] / norm }
    }

    private fun date(startedAt: Long): String = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(startedAt))
}
