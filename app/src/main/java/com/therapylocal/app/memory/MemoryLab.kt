package com.therapylocal.app.memory

import android.content.Context
import com.therapylocal.app.asr.SherpaWhisperAsr
import com.therapylocal.app.data.ChunkProcessor
import com.therapylocal.app.data.PendingAudio
import com.therapylocal.app.data.Store
import com.therapylocal.app.embed.Embedder
import com.therapylocal.app.models.ModelSetup
import com.therapylocal.app.log.AppLog
import com.therapylocal.app.log.LogCtx
import com.therapylocal.app.vault.SecureFile
import com.therapylocal.app.vault.Vault
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException

/**
 * Long-term-memory pipeline, run as a queue of session transcripts (see [MemoryQueue], [MemoryService]).
 *
 * Patient data lives only in the vault (internal storage, see [MemoryFiles]): transcripts `in/<name>`, the per-chunk extraction cache `cache/<sid>/<n>.json`
 * and `queue.json` are SecureFiles under the vault key; the memory itself is in [MemoryStore] (SQLCipher). None of it is readable while the vault is locked.
 * The external developer root ([MemoryFiles.devRoot], `files/memory/` on external storage) holds only developer inputs, so prompts and settings change without a rebuild:
 *   job.json                        settings and the ordered list of transcripts (see [Job]); read only in debug-harness mode
 *   prompts/(extract|review).(system|user).txt
 *   in/<name>                       test transcripts pushed by the harness; imported into the vault on first use
 * and, only in debug-harness mode ([useJobFile]), diagnostics in `out/`: `raw/` (raw model replies), `decisions.jsonl`, `bench.jsonl`, `DONE`. App mode writes none of these.
 *
 * Per session:
 *   1. cut the transcript into token-sized chunks with overlap, embed every chunk and store it
 *   2. Gemma extracts facts from each chunk, as one JSON object per line (validated, retried, quote must occur in the chunk)
 *   3. every candidate fact is embedded, its 10 nearest known facts are fetched (no cut-off), and Gemma answers duplicate true/false as JSON
 *   4. false: the fact is committed (append-only, never overwrites). true: the repeat is recorded against the matching fact.
 * Steps 3 and 4 run one fact at a time, so a fact committed from chunk 2 is already visible when chunk 3's repeat of it is checked.
 */
class MemoryLab(private val context: Context, private val useJobFile: Boolean = false) {
    private val root = MemoryFiles.devRoot(context)
    private val diag: File? = if (useJobFile) File(root, "out").apply { mkdirs() } else null
    private val prompts = File(root, "prompts")
    private lateinit var embedder: Embedder
    private lateinit var store: MemoryStore

    private class Job(o: JSONObject) {
        val backends = o.optJSONArray("backends")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: listOf("NPU", "GPU", "CPU")
        val maxTokens = o.optInt("maxTokens", 6144)
        val chunkTokens = o.optInt("chunkTokens", 1000)
        val overlapTokens = o.optInt("overlapTokens", 50)
        val neighbours = o.optInt("neighbours", 10)
        val stripLabels = o.optBoolean("stripLabels", false)
        val maxOutputTokens = o.optInt("maxOutputTokens", 1600)
        val extractTemperature = o.optDouble("extractTemperature", 0.1)
        val resetProfiles = o.optBoolean("resetProfiles", false)
        val benchPrefill = o.optBoolean("benchPrefill", false)
        val jobAttempts = o.optInt("jobAttempts", 3)
        val sessions: List<Pair<String, String>> = (o.optJSONArray("sessions") ?: JSONArray()).let { a ->
            (0 until a.length()).map { val s = a.getJSONObject(it); s.getString("file") to s.optString("date", "") }
        }
    }

    /** The session transcript from the vault; in debug-harness mode a transcript pushed to the developer root is imported into the vault first. */
    private fun transcript(file: String): String =
        SecureFile.readText(MemoryFiles.transcript(context, file), Vault.key())
            ?: if (useJobFile) File(root, "in/$file").readText().also { SecureFile.writeText(MemoryFiles.transcript(context, file), it, Vault.key()) }
            else throw FileNotFoundException(file)

    /** A developer copy in the dev root's prompts/ wins (edit without a rebuild); otherwise the prompt shipped in the app (demo/prompts). */
    private fun prompt(name: String) = File(prompts, name).takeIf { it.exists() }?.readText()?.trim() ?: context.assets.open("prompts/$name").bufferedReader().use { it.readText() }.trim()

