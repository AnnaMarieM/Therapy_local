package com.therapylocal.app.jobs

import com.therapylocal.app.engine.EngineDied
import com.therapylocal.app.engine.EngineUnavailable
import com.therapylocal.app.audio.AudioChunk
import com.therapylocal.app.config.MemoryConfig
import com.therapylocal.app.data.ChunkRow
import com.therapylocal.app.data.Job
import com.therapylocal.app.data.MindStore
import com.therapylocal.app.data.NewFact
import com.therapylocal.app.data.NewMention
import com.therapylocal.app.data.SegmentRow
import com.therapylocal.app.data.SpeakerMaps
import com.therapylocal.app.data.TurnRow
import com.therapylocal.app.data.WindowRow
import com.therapylocal.app.facts.Candidate
import com.therapylocal.app.facts.CommitPlanner
import com.therapylocal.app.facts.Fact
import com.therapylocal.app.facts.FactParser
import com.therapylocal.app.facts.FactSchemas
import com.therapylocal.app.facts.Known
import com.therapylocal.app.facts.Prompts
import com.therapylocal.app.facts.PromptBudget
import com.therapylocal.app.facts.PromptLanguage
import com.therapylocal.app.facts.RoleGuess
import com.therapylocal.app.facts.TranscriptWindows
import com.therapylocal.app.facts.Verdict
import com.therapylocal.app.log.AppLog
import com.therapylocal.app.log.LogCtx
import org.json.JSONArray
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
    /** Called by cleanup with the recording and its session once everything is stored: the app keeps the audio for a week, a test drops it. */
    private val finishAudio: (Long, String) -> Unit,
    /** The prompt language ([PromptLanguage]) for a client's language code: Czech only for a Czech app and a Czech client. */
    private val promptLanguage: (clientLanguage: String) -> String = { PromptLanguage.EN },
    /** True when the diarization model is on the phone; without it TRANSCRIBE takes the chunk path, so an install from before the model existed keeps working. */
    diarizationReady: () -> Boolean = { true },
) {
    /** The language of the prompts for the client of [job]: Czech only for a Czech app and a Czech client. */
    private fun language(job: Job): String = promptLanguage(store.clientLanguage(job.clientId))

    private fun promptFor(name: String, job: Job): String = prompt(Prompts.named(name, language(job)))

    val handlers: Map<String, JobHandler> = mapOf(
        JobGraph.TRANSCRIBE to JobHandler(::transcribe),
        JobGraph.ROLES to JobHandler(::roles),
        JobGraph.WINDOW to JobHandler(::window),
        JobGraph.EXTRACT to JobHandler(::extract),
        JobGraph.COMMIT to JobHandler(::commit),
        JobGraph.CLEANUP to JobHandler(::cleanup),
    )

    private val transcribeFlow = TranscribeFlow(
        object : TranscribeStore {
            override fun chunks(recordingId: Long) = store.chunks(recordingId)
            override fun putChunk(c: ChunkRow) = store.putChunk(c)
            override fun turns(recordingId: Long) = store.turns(recordingId)
            override fun replaceTurns(recordingId: Long, turns: List<TurnRow>) = store.replaceTurns(recordingId, turns)
            override fun setTurnText(recordingId: Long, idx: Int, text: String) { store.setTurnText(recordingId, idx, text) }
            override fun replaceSegments(session: String, segments: List<SegmentRow>) = store.replaceSegments(session, segments)
        },
        chunks, chunk, diarizationReady,
    )

    /**
     * Diarizes the recording and transcribes it turn by turn, or, when that cannot be done, chunk by chunk ([TranscribeFlow]). A chunk that
     * cannot be read fails the job (code AUDIO), so the audio is kept and the session is not processed with a hole in it.
     */
    private fun transcribe(job: Job, run: JobContext) {
        val rid = job.recordingId ?: throw JobFailure("NO_RECORDING", permanent = true)
        transcribeFlow.run(rid, job.sessionId, store.clientLanguage(job.clientId), run)
    }

    /**
     * Who is the therapist: the LLM reads the start of the transcript and names the therapist and the patient ([RoleGuess]). Skipped for a
     * transcript with fewer than two speakers and for a session that already has a map (a run again changes nothing). An answer that fails the
     * check is asked for once more, then the first speaker is taken as the therapist (source `fallback`, confidence 0).
     */
    private fun roles(job: Job, run: JobContext) {
        val sid = job.sessionId
        val system = promptFor(Prompts.ROLES_SYSTEM, job)
        val d = RoleStep.decide(store.segments(sid), store.currentSpeakerMap(sid) != null) { input ->
            run.checkStop()
            run.pool.llm().ask(system, input, RoleGuess.SCHEMA, MemoryConfig.ROLES_MAX_OUTPUT_TOKENS, MemoryConfig.ROLES_TEMPERATURE, MemoryConfig.ROLES_TOP_K)
        } ?: return
        val version = store.appendSpeakerMap(sid, SpeakerMaps.encode(d.guess.map), d.source, d.guess.confidence, JSONArray(d.guess.evidence).toString())
        AppLog.i(
            JobRunner.TAG, "roles_done", LogCtx.NONE,
            "source" to d.source, "version" to version, "speakers" to d.speakers.size, "attempts" to d.attempts, "confidence" to d.guess.confidence, "evidence" to d.guess.evidence.size,
            "model" to run.pool.llmModel(), "prompt_version" to MemoryConfig.ROLES_PROMPT_VERSION, "prompt_hash" to Prompts.version(system),
        )
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

    private fun extractUser(job: Job, template: String, window: com.therapylocal.app.data.WindowRef, parts: Int): String {
        val client = store.clients().firstOrNull { it.id == job.clientId }?.name ?: job.clientId
        return Prompts.fill(
            template,
            mapOf("CLIENT" to client, "DATE" to date(job.sessionStartedAt), "PART" to "${window.idx}", "PARTS" to "$parts", "TEXT" to window.text),
        )
    }

    /**
     * The context the LLM needs for all work still open, counted before it is loaded ([PromptBudget]): the prompt of every window already cut
     * (instructions, the window, the constrained-decoding schema of the last retry) plus the longest reply allowed. Sessions not cut into
     * windows yet count as one full window of long words, and the duplicate review as ten known facts of maximal length. Never below the
     * size the app always used; the model is reloaded with more when later work turns out to need more.
     */
    fun requiredContext(): Int {
        val open = queue.openJobs()
        var need = 0
        val windowsOf = HashMap<String, List<com.therapylocal.app.data.WindowRef>>()
        for (job in open) {
            for (lang in listOf(language(job))) {
                val extractSystem = prompt(Prompts.named(Prompts.EXTRACT_SYSTEM, lang)) + FactSchemas.EXTRACT_STRICT
                val extractTemplate = prompt(Prompts.named(Prompts.EXTRACT_USER, lang))
                when (job.kind) {
                    JobGraph.EXTRACT -> {
                        val windows = windowsOf.getOrPut(job.sessionId) { store.windows(job.sessionId) }
                        val window = windows.firstOrNull { it.idx == job.ref.toIntOrNull() } ?: continue
                        need = maxOf(need, PromptBudget.required(extractSystem, extractUser(job, extractTemplate, window, windows.size), MemoryConfig.EXTRACT_MAX_OUTPUT_TOKENS))
                    }
                    JobGraph.ROLES -> {
                        val text = "ě".repeat(TranscriptWindows.wordsFor(MemoryConfig.ROLES_INPUT_TOKENS) * WORD_CHARS)
                        need = maxOf(need, PromptBudget.required(prompt(Prompts.named(Prompts.ROLES_SYSTEM, lang)) + RoleGuess.SCHEMA, text, MemoryConfig.ROLES_MAX_OUTPUT_TOKENS))
                    }
                    JobGraph.WINDOW -> {
                        // A word is at most WORD_CHARS characters with its space, an accented one for the safe (high) token estimate.
                        val text = "ě".repeat(TranscriptWindows.wordsFor(MemoryConfig.WINDOW_TOKENS) * WORD_CHARS)
                        need = maxOf(need, PromptBudget.required(extractSystem, Prompts.fill(extractTemplate, mapOf("TEXT" to text, "CLIENT" to "x".repeat(40))), MemoryConfig.EXTRACT_MAX_OUTPUT_TOKENS))
                    }
                    JobGraph.COMMIT -> {
                        val known = "ě".repeat(MemoryConfig.NEIGHBOURS * REVIEW_FACT_CHARS)
                        val user = Prompts.fill(prompt(Prompts.named(Prompts.REVIEW_USER, lang)), mapOf("KNOWN" to known, "FACT" to "ě".repeat(REVIEW_FACT_CHARS)))
                        need = maxOf(need, PromptBudget.required(prompt(Prompts.named(Prompts.REVIEW_SYSTEM, lang)), user, MemoryConfig.REVIEW_MAX_OUTPUT_TOKENS))
                    }
                }
            }
        }
        val context = PromptBudget.contextFor(need, MemoryConfig.LLM_MAX_TOKENS)
        if (need > PromptBudget.MAX_CONTEXT) AppLog.w(JobRunner.TAG, "context_capped", LogCtx.NONE, "needed" to need, "context" to context)
        return context
    }

    /** The facts of window [Job.ref], grounded in it and mapped to their segment, cached for [commit]. */
    private fun extract(job: Job, run: JobContext) {
        val idx = job.ref.toInt()
        val windows = store.windows(job.sessionId)
        val window = windows.firstOrNull { it.idx == idx } ?: return
        run.progress(JobPhase.Reading, idx, windows.size)
        val system = promptFor(Prompts.EXTRACT_SYSTEM, job)
        val template = promptFor(Prompts.EXTRACT_USER, job)
        // Prompt version | model | window text: a window cut or transcribed differently is read again.
        val key = "${Prompts.version(system, template)}|${run.pool.llmModel()}|${Prompts.hash(window.text)}"
        if (store.extractCache(job.sessionId, idx)?.first == key) return
        val user = extractUser(job, template, window, windows.size)
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
                // The engine ended or no host can serve: another try on the same dead handle only repeats it. The queue defers or retries the job.
                if (t is EngineDied || t is EngineUnavailable) throw t
                AppLog.w(JobRunner.TAG, "extract_attempt_failed", LogCtx.NONE, "attempt" to attempt, "type" to t.javaClass.simpleName, "constrained" to (attempt == 3), "frames" to com.therapylocal.app.models.BackendLadder.frames(t, 4))
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
        val system = promptFor(Prompts.REVIEW_SYSTEM, job)
        val template = promptFor(Prompts.REVIEW_USER, job)
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
            if (t is EngineDied || t is EngineUnavailable) throw t
            AppLog.w(JobRunner.TAG, "review_failed", LogCtx.NONE, "type" to t.javaClass.simpleName, "frames" to com.therapylocal.app.models.BackendLadder.frames(t, 4))
            return null
        }
        return FactParser.verdict(reply, known.size)?.also { store.putReviewVerdict(session, key, it.encode()) }
    }

    /** Only now, with everything stored, is the audio let go (see [finishAudio]). The recording's raw chunks go with it; the segments stay. */
    private fun cleanup(job: Job, @Suppress("UNUSED_PARAMETER") run: JobContext) {
        val rid = job.recordingId ?: return
        finishAudio(rid, job.sessionId)
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

    private companion object {
        /** Characters of a word with its space that the up-front count assumes for a transcript not cut into windows yet (Czech averages about 7). */
        const val WORD_CHARS = 10

        /** "K1: heading: content" of a known fact, at most: a heading of 60 and content of 300 are what the strict schema allows, plus room. */
        const val REVIEW_FACT_CHARS = 480
    }

    private fun date(startedAt: Long): String = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(startedAt))
}
