package com.therapylocal.app.jobs

import com.therapylocal.app.asr.SpeakerDiarizer
import com.therapylocal.app.audio.AudioChunk
import com.therapylocal.app.config.ChunkConfig
import com.therapylocal.app.config.MemoryConfig
import com.therapylocal.app.data.AudioHash
import com.therapylocal.app.data.ChunkAudio
import com.therapylocal.app.data.ChunkRow
import com.therapylocal.app.data.Piece
import com.therapylocal.app.data.QuietCut
import com.therapylocal.app.data.SegmentBuilder
import com.therapylocal.app.data.SegmentRow
import com.therapylocal.app.data.TimedTurn
import com.therapylocal.app.data.TurnPlanner
import com.therapylocal.app.data.TurnRow
import com.therapylocal.app.data.UtteranceBuilder
import com.therapylocal.app.engine.EngineDied
import com.therapylocal.app.engine.EngineUnavailable
import com.therapylocal.app.log.AppLog
import com.therapylocal.app.log.LogCtx
import com.therapylocal.app.perf.StageLog
import com.therapylocal.app.workflow.TurnListener
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.security.GeneralSecurityException
import kotlin.coroutines.cancellation.CancellationException

/** What the transcribe step reads and writes, so it can run on a test double (the app passes the [com.therapylocal.app.data.MindStore]). */
interface TranscribeStore {
    fun chunks(recordingId: Long): List<ChunkRow>
    fun putChunk(c: ChunkRow)
    fun turns(recordingId: Long): List<TurnRow>
    fun replaceTurns(recordingId: Long, turns: List<TurnRow>)
    fun setTurnText(recordingId: Long, idx: Int, text: String, textEn: String? = null)
    /** Stores the next transcript version (docs/AD/AD-10.md); the same content as the current one writes nothing. [kind] is the store's transcript kind. */
    fun appendTranscript(session: String, segments: List<SegmentRow>, recordingId: Long?, audioSha: String?, kind: String = "spoken")
    /** Stores the English text of the segments of the current transcript, by segment index, as written by [model] with [promptVersion] (the TRANSLATE job skips those). */
    fun putEnglish(session: String, english: Map<Int, String>, model: String, promptVersion: String) {}
}

/** What the turns are put through (docs/AD/AD-22.md). */
enum class TurnMode {
    /** Canary writes the text in the language spoken; the TRANSLATE job makes the English (AD-17). */
    Spoken,

    /** Fast: Gemma hears the turn and writes the text in the language spoken and the English in one answer. */
    Listen,

    /** Quality: Whisper writes English from the audio. For a client who does not speak English there is no text in their language. */
    EnglishOnly,
}

/**
 * The TRANSCRIBE job (docs/AD/AD-06.md): diarize the whole recording, cut it at the turn boundaries, transcribe each turn with Canary and join
 * neighbouring turns of one speaker into utterances (the stored segments). If the recording cannot be diarized the job takes today's chunk path
 * (Canary per 20 s chunk, the overlaps stitched, speaker unknown); diarization never fails the job.
 *
 * Resumable: the turns are stored before the first is transcribed and each text as it comes, so a restarted job reuses both. A recording whose
 * chunks already have text was on the chunk path in an earlier run and stays on it.
 */