    private fun fill(t: String, vars: Map<String, String>) = vars.entries.fold(t) { acc, (k, v) -> acc.replace("{{$k}}", v) }

    /**
     * The embedder rejects more than 1,024 tokens, and the words-to-tokens estimate used for chunking can be a few percent low. A text that is too
     * long is split in half by words, each half embedded, and the vectors averaged and renormalised, so a chunk never fails for its length.
     */
    private fun embed(text: String, depth: Int = 0): FloatArray = try {
        runBlocking { embedder.embedText(text, false, LogCtx.NONE) }
    } catch (t: Throwable) {
        val words = text.split(' ')
        if (depth >= 3 || words.size < 20 || t.message?.contains("exceeds maximum") != true) throw t
        val half = words.size / 2
        val a = embed(words.take(half).joinToString(" "), depth + 1)
        val b = embed(words.drop(half).joinToString(" "), depth + 1)
        val m = FloatArray(a.size) { a[it] + b[it] }
        val norm = kotlin.math.sqrt(m.sumOf { (it * it).toDouble() }).toFloat().coerceAtLeast(1e-9f)
        FloatArray(m.size) { m[it] / norm }
    }

    /** Runs the whole queue: clients in order, each client's sessions in date order. Safe to call again after a stop or crash (resume). */
    fun run() {
        // job.json is optional: the debug harness uses it for a fixed list and settings; in the app the queue is fed by MemoryProcessor.
        val settings = File(root, "job.json")
        // Only a debug launch reads it: a leftover file from a test run must never reset clients or re-run old sessions in the real app.
        val job = Job(if (useJobFile && settings.exists()) JSONObject(settings.readText()) else JSONObject())
        val bench = diag?.let { File(it, "bench.jsonl") }
        MemoryQueue.bind(MemoryFiles.queue(context))
        if (!ModelSetup.isReady(context)) {
            // The service ends; the download worker calls MemoryProcessor.resumePending when the files are complete.
            MemoryQueue.restore()
            MemoryQueue.update { it.copy(running = false, message = "Waiting for setup") }
            return
        }
        val fresh = job.sessions.map { (file, date) -> SessionJob(file.removeSuffix(".md"), file.substringBefore('_'), file, date) }
            .sortedWith(compareBy({ it.client }, { it.date }))
        MemoryQueue.cancelRequested = false
        MemoryQueue.load(fresh, resume = !job.resetProfiles)
        MemoryQueue.update { it.copy(running = true, message = "Loading models") }
        store = MemoryStore(context)
        if (job.resetProfiles) MemoryQueue.state.value.clients.forEach { store.clearClient(it) }
        val llm = MemoryLlm(context, job.maxTokens)
        // MemoryLab runs Gemma 2B on GPU; forcing Embedder to CPU avoids concurrent GPU allocation OOMs on mobile GPUs.
        embedder = Embedder(context, forcedBackend = "CPU")
        try {
            llm.load(job.backends)
            runBlocking { embedder.load() }
            MemoryQueue.update { it.copy(backend = llm.backend, message = "Running") }
            if (job.benchPrefill) benchPrefill(llm, job, bench)
            // Jobs can be added while this runs, so take the next Queued one each time instead of iterating a snapshot.
            MemoryQueue.state.value.clients.forEach { publishProfile(it) }
            while (true) {
                val item = MemoryQueue.state.value.jobs.firstOrNull { it.status == JobStatus.Queued } ?: break
                if (MemoryQueue.cancelRequested) {
                    MemoryQueue.updateJob(item.id) { it.copy(status = JobStatus.Cancelled, stage = "stopped") }
                    continue
                }
                runWithRetries(llm, job, item, bench)
                publishProfile(item.client)
            }
        } catch (t: Throwable) {
            MemoryQueue.update { it.copy(message = "Failed: ${t.message}") }
            throw t
        } finally {
            llm.close()
            embedder.close()
            store.close()
            MemoryQueue.update { it.copy(running = false, message = if (MemoryQueue.cancelRequested) "Stopped" else if (it.message.startsWith("Failed")) it.message else "Finished") }
        }
        diag?.let { File(it, "DONE").writeText("ok") }
    }

    private fun publishProfile(client: String) {
        val facts = store.facts(client)
        MemoryQueue.update { it.copy(profiles = it.profiles + (client to facts)) }
    }

