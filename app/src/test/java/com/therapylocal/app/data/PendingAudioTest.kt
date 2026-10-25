package com.therapylocal.app.data

import com.therapylocal.app.audio.AudioChunk
import com.therapylocal.app.testsupport.assertIoException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import kotlin.random.Random

/**
 * Format contract (PendingAudio.encode doc): "TLA1" + idx(i32) + startMs(i64) + endMs(i64) + overlapMs(i64) + sampleCount(i32)
 * + PCM16 samples, everything little-endian; 36 header bytes. Chunks are persisted this way, so older files must stay readable.
 * Integrity of the bytes is the job of SecureFile (AES-GCM); decode() only has to refuse malformed input with an IOException
 * (never OutOfMemoryError / NegativeArraySizeException / IndexOutOfBounds).
 */
class PendingAudioTest {
    private fun chunk(idx: Int = 1, s: Long = 0, e: Long = 1000, o: Long = 0, pcm: ShortArray = shortArrayOf(1, 2, 3)) = AudioChunk(idx, s, e, o, pcm)

    private fun assertSame(a: AudioChunk, b: AudioChunk) {
        assertEquals(a.idx, b.idx)
        assertEquals(a.startMs, b.startMs)
        assertEquals(a.endMs, b.endMs)
        assertEquals(a.overlapMs, b.overlapMs)
        assertArrayEquals(a.pcm, b.pcm)
    }

    private fun assertIoException(msg: String, bytes: ByteArray) = assertIoException(msg) { PendingAudio.decode(bytes) }

    private fun ByteArray.withInt(offset: Int, v: Int): ByteArray = copyOf().also {
        for (k in 0 until 4) it[offset + k] = (v ushr (8 * k)).toByte()
    }

    @Test
    fun roundTripKeepsEveryField() {
        val pcm = shortArrayOf(0, 1, -1, 12345, -12345, Short.MAX_VALUE, Short.MIN_VALUE)
        val c = AudioChunk(7, 25_000L, 55_000L, 5_000L, pcm)
        assertSame(c, PendingAudio.decode(PendingAudio.encode(c)))
    }

    @Test
    fun emptyAudioRoundTrips() {
        val c = chunk(0, 0, 0, 0, ShortArray(0))
        val bytes = PendingAudio.encode(c)
        assertEquals(36, bytes.size)
        assertSame(c, PendingAudio.decode(bytes))
    }

    @Test
    fun extremeFieldValuesRoundTrip() {
        val c = AudioChunk(Int.MAX_VALUE, Long.MAX_VALUE, Long.MIN_VALUE, -1L, shortArrayOf(Short.MIN_VALUE))
        assertSame(c, PendingAudio.decode(PendingAudio.encode(c)))
        val d = AudioChunk(Int.MIN_VALUE, Long.MIN_VALUE, Long.MAX_VALUE, 0L, ShortArray(0))
        assertSame(d, PendingAudio.decode(PendingAudio.encode(d)))
    }

    @Test
    fun seededRandomRoundTrips() {
        val rnd = Random(2024)
        val sizes = listOf(0, 1, 2, 3, 255, 256, 1000, 16_000 * 30) + List(40) { rnd.nextInt(0, 5000) }
        for (n in sizes) {
            val pcm = ShortArray(n) { rnd.nextInt(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt() + 1).toShort() }
            val c = AudioChunk(rnd.nextInt(), rnd.nextLong(), rnd.nextLong(), rnd.nextLong(), pcm)
            val bytes = PendingAudio.encode(c)
            assertEquals(36 + 2 * n, bytes.size)
            assertSame(c, PendingAudio.decode(bytes))
        }
    }

    @Test
    fun byteLayoutIsTheDocumentedLittleEndianFormat() {
        val c = AudioChunk(0x01020304, 0x1112131415161718L, 2L, 3L, shortArrayOf(0x0A0B, -2))
        val expected = byteArrayOf(
            'T'.code.toByte(), 'L'.code.toByte(), 'A'.code.toByte(), '1'.code.toByte(),
            0x04, 0x03, 0x02, 0x01, // idx
            0x18, 0x17, 0x16, 0x15, 0x14, 0x13, 0x12, 0x11, // startMs
            2, 0, 0, 0, 0, 0, 0, 0, // endMs
            3, 0, 0, 0, 0, 0, 0, 0, // overlapMs
            2, 0, 0, 0, // sample count
            0x0B, 0x0A, // 0x0A0B
            0xFE.toByte(), 0xFF.toByte(), // -2
        )
        assertArrayEquals(expected, PendingAudio.encode(c))
        assertSame(c, PendingAudio.decode(expected))
    }

