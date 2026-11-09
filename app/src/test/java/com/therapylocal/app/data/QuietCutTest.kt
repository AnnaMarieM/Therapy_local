package com.therapylocal.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuietCutTest {
    private fun loud(seconds: Int) = ShortArray(seconds * 16_000) { if (it % 2 == 0) 8_000 else -8_000 }

    @Test
    fun theCutFallsInTheQuietestWindow() {
        val pcm = loud(10)
        // A pause of 100 ms at 5.3 s.
        for (i in 5_300 * 16 until 5_400 * 16) pcm[i] = 0
        val at = QuietCut.at(pcm, 4_500, 6_500)
        assertTrue(at.toString(), at in 5_325..5_375)
    }

    @Test
    fun silenceEverywhereCutsInTheMiddle() {
        assertTrue(QuietCut.at(ShortArray(10 * 16_000), 4_000, 6_000) in 4_990..5_010)
    }

    @Test
    fun aRangeShorterThanTheWindowIsCutInTheMiddle() {
        assertEquals(1_010, QuietCut.at(loud(3), 1_000, 1_020))
    }
}