    /** A session that throws is retried (chunks already extracted are cached, so only missing work is redone) before it is marked Failed. */
    private fun runWithRetries(llm: MemoryLlm, job: Job, item: SessionJob, bench: File?) {
        MemoryQueue.updateJob(item.id) { it.copy(status = JobStatus.Running, stage = "Waiting", phase = JobPhase.None, step = 0, steps = 0, startedAt = System.currentTimeMillis(), error = null) }
        var last: Throwable? = null
        for (attempt in 1..job.jobAttempts) {
            try {
                if (item.recordingId != 0L && !MemoryFiles.transcript(context, item.file).exists()) {
                    if (!transcribeRecording(item)) {
                        MemoryQueue.updateJob(item.id) { it.copy(status = JobStatus.Failed, error = "No speech was recorded", stage = "", finishedAt = System.currentTimeMillis()) }
                        return
                    }
                }
                processSession(llm, job, item, transcript(item.file), bench)
                MemoryQueue.updateJob(item.id) { it.copy(status = if (it.stage == "stopped") JobStatus.Cancelled else JobStatus.Done, finishedAt = System.currentTimeMillis()) }
                return
            } catch (t: Throwable) {
                last = t
                AppLog.e(MemoryLlm.TAG, "session_attempt_failed", LogCtx.NONE, t, "session" to item.id, "attempt" to attempt)
                MemoryQueue.updateJob(item.id) { it.copy(stage = "Retrying (${attempt + 1}/${job.jobAttempts})", phase = JobPhase.Retrying, step = attempt + 1, steps = job.jobAttempts) }
            }
        }
        MemoryQueue.updateJob(item.id) { it.copy(status = JobStatus.Failed, error = last?.message ?: last?.javaClass?.simpleName, finishedAt = System.currentTimeMillis()) }
    }

    private fun log(bench: File?, stage: String, sid: String, part: Int, r: LlmReply?, extra: Map<String, Any?> = emptyMap()) {
        val o = JSONObject().put("stage", stage).put("session", sid).put("part", part)
        if (r != null) {
            val s = r.stats
            o.put("backend", s.backend).put("prompt_tokens", s.promptTokens).put("prefill_tok_s", s.prefillTokPerSec).put("ttft_s", s.ttftSec)
                .put("decode_tokens", s.decodeTokens).put("decode_tok_s", s.decodeTokPerSec).put("wall_ms", s.wallMs)
        }
        extra.forEach { (k, v) -> o.put(k, v) }
        bench?.appendText(o.toString() + "\n")
    }

    private fun processSession(llm: MemoryLlm, job: Job, item: SessionJob, raw: String, bench: File?) {
        val client = item.client
        val sid = item.id
        val text = if (job.stripLabels) TranscriptWindows.stripSpeakerLabels(raw) else raw
        val chunks = TranscriptWindows.splitByTokens(text, job.chunkTokens, job.overlapTokens)
        val cacheDir = MemoryFiles.extractCache(context, sid).apply { mkdirs() }
        MemoryQueue.updateJob(sid) { it.copy(windows = chunks.size, windowsDone = 0, extracted = 0, added = 0, stage = "Reading 1/${chunks.size}", phase = JobPhase.Reading, step = 1, steps = chunks.size) }

        // 1 + 2: embed and store every chunk, extract candidate facts from it.
        if (item.date.isNotEmpty()) store.putSession(client, sid, item.date)
        val candidates = ArrayList<Fact>()
        chunks.forEachIndexed { i, chunk ->
            if (MemoryQueue.cancelRequested) return stopped(sid)
            store.putChunk(client, sid, i + 1, chunk, embed(chunk))
            val cache = File(cacheDir, "${i + 1}.json")
            val facts = if (cache.exists()) readFacts(cache) else extractChunk(llm, job, item, chunk, i + 1, chunks.size, bench).also { SecureFile.writeText(cache, JSONArray(it.map { f -> f.toJson() }).toString(1), Vault.key()) }
            candidates += facts
            MemoryQueue.updateJob(sid) { it.copy(windowsDone = i + 1, extracted = candidates.size, stage = if (i + 1 < chunks.size) "Reading ${i + 2}/${chunks.size}" else "Checking 0/${candidates.size}",
                phase = if (i + 1 < chunks.size) JobPhase.Reading else JobPhase.Checking, step = if (i + 1 < chunks.size) i + 2 else 0, steps = if (i + 1 < chunks.size) chunks.size else candidates.size) }
        }

        // 3 + 4: review each candidate against its 10 nearest known facts, sequentially.
        var added = 0
        var repeats = 0
        var next = store.facts(client).size
        candidates.forEachIndexed { i, cand ->
            if (MemoryQueue.cancelRequested) return stopped(sid)
            val vec = embed(cand.fact)
            val near = store.nearest(client, vec, job.neighbours)
            val verdict = if (near.isEmpty()) Verdict(false, null, "empty store") else review(llm, cand, near, sid, i + 1, bench)
            if (verdict.duplicate && verdict.of != null) {
                store.addSupport(client, verdict.of.id, sid, cand.evidence, cand.chunk)
                repeats++
            } else {
                store.putFact(client, cand.copy(id = "F${++next}"), vec)
                added++
            }
            if ((i + 1) % 3 == 0 || i + 1 == candidates.size) MemoryQueue.updateJob(sid) { it.copy(stage = "Checking ${i + 1}/${candidates.size}", phase = JobPhase.Checking, step = i + 1, steps = candidates.size, added = added) }
        }
        MemoryQueue.updateJob(sid) { it.copy(stage = "Done", phase = JobPhase.None, step = 0, steps = 0, added = added) }
        AppLog.i(MemoryLlm.TAG, "session_done", LogCtx.NONE, "session" to sid, "chunks" to chunks.size, "candidates" to candidates.size, "added" to added, "repeats" to repeats)
    }