class TranscribeFlow(
    private val store: TranscribeStore,
    /** The indices of a recording's saved chunks, in order. */
    private val chunkFiles: (Long) -> List<Int>,
    /** One saved chunk, null if it is missing; throws if it cannot be read. */
    private val chunk: (Long, Int) -> AudioChunk?,
    /** True when the diarization model is on the phone. An existing install without it still processes sessions, on the chunk path. */
    private val diarizationReady: () -> Boolean = { true },
    /** How the turns of a client speaking this language are put through; [TurnMode.Spoken] is the Canary path. */
    private val mode: (clientLanguage: String) -> TurnMode = { TurnMode.Spoken },
    /** The workflow key, for the stage lines of the log. */
    private val workflow: () -> String = { "canary" },
) {
    /** How a run ended: [skipped] is the `diarization_skipped` reason when the chunk path was taken, null when the recording was diarized or had nothing to do. */
    data class Outcome(val diarized: Boolean, val skipped: String? = null)

    /** Deaths of the diarizer host met per recording, in this process: the first is retried (the connection strikes the backend), later ones fall back. */
    private val diarizerDeaths = HashMap<Long, Int>()

    fun run(rid: Long, session: String, language: String, run: JobContext): Outcome {
        val rows = store.chunks(rid)
        // A recording without chunks (gone, or too short to have any) leaves the segments as they are.
        if (rows.isEmpty() && chunkFiles(rid).isEmpty()) return Outcome(false)
        val audio = ChunkAudio(rows) { idx -> readChunk(rid, idx) }
        if (store.turns(rid).isNotEmpty()) return Outcome(true).also { byTurns(rid, session, language, audio, run) }
        if (rows.any { it.text != null }) return Outcome(false).also { byChunks(rid, session, language, run) }
        val reason = diarize(rid, session, audio, run, mode(language))
        // Fast and Quality have no chunk path (nothing there could hear the audio): a recording that cannot be diarized fails the job.
        if (reason != null && mode(language) != TurnMode.Spoken) throw JobFailure("DIARIZE_" + reason.uppercase())
        if (reason == null) byTurns(rid, session, language, audio, run) else byChunks(rid, session, language, run)
        return Outcome(reason == null, reason)
    }

    /** The hash of the audio this transcript is made from, null when it cannot be read: it is a reference to the input, never a reason to fail. */
    private fun audioHash(rid: Long): String? = try {
        AudioHash.of(chunkFiles(rid)) { idx -> chunk(rid, idx) }
    } catch (_: IOException) {
        null
    } catch (_: GeneralSecurityException) {
        null
    }

    private fun readChunk(rid: Long, idx: Int): AudioChunk = try {
        chunk(rid, idx)
    } catch (_: IOException) {
        null
    } catch (_: GeneralSecurityException) {
        null
    } ?: throw JobFailure("AUDIO")

    /** Today's path: the chunks that have no text yet, one loaded at a time, then the segments from all of them. */
    private fun byChunks(rid: Long, session: String, language: String, run: JobContext) {
        val done = store.chunks(rid).filter { it.text != null }.map { it.idx }.toSet()
        val todo = chunkFiles(rid).filter { it !in done }
        run.progress(JobPhase.Transcribing, 0, todo.size)
        todo.forEachIndexed { i, idx ->
            run.checkStop()
            val c = readChunk(rid, idx)
            store.putChunk(ChunkRow(rid, c.idx, c.startMs, c.endMs, c.overlapMs, run.pool.asr(language).transcribe(c.pcm)))
            run.progress(JobPhase.Transcribing, i + 1, todo.size)
        }
        val chunks = store.chunks(rid)
        if (chunks.isNotEmpty()) store.appendTranscript(session, SegmentBuilder.build(chunks), rid, audioHash(rid))
    }

    /** The turns without text, each cut from the saved chunks (the whole recording is not held while Canary is loaded), then the utterances. */
    private fun byTurns(rid: Long, session: String, language: String, audio: ChunkAudio, run: JobContext) {
        val pending = store.turns(rid).filter { it.text == null }
        val m = mode(language)
        val t0 = System.nanoTime()
        var audioMs = 0L
        run.progress(JobPhase.Transcribing, 0, pending.size)
        pending.forEachIndexed { i, t ->
            run.checkStop()
            val pcm = audio.slice(t.startMs, t.endMs)
            audioMs += t.endMs - t.startMs
            val turn0 = System.nanoTime()
            when (m) {
                TurnMode.Listen -> TurnListener.listen(run.pool.llm(), pcm, language).let { store.setTurnText(rid, t.idx, it.source, it.english) }
                TurnMode.EnglishOnly -> run.pool.asr(language).transcribe(pcm).trim().let { store.setTurnText(rid, t.idx, it, if (language.equals("en", true)) null else it) }
                TurnMode.Spoken -> store.setTurnText(rid, t.idx, run.pool.asr(language).transcribe(pcm).trim())
            }
            // The turn's own line (counts and times, no text): the spread of the per-turn times is what a slow phone shows first.
            AppLog.i(StageLog.TAG, "turn_done", LogCtx(rid), "workflow" to workflow(), "turn" to t.idx, "audio_ms" to (t.endMs - t.startMs), "wall_ms" to (System.nanoTime() - turn0) / 1_000_000)
            run.progress(JobPhase.Transcribing, i + 1, pending.size)
        }
        if (pending.isNotEmpty()) {
            StageLog.done(if (m == TurnMode.Listen) "listen" else "transcribe", workflow(), session, (System.nanoTime() - t0) / 1_000_000, "turns" to pending.size, "audio_sec" to "%.1f".format(audioMs / 1000.0))
        }
        val turns = store.turns(rid)
        val pieces = turns.map { Piece(it.startMs, it.endMs, it.text.orEmpty(), it.speaker, it.textEn) }
        val englishOnly = m == TurnMode.EnglishOnly && !language.equals("en", true)
        store.appendTranscript(session, UtteranceBuilder.build(pieces), rid, audioHash(rid), if (englishOnly) "english_only" else "spoken")
        // Fast and Quality have the English already: stored under the model and prompt that wrote it, so the TRANSLATE job leaves those segments alone.
        if (m != TurnMode.Spoken && !language.equals("en", true)) {
            val model = if (m == TurnMode.Listen) run.pool.llmModel() else com.therapylocal.app.models.ModelRegistry.WHISPER_LARGE_V3_Q8.id
            store.putEnglish(session, UtteranceBuilder.english(pieces), model, if (m == TurnMode.Listen) TurnListener.VERSION else "whisper-translate")
        }
    }

    /**
     * Diarizes and stores the planned turns. Null when that worked, else the reason it was skipped. The recording's PCM lives only inside this
     * function: it is gone before Canary is asked for.
     */
    private fun diarize(rid: Long, session: String, audio: ChunkAudio, run: JobContext, m: TurnMode): String? {
        val ctx = LogCtx(rid)
        fun skipped(reason: String, error: Throwable? = null): String {
            val fields = arrayOf<Pair<String, Any?>>("reason" to reason, "type" to error?.javaClass?.simpleName)
            if (error == null) AppLog.i(SpeakerDiarizer.TAG, "diarization_skipped", ctx, *fields) else AppLog.w(SpeakerDiarizer.TAG, "diarization_skipped", ctx, *fields)
            return reason
        }
        val durationMs = audio.durationMs
        if (durationMs * ChunkConfig.SAMPLE_RATE / 1000 > SpeakerDiarizer.MAX_SAMPLES) return skipped(TOO_LONG)
        if (!diarizationReady()) return skipped(MODEL_MISSING)
        run.checkStop()
        val pcm = try {
            audio.slice(0, durationMs)
        } catch (_: OutOfMemoryError) {
            return skipped(FAILED)
        }
        val d0 = System.nanoTime()
        val turns = try {
            val diarizer = run.pool.diarizer()
            runBlocking { diarizer.diarize(pcm, MemoryConfig.DIARIZE_SPEAKERS_HINT, ctx) }
        } catch (t: Throwable) {
            // A stop, a cancel, no host to serve, or a new model build go back to the queue as they do for every other job.
            if (t is JobStopped || t is CancellationException || t is EngineUnavailable || t is ModelSwitched) throw t
            if (t is EngineDied && diarizerDeaths.merge(rid, 1, Int::plus)!! < MemoryConfig.DIARIZE_DEATHS_BEFORE_FALLBACK) throw t
            if (t is Error && t !is OutOfMemoryError) throw t
            return skipped(FAILED, t)
        }
        val planned = TurnPlanner.plan(turns.map { TimedTurn(it.startMs, it.endMs, "S${it.speaker}") }, durationMs) { s, e -> QuietCut.at(pcm, s, e) }
        StageLog.done("diarize", workflow(), session, (System.nanoTime() - d0) / 1_000_000, "turns" to planned.size, "raw_turns" to turns.size, "speakers" to planned.map { it.speaker }.distinct().size, "audio_sec" to "%.1f".format(durationMs / 1000.0))
        // Fast and Quality work per turn whatever the speaker count; only the Canary path falls back to chunks for a single voice.
        if (m == TurnMode.Spoken && planned.map { it.speaker }.distinct().size < 2) return skipped(ONE_SPEAKER)
        store.replaceTurns(rid, planned.mapIndexed { i, t -> TurnRow(i, t.startMs, t.endMs, t.speaker) })
        return null
    }

    companion object {
        const val MODEL_MISSING = "model_missing"
        const val FAILED = "failed"
        const val TOO_LONG = "too_long"
        const val ONE_SPEAKER = "one_speaker"
    }
}
