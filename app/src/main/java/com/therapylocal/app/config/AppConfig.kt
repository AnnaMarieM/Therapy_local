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
    /** Stored on every chunk row; search only compares vectors with the same model id. */
    const val MODEL_ID = "embeddinggemma-2-740m"

    /** Native size of the model. Matryoshka truncation to 512, 256 or 128 is possible by changing this one value. */
    const val DIM = 768

    /** Text signature length. 1024 is what the Gallery's video moment finder uses for audio plus text. */
    const val MAX_TOKENS = 1024

    const val QUERY_PREFIX = "task: search query | text: "
    const val DOC_PREFIX = "task: search result | text: "
}

/** How many chunks a search looks at and shows. */
object SearchConfig {
    /** Chunks fetched before merging neighbours. */
    const val RAW_TOP_K = 10

    /** A chunk is relevant only if its score is within this margin of the best one. Stops merging weak neighbours into a strong hit. */
    const val SCORE_MARGIN = 0.08f

    /** Results shown after neighbours are merged. */
    const val SHOWN = 5
}

/** Debug override of which backend the engines try: "auto" (NPU if possible, then GPU, then CPU), "npu", "gpu" or "cpu". */
object BackendPolicy {
    @Volatile
    var override: String = "auto"

    fun allows(backend: String) = override == "auto" || override.equals(backend, ignoreCase = true)
}

/** Realtime "possibly relevant" fact cues: the last WINDOW_SECONDS of live audio is embedded every TICK_MS and ranked against the client's facts. */
object LiveCueConfig {
    /** Audio window embedded per tick. Same length as a stored chunk, so it stays under Gemma's audio cap. */
    const val WINDOW_SECONDS = ChunkConfig.CHUNK_SECONDS
    const val WINDOW_SAMPLES = WINDOW_SECONDS * ChunkConfig.SAMPLE_RATE

    /** Pause between ticks. A tick that overruns it is followed by the next one after MIN_GAP_MS, never queued up. */
    const val TICK_MS = 5_000L
    const val MIN_GAP_MS = 250L

    /** No embedding before this much audio exists. */
    const val MIN_AUDIO_SAMPLES = 3 * ChunkConfig.SAMPLE_RATE

    /** A window quieter than this (RMS, 0..1) is silence: the previous cues stay and nothing is embedded. */
    const val SILENCE_RMS = 0.003

    /** Cues shown. */
    const val TOP_K = 10

    /** Weight of the newest tick in the per-fact score average. 1.0 = no smoothing. */
    const val SMOOTH_ALPHA = 0.5f

    /** Smoothed cosine a fact needs to be shown. To be calibrated on the phone: fact-to-fact cosines sit at 0.75-0.89, audio-to-fact is unmeasured. */
    const val SCORE_FLOOR = 0f
}
