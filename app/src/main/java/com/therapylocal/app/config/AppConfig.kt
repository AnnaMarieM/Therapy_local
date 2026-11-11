package com.therapylocal.app.config

/**
 * The ONE place that defines how a recording is cut into chunks. Nothing else may hardcode these numbers:
 * the recorder, the DB offsets, the trace logs and the UI text all read from here.
 *
 * Chunk i covers [i * STEP_SECONDS, i * STEP_SECONDS + CHUNK_SECONDS], so consecutive chunks overlap by OVERLAP_SECONDS.
 */
object ChunkConfig {
    const val SAMPLE_RATE = 16_000

    /** Length of a chunk. Must stay under Gemma's 30 s audio cap. */
    const val CHUNK_SECONDS = 20

    /** Audio shared by two consecutive chunks. */
    const val OVERLAP_SECONDS = 5

    /** New audio per chunk. */
    const val STEP_SECONDS = CHUNK_SECONDS - OVERLAP_SECONDS

    /** The trailing chunk is kept only if it adds at least this much audio that no earlier chunk covers. */
    const val MIN_NEW_SECONDS = 2

    /** A recording shorter than this produces no chunk at all. */
    const val MIN_RECORDING_SECONDS = 1

    /** Voice search: one clip, no overlap. */
    const val QUERY_MAX_SECONDS = 20
    const val QUERY_MIN_MS = 400

    /** A recording does not start with less free internal storage: its audio is kept until processed, about 150 MB per hour with the overlap. */
    const val MIN_FREE_BYTES = 300L shl 20

    init {
        require(OVERLAP_SECONDS in 0 until CHUNK_SECONDS) { "OVERLAP_SECONDS must be smaller than CHUNK_SECONDS" }
    }

    const val CHUNK_SAMPLES = CHUNK_SECONDS * SAMPLE_RATE
    const val OVERLAP_SAMPLES = OVERLAP_SECONDS * SAMPLE_RATE
    const val STEP_SAMPLES = STEP_SECONDS * SAMPLE_RATE
    const val MIN_NEW_SAMPLES = MIN_NEW_SECONDS * SAMPLE_RATE
    const val MIN_RECORDING_SAMPLES = MIN_RECORDING_SECONDS * SAMPLE_RATE
    const val QUERY_MAX_SAMPLES = QUERY_MAX_SECONDS * SAMPLE_RATE
    const val QUERY_MIN_SAMPLES = QUERY_MIN_MS * SAMPLE_RATE / 1000

    const val CHUNK_MS = CHUNK_SECONDS * 1000L
    const val OVERLAP_MS = OVERLAP_SECONDS * 1000L
    const val STEP_MS = STEP_SECONDS * 1000L

    fun startMs(idx: Int): Long = idx * STEP_MS

    /** Text for the UI, for example "20 s chunks, 5 s overlap". */
    fun describe(): String = "$CHUNK_SECONDS s chunks, $OVERLAP_SECONDS s overlap"
}

/** Embedding model identity and vector size, one place. EmbeddingGemma 2 maps text and audio into one 768-d space. */
object EmbeddingConfig {
    /** Stored on every window row; vectors are only compared within one model id. */
    const val MODEL_ID = "embeddinggemma-2-740m"

    /** Native size of the model. Matryoshka truncation to 512, 256 or 128 is possible by changing this one value. */
    const val DIM = 768

    /** Input length the embedder asks for: the model's full context (one of its LiteRT input sizes 128..8192), lowered to the context it reports if that is smaller. */
    const val MAX_TOKENS = 8192

    /** Audio fills the shared context at this rate (google/embeddinggemma-2 model card, "Context Limits": 25 tokens per second, ~327 s at 8,192). */
    const val AUDIO_TOKENS_PER_SECOND = 25

    /** Kept free for the model's own special tokens around the audio. */
    const val AUDIO_MARGIN_TOKENS = 16

    /**
     * The input sizes the LiteRT build of the model has (128..8192, powers of two; the engine is opened with one of them and its buffers are
     * sized for it, see AD-08). The smallest one that holds what is needed is used, not the full 8192.
     */
    val INPUT_SIZES = listOf(128, 256, 512, 1024, 2048, 4096, 8192)

