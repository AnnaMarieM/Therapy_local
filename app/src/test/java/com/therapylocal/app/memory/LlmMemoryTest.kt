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

class MemoryGateTest {
    private fun mb(n: Long) = n shl 20
    private fun cost(tokens: Int) = com.therapylocal.app.config.MemoryConfig.embedGpuCostBytes(tokens)

    @Test
    fun theEmbedderAtItsDefaultSizeFitsWhereTheFullSizeDoesNot() {
        // The tablet has 1.9-2.8 GB free. At 1024 tokens the measured cost is about 110 MB; at 8192 the load took at least 2.2 GB and lmkd killed :live.
        assertEquals(null, LlmMemory.gateReasonForCost(cost(1024), mb(2100), false))
        assertTrue(LlmMemory.gateReasonForCost(cost(8192), mb(2800), false)!!.startsWith("not enough free memory"))
    }

    @Test
    fun theCostLineStaysAboveEveryMeasuredPoint() {
        assertTrue(cost(1024) > mb(110) * 4)
        assertTrue(cost(2048) > mb(250) * 3)
        assertTrue(cost(4096) >= mb(1557))
        assertTrue(cost(8192) >= mb(2230))
    }

    @Test
    fun genuinelyLowFreeMemoryStillRefusesTheSmallSize() {
        assertTrue(LlmMemory.gateReasonForCost(cost(1024), mb(1200), false) != null)
    }

    @Test
    fun lowMemoryAlwaysBlocksAndSaysSo() {
        assertTrue(LlmMemory.gateReasonForCost(cost(1024), mb(8000), true)!!.contains("system reports low memory"))
    }

    @Test
    fun theLlmKeepsItsOwnFactor() {
        assertEquals(null, LlmMemory.gateReason(2_588_147_712L, mb(3600), false))
    }
}
