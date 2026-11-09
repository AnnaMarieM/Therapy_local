package com.therapylocal.app.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The speaker-diarization pair (docs/AD/AD-06.md) is a registered model with two checked files. */
class DiarizationSetupTest {
    @Test
    fun theDiarizationPairIsRegisteredWithTwoCheckedFiles() {
        val d = ModelRegistry.DIARIZATION
        assertEquals(d, ModelRegistry.byId("diarization-pyannote3-wespeaker"))
        assertEquals(listOf(ModelRegistry.DIAR_SEGMENTATION_FILE, ModelRegistry.DIAR_EMBEDDING_FILE), d.files.map { it.name })
        assertTrue(d.files.all { it.sha256.matches(Regex("[0-9a-f]{64}")) && it.size > 1_000_000 && it.url.startsWith("https://") && it.url.endsWith("/${it.name}") })
        assertEquals(5_992_913L + 26_530_550L, d.totalBytes)
    }

    @Test
    fun everyIdAndDirectoryIsUnique() {
        assertEquals(ModelRegistry.ALL.size, ModelRegistry.ALL.map { it.id }.distinct().size)
        assertEquals(ModelRegistry.ALL.size, ModelRegistry.ALL.map { it.subdir ?: it.id }.distinct().size)
    }
}
