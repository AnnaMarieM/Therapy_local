package com.therapylocal.app.models

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelChoiceTest {
    private val gib = 1L shl 30

    @Test
    fun storedValuesResolveToTheirBuild() {
        assertEquals(GemmaVariant.E2B, ModelChoice.resolve("e2b"))
        assertEquals(GemmaVariant.E4B, ModelChoice.resolve("e4b"))
    }

    @Test
    fun unsetOrUnknownKeepsE2b() {
        assertEquals(GemmaVariant.E2B, ModelChoice.resolve(null))
        assertEquals(GemmaVariant.E2B, ModelChoice.resolve("E4B"))
        assertEquals(GemmaVariant.E2B, ModelChoice.resolve("gemma-4-e4b"))
    }

    @Test
    fun phonesSoldWith12GbAreSuggestedE4b() {
        // Typical totalMem readings: a 12 GB phone reports ~11.2-11.6 GiB, a 16 GB one ~15 GiB.
        assertEquals(GemmaVariant.E4B, ModelChoice.suggested(11_400L * (1L shl 20)))
        assertEquals(GemmaVariant.E4B, ModelChoice.suggested(15 * gib))
    }

    @Test
    fun phonesWith8GbOrLessAreSuggestedE2b() {
        assertEquals(GemmaVariant.E2B, ModelChoice.suggested(7_500L * (1L shl 20)))
        assertEquals(GemmaVariant.E2B, ModelChoice.suggested(10 * gib))
        assertEquals(GemmaVariant.E2B, ModelChoice.suggested(0))
    }

    @Test
    fun eachBuildMapsToItsOwnFile() {
        assertEquals("gemma-4-E2B-it.litertlm", ModelRegistry.gemma(GemmaVariant.E2B).files.single().name)
        assertEquals("gemma-4-E4B-it.litertlm", ModelRegistry.gemma(GemmaVariant.E4B).files.single().name)
        assertEquals(3_659_530_240L, ModelRegistry.GEMMA_E4B.totalBytes)
    }
}
