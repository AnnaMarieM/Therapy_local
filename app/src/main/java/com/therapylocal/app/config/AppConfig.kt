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

    const val GEMMA_AUDIO_CAP_SECONDS = 30

    /** A recording does not start with less free internal storage: its audio is kept until processed, about 150 MB per hour with the overlap. */
    const val MIN_FREE_BYTES = 300L shl 20

    init {
        require(CHUNK_SECONDS < GEMMA_AUDIO_CAP_SECONDS) { "CHUNK_SECONDS must stay under Gemma's audio cap" }
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

    /** Input length the embedder asks for; lowered to the model's reported context if that is smaller. Live cues need room for [LiveCueConfig.MAX_LOOKBACK_S] of audio. */
    const val MAX_TOKENS = 2048

    const val QUERY_PREFIX = "task: search query | text: "
    const val DOC_PREFIX = "task: search result | text: "
}

/** The background pipeline that turns a session's transcript into facts (see [com.therapylocal.app.jobs.Pipeline]). */
object MemoryConfig {
    const val LLM_MAX_TOKENS = 6144
    val LLM_BACKENDS = listOf("NPU", "GPU", "CPU")

    /** Transcript windows, in estimated tokens: each is embedded for search and read by the LLM once. */
    const val WINDOW_TOKENS = 1000
    const val WINDOW_OVERLAP_TOKENS = 50

    const val EXTRACT_MAX_OUTPUT_TOKENS = 1600
    const val EXTRACT_TEMPERATURE = 0.1

    /** Known facts shown to the duplicate review. */
    const val NEIGHBOURS = 10

    /** A candidate with no earlier fact at least this close is new without asking the LLM. Our own choice, not calibrated yet. */
    const val MIN_SIMILARITY = 0.6f
}

/** Debug override of which backend the engines try: "auto" (NPU if possible, then GPU, then CPU), "npu", "gpu" or "cpu". */
object BackendPolicy {
    @Volatile
    var override: String = "auto"

    fun allows(backend: String) = override == "auto" || override.equals(backend, ignoreCase = true)
}

/** Realtime "possibly relevant" fact cues: the last few seconds of live audio are embedded every tick and ranked against the client's facts. The adjustable knobs are in [LiveCueSettings]. */
object LiveCueConfig {
    /** Longest lookback a user can choose; the ring buffer is this long. Not derived from audio tokens per second (unmeasured): raise it after a device measurement. */
    const val MAX_LOOKBACK_S = 60
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
