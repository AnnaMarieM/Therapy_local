package com.therapylocal.app.data

/**
 * The session transcript as timed segments, one per chunk that kept any words after [TranscriptMerge] removed the repeated overlap.
 * Read in order, the segments are exactly the merged transcript. A segment's times are the chunk's, narrowed to its kept words assuming
 * they are spread evenly over the chunk. Pure, so rebuilding from the same chunks gives the same segments.
 */
object SegmentBuilder {
    fun build(chunks: List<ChunkRow>): List<SegmentRow> {
        val texts = chunks.map { TranscriptClean.stripPromptEcho(it.text.orEmpty()) }
        val merged = TranscriptMerge.merge(texts)
        return chunks.indices.mapNotNull { i ->
            val kept = merged.kept[i]
            if (kept.isEmpty()) return@mapNotNull null
            val c = chunks[i]
            val words = TranscriptMerge.words(texts[i])
            val msPerWord = (c.endMs - c.startMs).toDouble() / words.size
            SegmentRow(0, c.startMs + (msPerWord * kept.first).toLong(), c.startMs + (msPerWord * (kept.last + 1)).toLong(), words.subList(kept.first, kept.last + 1).joinToString(" "))
        }.mapIndexed { n, s -> s.copy(idx = n + 1) }
    }
}
