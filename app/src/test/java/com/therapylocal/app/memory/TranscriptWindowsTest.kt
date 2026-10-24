package com.therapylocal.app.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptWindowsTest {
    private val words = (1..1000).joinToString(" ") { "w$it" }

    @Test
    fun shortTextIsOneWindow() {
        assertEquals(1, TranscriptWindows.split("a b c", 100, 10).size)
    }

    @Test
    fun windowsOverlapByExactlyTheRequestedWords() {
        val w = TranscriptWindows.split(words, 300, 50)
        assertTrue(w.size > 1)
        for (i in 1 until w.size) {
            val prevTail = w[i - 1].split(" ").takeLast(50)
            val head = w[i].split(" ").take(50)
            assertEquals(prevTail, head)
        }
        assertEquals("w1000", w.last().split(" ").last())
        assertTrue(w.all { it.split(" ").size <= 300 })
    }

    @Test
    fun speakerLabelsAreStripped() {
        assertEquals("hello there\nfine", TranscriptWindows.stripSpeakerLabels("Therapist: hello there\nGeorge: fine"))
    }
}