    @Test
    fun encodeDoesNotAliasOrModifyTheChunk() {
        val pcm = shortArrayOf(1, 2, 3)
        val c = chunk(pcm = pcm)
        val a = PendingAudio.encode(c)
        val b = PendingAudio.encode(c)
        assertArrayEquals(a, b)
        assertArrayEquals(shortArrayOf(1, 2, 3), pcm)
        val back = PendingAudio.decode(a)
        back.pcm[0] = 99
        assertArrayEquals(b, PendingAudio.encode(c))
    }

    @Test
    fun everyMagicByteIsChecked() {
        val bytes = PendingAudio.encode(chunk())
        for (i in 0 until 4) {
            val bad = bytes.copyOf()
            bad[i] = (bad[i].toInt() xor 0x01).toByte()
            assertIoException("magic byte $i", bad)
        }
        assertIoException("old/other version tag", bytes.copyOf().also { it[3] = '2'.code.toByte() })
    }

    @Test
    fun truncationAtEveryLengthThrows() {
        val bytes = PendingAudio.encode(chunk(pcm = shortArrayOf(1, 2, 3)))
        for (len in 0 until bytes.size) assertIoException("truncated to $len", bytes.copyOf(len))
    }

    @Test
    fun trailingBytesThrow() {
        val bytes = PendingAudio.encode(chunk())
        assertIoException("1 extra", bytes + 0)
        assertIoException("2 extra", bytes + byteArrayOf(0, 0))
        assertIoException("header only plus pad", ByteArray(0) + bytes + ByteArray(1000))
    }

    @Test
    fun sampleCountThatDisagreesWithTheLengthThrows() {
        val bytes = PendingAudio.encode(chunk(pcm = shortArrayOf(1, 2, 3)))
        val countOffset = 32
        for (n in listOf(0, 1, 2, 4, 5, 1000)) assertIoException("count $n", bytes.withInt(countOffset, n))
    }

    @Test
    fun negativeOrHugeSampleCountThrowsIoExceptionNotOomOrNegativeArraySize() {
        val bytes = PendingAudio.encode(chunk(pcm = shortArrayOf(1, 2, 3)))
        val countOffset = 32
        for (n in listOf(-1, -2, Int.MIN_VALUE, Int.MIN_VALUE + 1, Int.MAX_VALUE, Int.MAX_VALUE - 1, 0x40000000, 0x3FFFFFFF, 0x7FFFFFF0)) {
            assertIoException("count $n", bytes.withInt(countOffset, n))
        }
        // a header-only buffer that claims a gigantic payload
        assertIoException("header only, huge count", PendingAudio.encode(chunk(pcm = ShortArray(0))).withInt(countOffset, Int.MAX_VALUE))
    }

    @Test
    fun countWrapAroundCannotMakeAShortBufferLookValid() {
        // n * 2 overflows Int for n >= 2^30; the length check must not wrap to the real size (6 bytes of PCM).
        val bytes = PendingAudio.encode(chunk(pcm = shortArrayOf(1, 2, 3)))
        assertIoException("count 2^31 + 3 as negative", bytes.withInt(32, Int.MIN_VALUE + 3))
        assertIoException("count 2^30 + 3", bytes.withInt(32, (1 shl 30) + 3))
    }

    @Test
    fun randomGarbageNeverEscapesAsAnotherExceptionType() {
        val rnd = Random(99)
        val good = PendingAudio.encode(chunk(pcm = ShortArray(50) { it.toShort() }))
        repeat(500) {
            val bad = good.copyOf()
            repeat(rnd.nextInt(1, 6)) { bad[rnd.nextInt(bad.size)] = rnd.nextInt().toByte() }
            try {
                // may legitimately succeed when only idx/ms/pcm bytes changed; then it must be a faithful reading of those bytes
                assertArrayEquals(bad, PendingAudio.encode(PendingAudio.decode(bad)))
            } catch (_: IOException) {
            }
        }
        repeat(200) {
            try {
                val bytes = rnd.nextBytes(rnd.nextInt(0, 80))
                assertArrayEquals(bytes, PendingAudio.encode(PendingAudio.decode(bytes)))
            } catch (_: IOException) {
            }
        }
    }
}
