package com.therapylocal.app.asr

import com.therapylocal.app.log.LogCtx

/** One stretch of one speaker's voice, in milliseconds from the start of the audio. [speaker] is a cluster number (0, 1, ...), not a role. */
data class DiarTurn(val startMs: Long, val endMs: Long, val speaker: Int)

/**
 * Speaker diarization of a whole recording (docs/AD/AD-06.md): who speaks when, so Canary can transcribe one speaker turn at a time.
 * Loaded lazily like [AsrEngine] and reused; the model runs in the `:batch` host.
 */
interface Diarizer {
    /** False once the host ended under the loaded engine (a death, an idle stop): it must be closed and loaded again. */
    val healthy: Boolean

    suspend fun load()

    /**
     * Turns of 16 kHz mono PCM16 [pcm], sorted by start. [numSpeakersHint] > 0 fixes the number of speakers; 0 lets the clustering threshold
     * decide. Silence gives an empty list. Throws when the engine fails; the caller falls back to the chunk path.
     */
    suspend fun diarize(pcm: ShortArray, numSpeakersHint: Int, ctx: LogCtx): List<DiarTurn>

    fun close()
}
