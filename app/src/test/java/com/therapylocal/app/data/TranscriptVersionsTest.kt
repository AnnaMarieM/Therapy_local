package com.therapylocal.app.data

import com.therapylocal.app.audio.AudioChunk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The hashes the versioned transcript and its audio reference rest on (docs/AD/AD-10.md). */
class TranscriptVersionsTest {
    private fun seg(idx: Int, text: String, speaker: String? = "S0", start: Long = idx * 1000L, end: Long = start + 900) = SegmentRow(idx, start, end, text, speaker)

    private val base = listOf(seg(1, "hello"), seg(2, "world", "S1"))

    @Test
    fun theSameSegmentsHaveTheSameHash() {
        assertEquals(TranscriptHash.of(base), TranscriptHash.of(base.map { it.copy() }))
        assertEquals(64, TranscriptHash.of(base).length)
    }

    @Test
    fun anyChangeChangesTheHash() {
        val h = TranscriptHash.of(base)
        assertNotEquals(h, TranscriptHash.of(base.dropLast(1)))
        assertNotEquals(h, TranscriptHash.of(listOf(seg(1, "hello"), seg(2, "world!", "S1"))))
        assertNotEquals(h, TranscriptHash.of(listOf(seg(1, "hello"), seg(2, "world", "S0"))))
        assertNotEquals(h, TranscriptHash.of(listOf(seg(1, "hello"), seg(2, "world", "S1", end = 5000))))
        assertNotEquals(h, TranscriptHash.of(listOf(seg(1, "hello"), seg(3, "world", "S1", start = 2000, end = 2900))))
    }

    @Test
    fun partsAreFramedSoSplitsDoNotCollide() {
        assertNotEquals(TranscriptHash.of(listOf(seg(1, "ab", null))), TranscriptHash.of(listOf(seg(1, "a", "b"))))
        assertNotEquals(TranscriptHash.of(listOf(seg(1, "a b"))), TranscriptHash.of(listOf(seg(1, "a"), seg(2, "b", start = 1000, end = 1900))))
    }

    @Test
    fun audioHashCoversEveryChunkInOrder() {
        val chunks = mapOf(0 to AudioChunk(0, 0, 1000, 0, shortArrayOf(1, 2, 3)), 1 to AudioChunk(1, 1000, 2000, 0, shortArrayOf(4, 5, 6)))
        val h = AudioHash.of(listOf(0, 1)) { chunks[it] }!!
        assertEquals(64, h.length)
        assertEquals(h, AudioHash.of(listOf(0, 1)) { chunks[it] })
        assertNotEquals(h, AudioHash.of(listOf(1, 0)) { chunks[it] })
        assertNotEquals(h, AudioHash.of(listOf(0)) { chunks[it] })
        assertNotEquals(h, AudioHash.of(listOf(0, 1)) { AudioChunk(it, 0, 1000, 0, shortArrayOf(9)) })
    }

    @Test
    fun audioHashIsUnknownWithoutAudioOrWithAMissingChunk() {
        assertNull(AudioHash.of(emptyList()) { null })
        assertNull(AudioHash.of(listOf(0, 1)) { if (it == 0) AudioChunk(0, 0, 1, 0, shortArrayOf(1)) else null })
    }
}
