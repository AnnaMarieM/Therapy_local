package com.therapylocal.app.data

import com.therapylocal.app.audio.AudioChunk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChunkAudioTest {
    /** Chunk 0 covers 0..20 s, chunk 1 15..30 s (5 s overlap); the sample of second s has the value s. */
    private fun chunk(idx: Int): AudioChunk {
        val (start, end, overlap) = if (idx == 0) Triple(0L, 20_000L, 0L) else Triple(15_000L, 30_000L, 5_000L)
        return AudioChunk(idx, start, end, overlap, ShortArray(((end - start) * 16).toInt()) { ((start * 16 + it) / 16_000).toShort() })
    }

    private val rows = listOf(ChunkRow(1, 0, 0, 20_000, 0, null), ChunkRow(1, 1, 15_000, 30_000, 5_000, null))
    private val loads = ArrayList<Int>()
    private val audio = ChunkAudio(rows) { loads += it; chunk(it) }

    @Test
    fun theWholeRecordingIsOneTimelineWithoutTheRepeatedOverlap() {
        val all = audio.slice(0, audio.durationMs)
        assertEquals(30 * 16_000, all.size)
        assertTrue((0 until all.size).all { all[it].toInt() == it / 16_000 })
    }

    @Test
    fun aSliceAcrossTheChunkBoundaryIsContinuous() {
        val s = audio.slice(18_000, 22_000)
        assertEquals(4 * 16_000, s.size)
        assertEquals(listOf(18, 19, 20, 21), listOf(s[0], s[16_000], s[32_000], s[48_000]).map { it.toInt() })
    }

    @Test
    fun aSliceInsideOneChunkLoadsOnlyThatChunk() {
        audio.slice(2_000, 4_000)
        assertEquals(listOf(0), loads)
    }

    @Test
    fun aStretchNoChunkCoversIsSilence() {
        val gap = ChunkAudio(listOf(ChunkRow(1, 0, 1_000, 2_000, 0, null))) { AudioChunk(0, 1_000, 2_000, 0, ShortArray(16_000) { 7 }) }
        val s = gap.slice(0, 3_000)
        assertEquals(0, s[0].toInt())
        assertEquals(7, s[20_000].toInt())
        assertEquals(0, s[40_000].toInt())
    }
}