    /**
     * Turns the pending audio of a recording made before setup finished into the session transcript (always with Whisper: Gemma is busy as the memory LLM).
     * Chunks already stored by an earlier attempt are skipped, so a crash or retry only redoes the missing ones. Returns false if no speech was found.
     * The pending audio and the recording rows are deleted once the transcript is written (or found empty); on an error they are kept for the retry.
     */
    private fun transcribeRecording(item: SessionJob): Boolean {
        val rid = item.recordingId
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val asr = SherpaWhisperAsr(context, prefs.getString("whisper_size", "base") ?: "base", prefs.getString("language", "auto") ?: "auto")
        val store = Store(context)
        try {
            val audio = PendingAudio.load(context, rid)
            MemoryQueue.updateJob(item.id) { it.copy(stage = "Transcribing 0/${audio.size}", phase = JobPhase.Transcribing, step = 0, steps = audio.size) }
            runBlocking { asr.load() }
            val processor = ChunkProcessor(store, embedder, asr)
            val stored = store.chunks(rid).map { it.idx }.toSet()
            audio.forEachIndexed { i, chunk ->
                if (chunk.idx !in stored) runBlocking { processor.process(rid, chunk) }
                MemoryQueue.updateJob(item.id) { it.copy(stage = "Transcribing ${i + 1}/${audio.size}", phase = JobPhase.Transcribing, step = i + 1, steps = audio.size) }
            }
            val text = ChunkProcessor.transcript(store.chunks(rid))
            if (text.isNotBlank()) SecureFile.writeText(MemoryFiles.transcript(context, item.file), text, Vault.key())
            // Only now (transcript written, or nothing to write) is the source audio dropped; an exception above keeps it for the retry.
            PendingAudio.delete(context, rid)
            store.deleteRecording(rid)
            return text.isNotBlank()
        } finally {
            asr.close()
            store.close()
        }
    }

    private fun stopped(sid: String) {
        MemoryQueue.updateJob(sid) { it.copy(stage = "stopped") }
    }

    private fun readFacts(f: File): List<Fact> = JSONArray(SecureFile.readText(f, Vault.key()) ?: "[]").let { a -> (0 until a.length()).map { Fact.fromJson(a.getJSONObject(it)) } }

    /**
     * Facts of one chunk as validated objects. Up to three attempts: the model is asked for one JSON object per line twice; the last attempt uses
     * a constrained schema as a last resort. Facts whose quote does not occur in the chunk are dropped.
     */
    private fun extractChunk(llm: MemoryLlm, job: Job, item: SessionJob, chunk: String, idx: Int, total: Int, bench: File?): List<Fact> {
        val sys = prompt("extract.system.txt")
        val user = fill(
            prompt("extract.user.txt"),
            mapOf("CLIENT" to item.client.replaceFirstChar { it.uppercase() }, "DATE" to item.date, "PART" to "$idx", "PARTS" to "$total", "TEXT" to chunk),
        )
        var best = emptyList<Fact>()
        for (attempt in 1..3) {
            val constrained = attempt == 3
            try {
                val reply = llm.ask(sys, user, if (constrained) FactSchemas.EXTRACT_STRICT else null, job.maxOutputTokens, job.extractTemperature, 20)
                diag?.let { File(it, "raw").apply { mkdirs() }.resolve("${item.id}_extract_${idx}_$attempt.txt").writeText(reply.text) }
                val facts = FactParser.anyFacts(reply.text, "${item.id}c${idx}_", item.id, idx)
                val grounded = facts.filter { FactParser.evidenceHolds(it, chunk) }
                log(bench, "extract", item.id, idx, reply, mapOf("attempt" to attempt, "constrained" to constrained, "valid" to facts.size, "grounded" to grounded.size))
                MemoryQueue.updateJob(item.id) { it.copy(prefillTokPerSec = reply.stats.prefillTokPerSec, decodeTokPerSec = reply.stats.decodeTokPerSec) }
                if (grounded.size > best.size) best = grounded
                if (grounded.isNotEmpty()) return grounded
            } catch (t: Throwable) {
                AppLog.w(MemoryLlm.TAG, "extract_attempt_failed", LogCtx.NONE, "session" to item.id, "chunk" to idx, "attempt" to attempt, "reason" to t.message)
            }
        }
        return best
    }

