package com.therapylocal.app.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmMemoryTest {
    private val e4b = 3_659_530_240L
    private val e2b = 2_588_147_712L
    private fun gb(n: Double) = (n * (1L shl 30)).toLong()

    @Test
    fun theGpuNeedsAboutTheModelPlusOneGigabyte() {
        // GiB: the files are 3.41 and 2.41 GiB, and the GPU took about as much as the file (E4B) or two thirds of it (E2B).
        assertEquals(4.41, LlmMemory.gpuNeeds(e4b).toDouble() / (1L shl 30), 0.05)
        assertEquals(3.41, LlmMemory.gpuNeeds(e2b).toDouble() / (1L shl 30), 0.05)
    }

    @Test
    fun e4bDoesNotUseTheGpuOnAPhoneWithFourGigabytesFree() {
        assertFalse(LlmMemory.gpuFits(e4b, gb(4.0), lowMemory = false))
    }

    @Test
    fun e4bUsesTheGpuWhenThereIsRoom() {
        assertTrue(LlmMemory.gpuFits(e4b, gb(6.0), lowMemory = false))
    }

    @Test
    fun e2bFitsWhereE4bDoesNot() {
        assertTrue(LlmMemory.gpuFits(e2b, gb(4.0), lowMemory = false))
    }

    @Test
    fun theGpuIsNeverUsedWhenTheSystemReportsLowMemory() {
        assertFalse(LlmMemory.gpuFits(e2b, gb(8.0), lowMemory = true))
    }
}
