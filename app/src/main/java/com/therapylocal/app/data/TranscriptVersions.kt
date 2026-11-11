package com.therapylocal.app.data

import com.therapylocal.app.audio.AudioChunk
import com.therapylocal.app.facts.Prompts
import java.security.MessageDigest

/** One stored version of a session's raw transcript; [audioSha256] is [AudioHash.of] the audio it came from, [recordingId] where that audio is kept. */
data class TranscriptVersion(val sessionId: String, val version: Int, val sha256: String, val recordingId: Long?, val audioSha256: String?, val createdAt: Long)

object TranscriptHash {
    /** SHA-256 over every segment's index, times, speaker and text, each part length-framed ([Prompts.hash]). */
    fun of(segments: List<SegmentRow>): String =
        Prompts.hash(*segments.flatMap { listOf(it.idx.toString(), it.startMs.toString(), it.endMs.toString(), it.speaker.orEmpty(), it.text) }.toTypedArray())
}

object AudioHash {
    /** SHA-256 over the bytes [PendingAudio.encode] gives each chunk, in order, one chunk in memory at a time. Null without audio or with a missing chunk. */
    fun of(indices: List<Int>, load: (Int) -> AudioChunk?): String? {
        if (indices.isEmpty()) return null
        val md = MessageDigest.getInstance("SHA-256")
        for (i in indices) md.update(PendingAudio.encode(load(i) ?: return null))
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
