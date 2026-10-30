package com.therapylocal.app.session

import kotlin.math.log10
import kotlin.math.sqrt

/** Where a session is. [Finishing] is after the user ended it and before its last chunk is saved. */
enum class SessionPhase { Idle, Recording, Paused, Finishing }

/**
 * Elapsed recording time from a monotonic clock, so it never drifts the way a one-second ticker does and survives the UI being recreated.
 * [runningSince] is null while paused or stopped. Pure: every call takes the current time.
 */
data class SessionClock(val accumulatedMs: Long = 0, val runningSince: Long? = null) {
    fun elapsedMs(now: Long): Long = accumulatedMs + (runningSince?.let { (now - it).coerceAtLeast(0) } ?: 0)
    fun pause(now: Long): SessionClock = if (runningSince == null) this else SessionClock(elapsedMs(now), null)
    fun resume(now: Long): SessionClock = if (runningSince != null) this else copy(runningSince = now)

    companion object {
        fun started(now: Long) = SessionClock(0, now)
    }
}

/** The last session that ended, for the "Saved" moment and its Discard. */
data class EndedSession(val client: String, val sessionId: String, val startedAt: Long, val durationMs: Long)

/**
 * One session as the UI sees it. [error] is a recording problem only (setup problems live elsewhere). [recordingId] is set from the first
 * saved row until the last chunk is saved. [cuesOn] is false when live cues could not start (setup not finished yet).
 */
data class SessionState(
    val phase: SessionPhase = SessionPhase.Idle,
    val client: String = "",
    val sessionId: String = "",
    val startedAt: Long = 0,
    val clock: SessionClock = SessionClock(),
    val recordingId: Long? = null,
    val cuesOn: Boolean = false,
    val error: String? = null,
    val ended: EndedSession? = null,
) {
    /** Recording or paused: the user is in a session. */
    val active: Boolean get() = phase == SessionPhase.Recording || phase == SessionPhase.Paused

    /** A new session can start: nothing is recording and the previous one has saved its last chunk. */
    val canStart: Boolean get() = phase == SessionPhase.Idle
}

/** Microphone loudness for the level meter, 0..1 on a perceptual (dB) scale. */
object AudioLevel {
    /** Quieter than this is shown as silence; the room tone of a quiet office sits around here. */
    const val FLOOR_DB = -60.0

    /** 0..1 from the RMS of the first [n] samples of [buf] (PCM16). */
    fun of(buf: ShortArray, n: Int): Float {
        if (n <= 0) return 0f
        var sum = 0.0
        for (i in 0 until minOf(n, buf.size)) {
            val s = buf[i].toDouble()
            sum += s * s
        }
        return fromRms(sqrt(sum / minOf(n, buf.size)) / 32768.0)
    }

    /** 0..1 from an RMS in 0..1 of full scale. */
    fun fromRms(rms: Double): Float {
        if (rms <= 0.0 || rms.isNaN()) return 0f
        val db = 20 * log10(rms)
        return ((db - FLOOR_DB) / -FLOOR_DB).coerceIn(0.0, 1.0).toFloat()
    }
}