    fun inputSizeFor(tokens: Int): Int = INPUT_SIZES.firstOrNull { it >= tokens } ?: MAX_TOKENS

    /** Tokens [seconds] of audio take, with the margin for the special tokens: the inverse of [maxAudioSeconds]. */
    fun audioTokens(seconds: Int) = seconds * AUDIO_TOKENS_PER_SECOND + AUDIO_MARGIN_TOKENS

    /**
     * The live (audio) embedder's input size for a lookback of [lookbackS] seconds (Settings, "Knobs for nerds"): it must also hold a voice
     * search query, which is up to [ChunkConfig.QUERY_MAX_SECONDS] long. The default 20 s needs 516 tokens, so 1024.
     */
    fun liveInputTokens(lookbackS: Int) = inputSizeFor(audioTokens(maxOf(lookbackS, ChunkConfig.QUERY_MAX_SECONDS)))

    /**
     * The pipeline's text embedder (`:batch`): a transcript window is `MemoryConfig.WINDOW_TOKENS` (1000) estimated tokens plus 50 of overlap,
     * the estimate can be a few percent low (Czech more) and a fact or a search query is a sentence. 2048 holds all of them; a window that
     * still does not fit is split by `Pipeline.embedFitting`, so this is a size, not a limit.
     */
    const val PIPELINE_INPUT_TOKENS = 2048

    /** Longest audio clip that fits [tokens] of context. */
    fun maxAudioSeconds(tokens: Int) = ((tokens - AUDIO_MARGIN_TOKENS) / AUDIO_TOKENS_PER_SECOND).coerceAtLeast(0)

    const val QUERY_PREFIX = "task: search query | text: "
    const val DOC_PREFIX = "task: search result | text: "
}

/** The background pipeline that turns a session's transcript into facts (see [com.therapylocal.app.jobs.Pipeline]). */
object MemoryConfig {
    const val LLM_MAX_TOKENS = 6144

    /** Transcript windows, in estimated tokens: each is embedded for search and read by the LLM once. */
    const val WINDOW_TOKENS = 1000
    const val WINDOW_OVERLAP_TOKENS = 50

    const val EXTRACT_MAX_OUTPUT_TOKENS = 1600
    const val EXTRACT_TEMPERATURE = 0.1
    const val EXTRACT_TOP_K = 20

    /** The duplicate review answers with a short verdict, as deterministically as the sampler allows. */
    const val REVIEW_MAX_OUTPUT_TOKENS = 40
    const val REVIEW_TEMPERATURE = 0.1
    const val REVIEW_TOP_K = 1

    /**
     * How many speakers the diarizer is told to find in a recording (docs/AD/AD-07.md): always two, a therapist and a patient. The app does not
     * record couples or family sessions, and a fixed count cannot split one voice into a spurious third speaker. A one-voice recording therefore
     * gets two speakers too (accepted in AD-07). 0 would let the clustering threshold decide. Changing it needs a new decision.
     */
    const val DIARIZE_SPEAKERS_HINT = 2

    /** A diarizer host that dies this many times under one recording's transcribe job stops being retried: the job takes the chunk path. */
    const val DIARIZE_DEATHS_BEFORE_FALLBACK = 2

    /** The role guess (job ROLES) reads the start of the transcript and answers with a small JSON object. */
    const val ROLES_INPUT_TOKENS = 4000
    const val ROLES_MAX_OUTPUT_TOKENS = 250
    const val ROLES_TEMPERATURE = 0.1
    const val ROLES_TOP_K = 1

    /** Changes with the meaning of the role prompt or its schema; logged with the model id next to the prompt's text hash. */
    const val ROLES_PROMPT_VERSION = 1

    /** Known facts shown to the duplicate review. */
    const val NEIGHBOURS = 10

    /** A candidate with no earlier fact at least this close is new without asking the LLM. Our own choice, not calibrated yet. */
    const val MIN_SIMILARITY = 0.6f
}

/** Debug override of which accelerator the engines try: "auto" (the [com.therapylocal.app.models.Accelerators] order) or one accelerator id. */
object BackendPolicy {
    @Volatile
    var override: String = "auto"

    /** "auto" in any case, and a blank override, which names no accelerator. */
    private fun isAuto(override: String) = override.isBlank() || override.trim().equals("auto", ignoreCase = true)