    private class Verdict(val duplicate: Boolean, val of: Fact?, val note: String)

    /**
     * Asks Gemma for a true/false duplicate verdict as JSON (greedy), against the [near] known facts (always shown, whatever their score).
     * Asks for a JSON answer and validates it, up to 3 attempts; an unreadable answer counts as "not a duplicate",
     * so a model failure can never lose a fact. Every decision is logged with the neighbours it saw.
     */
    private fun review(llm: MemoryLlm, cand: Fact, near: List<Pair<Fact, Float>>, sid: String, n: Int, bench: File?): Verdict {
        val known = near.mapIndexed { i, (f, _) -> "K${i + 1}: ${f.fact}" }.joinToString("\n")
        val user = fill(prompt("review.user.txt"), mapOf("KNOWN" to known, "FACT" to cand.fact))
        var verdict: Verdict? = null
        var raw = ""
        var attempts = 0
        for (attempt in 1..3) {
            attempts = attempt
            try {
                // Not schema-constrained: turning constrained decoding on makes createConversation take about 4 s (measured), against 2 ms without it.
                val reply = llm.ask(prompt("review.system.txt"), user, null, 40, 0.1, 1)
                raw = reply.text
                val o = FactParser.json(raw)
                val dup = o?.opt("duplicate")
                val flag = when (dup) {
                    is Boolean -> dup
                    is String -> dup.equals("true", ignoreCase = true).takeIf { dup.equals("true", true) || dup.equals("false", true) }
                    else -> Regex("(?i)\\b(true|false)\\b").find(raw)?.value?.equals("true", true)
                }
                if (flag != null) {
                    val ref = o?.optString("of").orEmpty().trim().removePrefix("K").toIntOrNull()?.let { near.getOrNull(it - 1)?.first } ?: if (flag) near.first().first else null
                    verdict = Verdict(flag, ref, "ok")
                    if (attempt == 1 || n % 10 == 0) log(bench, "review", sid, n, reply, mapOf("attempt" to attempt))
                    break
                }
            } catch (t: Throwable) {
                raw = "error: ${t.message}"
            }
        }
        val v = verdict ?: Verdict(false, null, "unreadable after 3 attempts")
        diag?.let { File(it, "decisions.jsonl") }?.appendText(
            JSONObject().put("session", sid).put("n", n).put("fact", cand.fact).put("duplicate", v.duplicate).put("of", v.of?.fact ?: JSONObject.NULL)
                .put("note", v.note).put("attempts", attempts).put("raw", raw)
                .put("neighbours", JSONArray(near.map { (f, d) -> JSONObject().put("fact", f.fact).put("cos", "%.3f".format(d)) })).toString() + "\n",
        )
        return v
    }

    /** Prefill speed at growing prompt sizes: the slope is the real prefill rate, the intercept the fixed cost. */
    private fun benchPrefill(llm: MemoryLlm, job: Job, bench: File?) {
        val sample = transcript(job.sessions.first().first)
        val words = sample.split(Regex("\\s+")).filter { it.isNotEmpty() }
        llm.ask("Reply with OK.", words.take(100).joinToString(" "), null, 8) // warm-up, discarded
        for (n in listOf(200, 400, 800, 1200, 1600)) {
            val r = llm.ask("Reply with OK.", words.take(n).joinToString(" "), null, 8)
            log(bench, "bench_prefill", "bench", n, r, mapOf("words" to n))
        }
    }

    companion object {
        /** Starts the queue in the foreground service (survives leaving the app) unless it is already running. This is the debug entry (Memory tab, `memory_lab` intent): it reads job.json. */
        fun start(context: Context) = MemoryService.start(context.applicationContext, debugJob = true)
    }
}
