package com.therapylocal.app.engine.runner

import android.content.Context
import android.os.SystemClock
import com.k2fsa.sherpa.onnx.FastClusteringConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationSegment
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import com.therapylocal.app.asr.DiarTurn
import com.therapylocal.app.asr.Pcm
import com.therapylocal.app.engine.CallTag
import com.therapylocal.app.engine.EngineKind
import com.therapylocal.app.engine.EngineOp
import com.therapylocal.app.engine.EngineReply
import com.therapylocal.app.engine.PhaseSink
import com.therapylocal.app.log.AppLog
import com.therapylocal.app.log.LogCtx
import com.therapylocal.app.models.Accelerators
import com.therapylocal.app.models.ModelRegistry

/**
 * The native half of speaker diarization (docs/AD/AD-06.md) in the `:batch` host: sherpa-onnx `OfflineSpeakerDiarization` with pyannote
 * segmentation-3.0 and a WeSpeaker embedding, run on the CPU. One call takes a whole recording: the PCM16 arrives through shared memory and is
 * widened to float here, so main never holds the 4-byte-per-sample copy (an hour is 230 MB as float, 115 MB as PCM16). It logs counts and times
 * only, never audio. The file completeness check needs no native code and runs in main before the ladder.
 */
internal class DiarizeRunner(private val context: Context) : DiarizeEngine {
    override val kind = EngineKind.DIARIZE
    override var model: String = ""
        private set
    override var backend: String = "none"
        private set
    private var diarizer: OfflineSpeakerDiarization? = null

    /** One ladder attempt: create the diarizer on [op].backend and run it over silence. One that fails is released here. */
    fun open(op: EngineOp.Open, phases: PhaseSink): EngineReply.Opened {
        val spec = ModelRegistry.byId(op.model)
        val name = op.backend
        val f = callFields(op.tag)
        val onnx = Accelerators.get(name)?.onnxProvider ?: name.lowercase()
        val config = config(
            segmentation = spec.path(context, ModelRegistry.DIAR_SEGMENTATION_FILE),
            embedding = spec.path(context, ModelRegistry.DIAR_EMBEDDING_FILE),
            provider = onnx, numSpeakers = 0,
        )
        notePhase(phases, op.tag, TAG, op.guardModel, name, "create")
        val d = AppLog.span(TAG, "diarize_create", LogCtx.NONE, "engine" to ENGINE, "backend_try" to name, "onnx_provider" to onnx, "threads" to THREADS, *f) {
            OfflineSpeakerDiarization(config = config)
        }
        try {
            notePhase(phases, op.tag, TAG, op.guardModel, name, "test")
            selfTest(name, d, f)
        } catch (t: Throwable) {
            runCatching { d.release() }
            throw t
        }
        diarizer = d
        model = spec.id
        backend = name
        return EngineReply.Opened(0, name, 0, null)
    }

    /** Runs [SELFTEST_SECONDS] of silence: a provider can construct fine and fail at the first run, and silence must give no turns. */
    private fun selfTest(name: String, d: OfflineSpeakerDiarization, f: Fields) {
        AppLog.span(TAG, "diarize_selftest", LogCtx.NONE, "engine" to ENGINE, "backend_try" to name, *f) { span ->
            check(d.sampleRate() == Pcm.SAMPLE_RATE) { "diarizer wants ${d.sampleRate()} Hz, audio is ${Pcm.SAMPLE_RATE} Hz" }
            val turns = d.process(FloatArray(SELFTEST_SECONDS * Pcm.SAMPLE_RATE))
            span.put("turns", turns.size)
            check(turns.isEmpty()) { "silence gave ${turns.size} turns" }
        }
    }

    override fun diarize(op: EngineOp.Diarize): EngineReply.Turns {
        val d = checkNotNull(diarizer) { "diarizer not loaded" }
        val audioSec = Pcm.seconds(op.pcm)
        val start = SystemClock.elapsedRealtime()
        val turns = AppLog.span(
            TAG, "diarize", LogCtx.NONE, "engine" to ENGINE, "model" to model, "backend" to backend, "audio_sec" to "%.2f".format(audioSec),
            "speakers_hint" to op.numSpeakersHint, *callFields(op.tag, op.handle),
        ) { span ->
            val samples = Pcm.toFloats(op.pcm)
            // A fixed count replaces the threshold for this call only; the clustering is the one part of the config that can change on a loaded model.
            d.setConfig(d.config.copy(clustering = clustering(op.numSpeakersHint)))
            val out = toTurns(d.process(samples))
            span.put("turns", out.size)
            span.put("speakers", out.map { it.speaker }.distinct().size)
            span.put("wall_ms", SystemClock.elapsedRealtime() - start)
            out
        }
        return EngineReply.Turns(turns)
    }

    override fun close(tag: CallTag) {
        val d = diarizer ?: return
        diarizer = null
        val f = callFields(tag)
        runCatching { d.release() }.onFailure { AppLog.e(TAG, "diarize_release_failed", LogCtx.NONE, it, "engine" to ENGINE, *f) }
        AppLog.i(TAG, "diarize_unloaded", LogCtx.NONE, "engine" to ENGINE, "model" to model, "backend" to backend, *f)
        backend = "none"
    }

    companion object {
        const val TAG = "Diarize"
        private const val ENGINE = "pyannote"
        private const val THREADS = 4
        private const val SELFTEST_SECONDS = 2

        /** Agglomerative clustering cut when the speaker count is unknown: the value sherpa-onnx's own diarization examples use (a lower value splits one voice into several). */
        const val CLUSTER_THRESHOLD = 0.5f

        /** Turns shorter than this are dropped (a cough, a click); a gap shorter than [MIN_DURATION_OFF_S] between two turns of one speaker joins them. */
        const val MIN_DURATION_ON_S = 0.2f
        const val MIN_DURATION_OFF_S = 0.5f

        /** `numClusters` -1 means "use the threshold" in sherpa-onnx. */
        internal fun clustering(numSpeakersHint: Int) =
            if (numSpeakersHint > 0) FastClusteringConfig(numClusters = numSpeakersHint, threshold = CLUSTER_THRESHOLD)
            else FastClusteringConfig(numClusters = -1, threshold = CLUSTER_THRESHOLD)

        internal fun config(segmentation: String, embedding: String, provider: String, numSpeakers: Int) = OfflineSpeakerDiarizationConfig(
            segmentation = OfflineSpeakerSegmentationModelConfig(
                pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(model = segmentation),
                numThreads = THREADS, debug = false, provider = provider,
            ),
            embedding = SpeakerEmbeddingExtractorConfig(model = embedding, numThreads = THREADS, debug = false, provider = provider),
            clustering = clustering(numSpeakers),
            minDurationOn = MIN_DURATION_ON_S,
            minDurationOff = MIN_DURATION_OFF_S,
        )

        /** Seconds to milliseconds, sorted by start (then end), a turn never shorter than 1 ms. */
        internal fun toTurns(segments: Array<OfflineSpeakerDiarizationSegment>): List<DiarTurn> =
            segments.map { s ->
                val a = Math.round(s.start * 1000.0)
                DiarTurn(a, maxOf(Math.round(s.end * 1000.0), a + 1), s.speaker)
            }.sortedWith(compareBy({ it.startMs }, { it.endMs }))
    }
}
