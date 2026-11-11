package com.therapylocal.app.data

import com.therapylocal.app.audio.AudioChunk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The raw transcript is append-only and versioned by content hash; audio is referenced by hash (docs/AD/AD-10.md). */
class TranscriptVersionsTest {
    private fun seg(idx: Int, text: String, speaker: String? = "S0", start: Long = idx * 1000L, end: Long = start + 900) = SegmentRow(idx, start, end, text, speaker)

    private val base = listOf(seg(1, "hello"), seg(2, "world", "S1"))

    /** The append logic of [MindStore.appendTranscript] on a list: what it stores for each call. */
    private class Log {
        val versions = ArrayList<Pair<TranscriptVersion, List<SegmentRow>>>()
        fun append(segments: List<SegmentRow>, source: String = TranscriptVersions.DIARIZED): TranscriptVersion {
            val sha = TranscriptHash.of(segments)
            val head = versions.lastOrNull()?.first
            return when (val plan = TranscriptVersions.plan(head?.version, head?.sha256, sha)) {
                is TranscriptAppend.Unchanged -> head!!
                is TranscriptAppend.Append -> TranscriptVersion("s", plan.version, sha, source, segments.size, null, null, 0).also { versions += it to segments }
            }
        }
    }

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
    fun theHashOfNoSegmentsIsStableAndDiffersFromSomeText() {
        assertEquals(TranscriptHash.of(emptyList()), TranscriptHash.of(emptyList()))
        assertNotEquals(TranscriptHash.of(emptyList()), TranscriptHash.of(base))
    }

    @Test
    fun theFirstTranscriptIsVersionOne() = assertEquals(1, Log().append(base).version)

    @Test
    fun theSameContentAgainWritesNothing() {
        val log = Log()
        val first = log.append(base)
        val again = log.append(base.map { it.copy() }, TranscriptVersions.CHUNKS)
        assertEquals(first, again)
        assertEquals(1, log.versions.size)
    }

    @Test
    fun changedContentIsANewVersionAndTheOldOneStaysAsItWas() {
        val log = Log()
        val v1 = log.append(base)
        val v2 = log.append(listOf(seg(1, "hello"), seg(2, "there", "S1")))
        assertEquals(listOf(1, 2), log.versions.map { it.first.version })
        assertNotEquals(v1.sha256, v2.sha256)
        assertEquals(base, log.versions[0].second)
        assertEquals(v1, log.versions[0].first)
    }

    @Test
    fun goingBackToEarlierContentIsAnotherNewVersion() {
        val log = Log()
        log.append(base)
        log.append(listOf(seg(1, "other")))
        assertEquals(3, log.append(base).version)
    }

    @Test
    fun audioHashCoversEveryChunkInOrder() {
        val a = AudioChunk(0, 0, 1000, 0, shortArrayOf(1, 2, 3))
        val b = AudioChunk(1, 1000, 2000, 0, shortArrayOf(4, 5, 6))
        val chunks = mapOf(0 to a, 1 to b)
        val h = AudioHash.of(listOf(0, 1)) { chunks[it] }
        assertNotNull(h)
        assertEquals(h, AudioHash.of(listOf(0, 1)) { chunks[it] })
        assertNotEquals(h, AudioHash.of(listOf(1, 0)) { chunks[it] })
        assertNotEquals(h, AudioHash.of(listOf(0)) { chunks[it] })
        assertNotEquals(h, AudioHash.of(listOf(0, 1)) { AudioChunk(it, 0, 1000, 0, shortArrayOf(9)) })
    }

    @Test
    fun audioHashIsUnknownWithoutAudioOrWithAMissingChunk() {
        assertNull(AudioHash.of(emptyList()) { null })
        assertNull(AudioHash.of(listOf(0, 1)) { if (it == 0) AudioChunk(0, 0, 1, 0, shortArrayOf(1)) else null })
        assertTrue(AudioHash.of(listOf(0)) { AudioChunk(0, 0, 1, 0, shortArrayOf(1)) }!!.length == 64)
    }
}
