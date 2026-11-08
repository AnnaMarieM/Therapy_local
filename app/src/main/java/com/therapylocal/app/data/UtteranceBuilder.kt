package com.therapylocal.app.data

/** The transcription of one planned piece. [speaker] is null when the recording was not diarized. */
data class Piece(val startMs: Long, val endMs: Long, val text: String, val speaker: String? = null)

/**
 * Pieces to utterances (the stored segments): empty texts are dropped, and neighbouring pieces of the same speaker, which are the parts of a
 * turn that was split to fit the recogniser or two turns with a short pause, become one utterance. Index 1..n. Pure Kotlin.
 */
object UtteranceBuilder {
    fun build(pieces: List<Piece>): List<SegmentRow> {
        val out = ArrayList<SegmentRow>()
        for (p in pieces) {
            val text = p.text.trim()
            if (text.isEmpty()) continue
            val last = out.lastOrNull()
            // Unknown speakers are never merged: null does not mean the same person.
            if (last != null && p.speaker != null && last.speaker == p.speaker) {
                out[out.lastIndex] = last.copy(endMs = maxOf(last.endMs, p.endMs), text = last.text + " " + text)
            } else {
                out += SegmentRow(out.size + 1, p.startMs, p.endMs, text, p.speaker)
            }
        }
        return out
    }

    /** The fallback when diarization was skipped: every utterance keeps its own piece and has no speaker. */
    fun unlabelled(pieces: List<Piece>): List<SegmentRow> = build(pieces.map { it.copy(speaker = null) })
}