    /**
     * Debug/QA only, never set by a release path: with a specific accelerator in [override] and this on, the engines skip the free-memory
     * gate and the guard's block and demotion and try exactly that accelerator. The engine process then survives or dies and main goes on
     * (docs/AD/AD-01.md). A library that is missing still refuses it: it could not run.
     */
    @Volatile
    var force: Boolean = false

    fun forcing() = force && !isAuto(override)

    fun allows(backend: String) = isAuto(override) || override.trim().equals(backend, ignoreCase = true)

    /**
     * The order to try: [preferred] as it is on "auto", otherwise the forced accelerator first. The fallback (CPU) stays last whenever
     * [preferred] has it, so forcing one the phone cannot run falls back instead of failing.
     */
    fun order(preferred: List<String>, override: String = this.override): List<String> {
        if (isAuto(override)) return preferred
        val forced = override.trim().uppercase()
        val fallback = preferred.filter { com.therapylocal.app.models.Accelerators.isFallback(it) }.map { it.uppercase() }
        return (listOf(forced) + fallback).distinct()
    }
}

/** Realtime "possibly relevant" fact cues: the last few seconds of live audio are embedded every tick and ranked against the client's facts. The adjustable knobs are in [LiveCueSettings]. */
object LiveCueConfig {
    /** Longest lookback a user can choose: as much audio as the embedder's context holds. The ring buffer is this long. */
    const val MAX_LOOKBACK_S = (EmbeddingConfig.MAX_TOKENS - EmbeddingConfig.AUDIO_MARGIN_TOKENS) / EmbeddingConfig.AUDIO_TOKENS_PER_SECOND
    const val MAX_LOOKBACK_SAMPLES = MAX_LOOKBACK_S * ChunkConfig.SAMPLE_RATE

    /** A tick that overruns the interval is followed by the next one after this gap, never queued up. */
    const val MIN_GAP_MS = 250L

    /** No embedding before this much audio exists. */
    const val MIN_AUDIO_SAMPLES = 3 * ChunkConfig.SAMPLE_RATE

    /** A window quieter than this (RMS, 0..1) is silence: the previous cues stay and nothing is embedded. */
    const val SILENCE_RMS = 0.003

    /** Cues shown. */
    const val TOP_K = 10
}

/** How the engines run (AD-01). Constants here; every timing is in [EngineTuning]. */
object EngineConfig {
    /** The emergency switch from AD-01: forces `LocalEngineHost` everywhere. */
    val IN_PROCESS: Boolean = com.therapylocal.app.BuildConfig.ENGINE_IN_PROCESS

    /** The one inline limit: a string or PCM over this many bytes (UTF-8 for strings) goes through shared memory. */
    const val INLINE_MAX_BYTES = 128 * 1024

    /** Debug check on a finished Bundle (a parcelled String is UTF-16, so inline strings count double); the binder limit is about 1 MB. */
    const val BUNDLE_DEBUG_MAX_BYTES = 768 * 1024

    val DEFAULT = EngineTuning()
}

/** Every timing the engine connection uses. Production uses [EngineConfig.DEFAULT]; only androidTest (through the lab wiring) passes another. */
data class EngineTuning(
    val bindTimeoutMs: Long = 10_000, val openTimeoutMs: Long = 300_000, val askMinTimeoutMs: Long = 600_000,
    val embedTimeoutMs: Long = 60_000, val transcribeTimeoutMs: Long = 180_000, val closeTimeoutMs: Long = 5_000,
    val hangNoProgressMs: Long = 60_000, val hardCapFactor: Int = 3, val exitLookupMs: Long = 2_000,
    val backoffMs: List<Long> = listOf(0, 500, 1000, 2000, 4000), val backoffCapMs: Long = 10_000,
    val stormDeaths: Int = 6, val stormWindowMs: Long = 120_000, val stableMs: Long = 60_000,
    val idleMs: Long = 120_000, val liveShutdownMs: Long = 5_000, val statusTimeoutMs: Long = 1_000,
    /** A whole recording at once (an hour on the CPU is minutes); the watchdog also needs no CPU progress, and kills at 3 times this. */
    val diarizeTimeoutMs: Long = 600_000,
)
