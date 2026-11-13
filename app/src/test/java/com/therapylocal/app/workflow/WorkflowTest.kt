package com.therapylocal.app.workflow

import com.therapylocal.app.config.Workflow
import com.therapylocal.app.models.GemmaVariant
import com.therapylocal.app.models.ModelRegistry
import com.therapylocal.app.perf.StageLog
import com.therapylocal.app.jobs.JobGraph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The two workflows and the control (docs/AD/AD-22.md): what each one fixes, which models it needs and how its stages are named. */
class WorkflowTest {
    @Test
    fun theControlIsTheDefaultAndAnUnknownKeyReadsAsIt() {
        assertEquals(Workflow.Canary, Workflow.DEFAULT)
        assertEquals(Workflow.Canary, Workflow.of(null))
        assertEquals(Workflow.Canary, Workflow.of("turbo"))
        assertEquals(listOf("canary", "fast", "quality", "quality-cs"), Workflow.entries.map { it.key })
        Workflow.entries.forEach { assertEquals(it, Workflow.of(it.key)) }
    }

    @Test
    fun fastIsE2bAndQualityIsE4bAndTheControlLeavesTheSettingAlone() {
        assertEquals(GemmaVariant.E2B, Workflow.Fast.gemma)
        assertEquals(GemmaVariant.E4B, Workflow.Quality.gemma)
        assertNull(Workflow.Canary.gemma)
        assertTrue(Workflow.Fast.fixesGemma && Workflow.Quality.fixesGemma)
        assertFalse(Workflow.Canary.fixesGemma)
    }

    @Test
    fun fastAndQualityWorkInEnglishWhateverTheProcessingLanguageSays() {
        assertTrue(Workflow.Fast.forcesEnglish)
        assertTrue(Workflow.Quality.forcesEnglish)
        assertFalse(Workflow.Canary.forcesEnglish)
    }

    @Test
    fun onlyQualityHasNoTranscriptInTheLanguageSpoken() {
        assertFalse(Workflow.Quality.keepsSourceTranscript("cs"))
        assertTrue(Workflow.Quality.keepsSourceTranscript("en"))
        assertTrue(Workflow.Quality.keepsSourceTranscript("EN"))
        assertTrue(Workflow.Fast.keepsSourceTranscript("cs"))
        assertTrue(Workflow.Canary.keepsSourceTranscript("cs"))
    }

    @Test
    fun qualityCsKeepsTheSpokenTranscriptUsesWhisperAndE4b() {
        assertEquals(Workflow.QualityCs, Workflow.of("quality-cs"))
        assertEquals(GemmaVariant.E4B, Workflow.QualityCs.gemma)
        assertTrue(Workflow.QualityCs.forcesEnglish)
        assertTrue(Workflow.QualityCs.keepsSourceTranscript("cs"))
        assertEquals(listOf("whisper-large-v3-q8_0"), ModelRegistry.speechFor(Workflow.QualityCs).map { it.id })
    }

    @Test
    fun eachWorkflowNeedsItsOwnSpeechModel() {
        assertEquals(listOf("canary-1b-v2"), ModelRegistry.speechFor(Workflow.Canary).map { it.id })
        assertEquals(emptyList<String>(), ModelRegistry.speechFor(Workflow.Fast).map { it.id })
        assertEquals(listOf("whisper-large-v3-q8_0"), ModelRegistry.speechFor(Workflow.Quality).map { it.id })
    }

    @Test
    fun theWhisperSpecPinsTheMeasuredFile() {
        val w = ModelRegistry.WHISPER_LARGE_V3_Q8
        assertEquals(1, w.files.size)
        assertEquals("ggml-large-v3-q8_0.bin", w.files.first().name)
        assertEquals(1_656_538_283L, w.totalBytes)
        assertEquals("24bc434f372355688ab9a623077a63e5361a1c41f4d8d648977e39f9b060f09e", w.files.first().sha256)
        // Nobody hosts the q8_0 file yet: the address is empty on purpose (see ModelRegistry), not an invented one.
        assertEquals("", w.files.first().url)
        assertEquals(w, ModelRegistry.byId(w.id))
    }

    @Test
    fun theGemmaFilesTheAudioPathUsesArePinned() {
        assertEquals(2_588_147_712L, ModelRegistry.GEMMA_E2B.totalBytes)
        assertEquals(3_659_530_240L, ModelRegistry.GEMMA_E4B.totalBytes)
        assertTrue(ModelRegistry.GEMMA_E2B.files.first().sha256.length == 64 && ModelRegistry.GEMMA_E4B.files.first().sha256.length == 64)
    }

    @Test
    fun everyPipelineJobHasAStageAndTranscribeLogsItsOwn() {
        assertEquals("translate", StageLog.stageOf(JobGraph.TRANSLATE))
        assertEquals("roles", StageLog.stageOf(JobGraph.ROLES))
        assertEquals("facts", StageLog.stageOf(JobGraph.WINDOW))
        assertEquals("facts", StageLog.stageOf(JobGraph.EXTRACT))
        assertEquals("facts", StageLog.stageOf(JobGraph.COMMIT))
        assertEquals("summary", StageLog.stageOf(JobGraph.SUMMARY))
        assertEquals("translate_back", StageLog.stageOf(JobGraph.LOCALIZE))
        assertNull(StageLog.stageOf(JobGraph.TRANSCRIBE))
        assertNull(StageLog.stageOf(JobGraph.CLEANUP))
    }

    @Test
    fun aTimedStageReturnsItsResult() {
        assertEquals(42, StageLog.timed("roles", "fast", "s1") { 42 })
    }
}
