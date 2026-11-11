package com.therapylocal.app.data

import com.therapylocal.app.audio.AudioChunk
import com.therapylocal.app.facts.Prompts
import java.security.MessageDigest

/** One stored version of a session's raw transcript. [sha256] is [TranscriptHash.of] its segments; [audioSha256] is [AudioHash.of] the audio it came from, when known. */
data class TranscriptVersion(
    val sessionId: String,
    val version: Int,
    val sha256: String,
    val source: String,
    val segmentCount: Int,
    val recordingId: Long?,
    val audioSha256: String?,
    val createdAt: Long,
)

object TranscriptHash {
    /** SHA-256 over every segment's idx, times, speaker and text ([Prompts.hash] frames each part by length). The same segments always give the same hash. */
    fun of(segments: List<SegmentRow>): String {
        val parts = ArrayList<String>(segments.size * 5)
        for (s in segments) {
            parts += s.idx.toString(); parts += s.startMs.toString(); parts += s.endMs.toString(); parts += s.speaker.orEmpty(); parts += s.text
        }
        return Prompts.hash(*parts.toTypedArray())
    }
}

/** What an append of a transcript does. */
sealed interface TranscriptAppend {
    /** Same content as the current version: nothing is written. */
    data class Unchanged(val version: Int) : TranscriptAppend

    /** A new version, numbered [version], is written. */
    data class Append(val version: Int) : TranscriptAppend
}

object TranscriptVersions {
    /** Values of `transcripts.source`. */
    const val DIARIZED = "diarized"
    const val CHUNKS = "chunks"
    const val IMPORT = "import"
    const val MIGRATED = "migrated"

    fun plan(currentVersion: Int?, currentSha: String?, newSha: String): TranscriptAppend =
        if (currentVersion != null && currentSha == newSha) TranscriptAppend.Unchanged(currentVersion) else TranscriptAppend.Append((currentVersion ?: 0) + 1)
}

object AudioHash {
    /**
     * SHA-256 of a recording's audio: the bytes [PendingAudio.encode] gives each chunk, in index order. Null when there is no audio or a chunk
     * cannot be read (the hash is then unknown; it never fails a job). One chunk is held at a time.
     */
    fun of(indices: List<Int>, load: (Int) -> AudioChunk?): String? {
        if (indices.isEmpty()) return null
        val md = MessageDigest.getInstance("SHA-256")
        for (i in indices) md.update(PendingAudio.encode(load(i) ?: return null))
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
