package com.therapylocal.app.data

/** The transcription of one planned piece. [speaker] is null when the recording was not diarized. */
data class Piece(val startMs: Long, val endMs: Long, val text: String, val speaker: String? = null, val textEn: String? = null)

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

    /**
     * The English text of each utterance of [build], by segment index: the English of its pieces joined, for the pieces that have one. An utterance
     * none of whose pieces has English is not in the map. Uses the same merging, so the indices are those of [build].
     */
    fun english(pieces: List<Piece>): Map<Int, String> {
        val out = LinkedHashMap<Int, StringBuilder>()
        var idx = 0
        var lastSpeaker: String? = null
        for (p in pieces) {
            if (p.text.trim().isEmpty()) continue
            val merges = idx > 0 && p.speaker != null && lastSpeaker == p.speaker
            if (!merges) idx++
            lastSpeaker = p.speaker
            val en = p.textEn?.trim().orEmpty()
            if (en.isNotEmpty()) out.getOrPut(idx) { StringBuilder() }.let { if (it.isNotEmpty()) it.append(' '); it.append(en) }
        }
        return out.mapValues { it.value.toString() }
    }

    /** The fallback when diarization was skipped: every utterance keeps its own piece and has no speaker. */
    fun unlabelled(pieces: List<Piece>): List<SegmentRow> = build(pieces.map { it.copy(speaker = null) })
}
