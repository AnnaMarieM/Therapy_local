package com.therapylocal.app.data

import com.therapylocal.app.audio.AudioChunk
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class PendingAudioTest {
    @Test
    fun roundTripKeepsEveryField() {
        val pcm = shortArrayOf(0, 1, -1, 12345, -12345, Short.MAX_VALUE, Short.MIN_VALUE)
        val back = PendingAudio.decode(PendingAudio.encode(AudioChunk(7, 25_000L, 55_000L, 5_000L, pcm)))
        assertEquals(7, back.idx)
        assertEquals(25_000L, back.startMs)
        assertEquals(55_000L, back.endMs)
        assertEquals(5_000L, back.overlapMs)
        assertArrayEquals(pcm, back.pcm)
    }

    @Test
    fun emptyAudioRoundTrips() {
        val back = PendingAudio.decode(PendingAudio.encode(AudioChunk(0, 0, 0, 0, ShortArray(0))))
        assertEquals(0, back.pcm.size)
    }

    @Test
    fun badMagicThrows() {
        val bytes = PendingAudio.encode(AudioChunk(1, 0, 1000, 0, shortArrayOf(1, 2, 3)))
        bytes[0] = 'X'.code.toByte()
        try {
            PendingAudio.decode(bytes)
            fail("expected IOException")
        } catch (_: IOException) {
        }
    }

    @Test
    fun truncatedOrTooShortThrows() {
        val bytes = PendingAudio.encode(AudioChunk(1, 0, 1000, 0, shortArrayOf(1, 2, 3)))
        for (b in listOf(bytes.copyOf(bytes.size - 1), bytes.copyOf(3), ByteArray(0))) {
            try {
                PendingAudio.decode(b)
                fail("expected IOException")
            } catch (_: IOException) {
            }
        }
    }
}
