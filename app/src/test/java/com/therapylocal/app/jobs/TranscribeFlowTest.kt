package com.therapylocal.app.jobs

import com.therapylocal.app.asr.DiarTurn
import com.therapylocal.app.asr.Diarizer
import com.therapylocal.app.audio.AudioChunk
import com.therapylocal.app.data.ChunkRow
import com.therapylocal.app.data.SegmentRow
import com.therapylocal.app.data.TurnRow
import com.therapylocal.app.log.LogCtx
import com.therapylocal.app.testsupport.FakeJobRepo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import com.therapylocal.app.data.AudioHash
import kotlin.coroutines.cancellation.CancellationException

/** The TRANSCRIBE job on test doubles: diarized, each way of falling back to the chunk path, and a restart (docs/AD/AD-06.md, AD-07). */
class TranscribeFlowTest {
    private val rid = 5L

    /** Rows and segments in memory. */
    private class Store(var rows: List<ChunkRow>) : TranscribeStore {
        var turns = emptyList<TurnRow>()
        var segments = emptyList<SegmentRow>()
        val audioShas = ArrayList<String?>()
        override fun chunks(recordingId: Long) = rows
        override fun putChunk(c: ChunkRow) { rows = (rows.filter { it.idx != c.idx } + c).sortedBy { it.idx } }
        override fun turns(recordingId: Long) = turns
        override fun replaceTurns(recordingId: Long, turns: List<TurnRow>) { this.turns = turns }
        override fun setTurnText(recordingId: Long, idx: Int, text: String) { turns = turns.map { if (it.idx == idx) it.copy(text = text) else it } }
        override fun appendTranscript(session: String, segments: List<SegmentRow>, recordingId: Long?, audioSha: String?) { this.segments = segments; audioShas += audioSha }
    }

    private class FakeDiarizer(val result: () -> List<DiarTurn>) : Diarizer {
        var calls = 0
        var hints = ArrayList<Int>()
        override val healthy = true
        override suspend fun load() {}
        override suspend fun diarize(pcm: ShortArray, numSpeakersHint: Int, ctx: LogCtx): List<DiarTurn> {
            calls++
            hints += numSpeakersHint
            return result()
        }
        override fun close() {}
    }

    /** Says "t<second of its first sample>": the audio is a staircase, one value per second of the recording. */
    private class FakeAsr : Asr {
        val heard = ArrayList<Int>()
        override fun transcribe(pcm: ShortArray): String {
            heard += pcm.size
            return "t${pcm.firstOrNull() ?: -1}"
        }
    }

    private val asr = FakeAsr()

    /** A 30 s recording as chunk 0 (0..20 s) and chunk 1 (15..30 s, 5 s overlap), the sample of second s having the value s. */
    private fun rows(text: Boolean = false) = listOf(
        ChunkRow(rid, 0, 0, 20_000, 0, if (text) "alpha beta" else null),
        ChunkRow(rid, 1, 15_000, 30_000, 5_000, if (text) "gamma delta" else null),
    )

    private fun chunk(idx: Int): AudioChunk {
        val (start, end, overlap) = if (idx == 0) Triple(0L, 20_000L, 0L) else Triple(15_000L, 30_000L, 5_000L)
        return AudioChunk(idx, start, end, overlap, ShortArray(((end - start) * 16).toInt()) { ((start * 16 + it) / 16_000).toShort() })
    }

    private fun flow(store: Store, ready: Boolean = true, files: List<Int> = listOf(0, 1)) =
        TranscribeFlow(store, { files }, { _, idx -> chunk(idx) }, { ready })

    private fun context(diarizer: FakeDiarizer?) =
        JobContext(ModelPool({ error("no llm") }, { error("no embed") }, { asr }, { "m" }, loadDiarizer = { diarizer ?: error("no diarizer") }), JobQueue(FakeJobRepo()))

    private fun turns(vararg t: Triple<Int, Int, Int>) = t.map { (s, e, sp) -> DiarTurn(s * 1000L, e * 1000L, sp) }

    @Test
    fun twoSpeakersGiveUtterancesLabelledS0AndS1() {
        val store = Store(rows())
        val diarizer = FakeDiarizer { turns(Triple(0, 10, 0), Triple(10, 20, 1), Triple(20, 30, 0)) }
        val out = flow(store).run(rid, "s", "en", context(diarizer))
        assertEquals(TranscribeFlow.Outcome(true), out)
        assertEquals(listOf(2), diarizer.hints) // AD-07: always two speakers
        assertEquals(listOf("S0" to "t0", "S1" to "t10", "S0" to "t20"), store.segments.map { it.speaker to it.text })
        assertEquals(listOf(1, 2, 3), store.segments.map { it.idx })
        // The turns are cut from the chunks without their repeated overlap: the 10 s turn is 10 s of audio, not more.
        assertEquals(listOf(160_000, 160_000, 160_000), asr.heard)
        assertTrue(store.rows.all { it.text == null }) // the chunk path was not used
    }

    @Test
    fun theTranscriptIsStoredWithTheHashOfTheAudio() {
        val diarized = Store(rows())
        flow(diarized).run(rid, "s", "en", context(FakeDiarizer { turns(Triple(0, 10, 0), Triple(10, 30, 1)) }))
        assertEquals(AudioHash.of(listOf(0, 1)) { chunk(it) }, diarized.audioShas.single())
        assertTrue(diarized.audioShas.single()!!.length == 64)
    }

    @Test
    fun anAudioChunkThatCannotBeHashedLeavesTheHashUnknownAndDoesNotFailTheJob() {
        val store = Store(rows(text = true))
        val flaky = TranscribeFlow(store, { listOf(0, 1) }, { _, idx -> if (idx == 1) throw java.io.IOException("x") else chunk(idx) }, { false })
        flaky.run(rid, "s", "en", context(null))
        assertEquals(listOf<String?>(null), store.audioShas)
    }

    @Test
    fun threeSpeakersKeepS0ToS2() {
        val store = Store(rows())
        val diarizer = FakeDiarizer { turns(Triple(0, 8, 0), Triple(8, 16, 1), Triple(16, 24, 2), Triple(24, 30, 0)) }
        flow(store).run(rid, "s", "en", context(diarizer))
        assertEquals(listOf("S0", "S1", "S2", "S0"), store.segments.map { it.speaker })
    }

    @Test
    fun neighbouringTurnsOfOneSpeakerBecomeOneUtterance() {
        val store = Store(rows())
        val diarizer = FakeDiarizer { turns(Triple(0, 5, 0), Triple(5, 10, 0), Triple(10, 30, 1)) }
        flow(store).run(rid, "s", "en", context(diarizer))
        assertEquals(listOf("S0", "S1"), store.segments.map { it.speaker })
    }

    private fun assertChunkPath(store: Store, reason: String, out: TranscribeFlow.Outcome) {
        assertEquals(TranscribeFlow.Outcome(false, reason), out)
        assertTrue(store.turns.isEmpty())
        assertTrue(store.segments.isNotEmpty() && store.segments.all { it.speaker == null })
        assertEquals(2, asr.heard.size) // one Canary call per chunk
        assertTrue(store.rows.all { it.text != null })
    }

    @Test
    fun aMissingModelTakesTheChunkPathWithoutLoadingTheDiarizer() {
        val store = Store(rows())
        val out = flow(store, ready = false).run(rid, "s", "en", context(null))
        assertChunkPath(store, TranscribeFlow.MODEL_MISSING, out)
    }

    @Test
    fun aFailingDiarizerTakesTheChunkPath() {
        val store = Store(rows())
        val out = flow(store).run(rid, "s", "en", context(FakeDiarizer { throw IllegalStateException("boom") }))
        assertChunkPath(store, TranscribeFlow.FAILED, out)
    }

    @Test
    fun aDiarizerThatCannotBeLoadedTakesTheChunkPath() {
        val store = Store(rows())
        val out = flow(store).run(rid, "s", "en", context(null))
        assertChunkPath(store, TranscribeFlow.FAILED, out)
    }

    @Test
    fun oneSpeakerTakesTheChunkPath() {
        val store = Store(rows())
        val out = flow(store).run(rid, "s", "en", context(FakeDiarizer { turns(Triple(0, 30, 0)) }))
        assertChunkPath(store, TranscribeFlow.ONE_SPEAKER, out)
    }

    @Test
    fun silenceIsOneSpeakerToo() {
        val store = Store(rows())
        val out = flow(store).run(rid, "s", "en", context(FakeDiarizer { emptyList() }))
        assertChunkPath(store, TranscribeFlow.ONE_SPEAKER, out)
    }

    @Test
    fun aRecordingOverTwoHoursTakesTheChunkPathWithoutDiarizing() {
        val long = listOf(ChunkRow(rid, 0, 0, 7_300_000, 0, null))
        val store = Store(long)
        val diarizer = FakeDiarizer { error("must not be called") }
        val out = TranscribeFlow(store, { listOf(0) }, { _, _ -> chunk(0) }, { true }).run(rid, "s", "en", context(diarizer))
        assertEquals(TranscribeFlow.Outcome(false, TranscribeFlow.TOO_LONG), out)
        assertEquals(0, diarizer.calls)
        assertTrue(store.segments.all { it.speaker == null })
    }

    @Test
    fun aStopOrACancelDuringDiarizationPropagatesAndStoresNothing() {
        for (t in listOf<Throwable>(JobStopped(), CancellationException("cancelled"))) {
            val store = Store(rows())
            try {
                flow(store).run(rid, "s", "en", context(FakeDiarizer { throw t }))
                fail("should propagate")
            } catch (e: Throwable) {
                assertTrue(e.toString(), if (t is JobStopped) e is JobStopped else e is CancellationException)
            }
            assertTrue(store.turns.isEmpty() && store.segments.isEmpty() && store.rows.all { it.text == null })
        }
    }

    @Test
    fun storedTurnsAreReusedAndOnlyTheUntranscribedOnesAreHeard() {
        val store = Store(rows())
        store.turns = listOf(TurnRow(0, 0, 10_000, "S0", "already"), TurnRow(1, 10_000, 20_000, "S1"), TurnRow(2, 20_000, 30_000, "S0"))
        val diarizer = FakeDiarizer { error("must not run again") }
        val out = flow(store).run(rid, "s", "en", context(diarizer))
        assertEquals(TranscribeFlow.Outcome(true), out)
        assertEquals(0, diarizer.calls)
        assertEquals(2, asr.heard.size)
        assertEquals(listOf("already", "t10", "t20"), store.segments.map { it.text })
    }

    @Test
    fun aRestartAfterTheLastTurnJustRebuildsTheUtterances() {
        val store = Store(rows())
        store.turns = listOf(TurnRow(0, 0, 15_000, "S0", "a b"), TurnRow(1, 15_000, 30_000, "S1", "c d"))
        flow(store).run(rid, "s", "en", context(null))
        assertTrue(asr.heard.isEmpty())
        assertEquals(listOf("S0" to "a b", "S1" to "c d"), store.segments.map { it.speaker to it.text })
    }

    @Test
    fun chunkTextsFromAnEarlierFallbackRunAreReusedAndDiarizationIsNotRetried() {
        val store = Store(rows(text = true))
        val diarizer = FakeDiarizer { error("must not run") }
        val out = flow(store).run(rid, "s", "en", context(diarizer))
        assertEquals(TranscribeFlow.Outcome(false), out)
        assertEquals(0, diarizer.calls)
        assertTrue(asr.heard.isEmpty())
        assertEquals("alpha beta gamma delta", store.segments.joinToString(" ") { it.text })
    }

    @Test
    fun anUnreadableChunkStillFailsTheJob() {
        val store = Store(rows())
        val flow = TranscribeFlow(store, { listOf(0, 1) }, { _, _ -> null }, { false })
        try {
            flow.run(rid, "s", "en", context(null))
            fail("should fail")
        } catch (e: JobFailure) {
            assertEquals("AUDIO", e.code)
        }
    }

    @Test
    fun aRecordingWithoutChunksLeavesTheSegmentsAlone() {
        val store = Store(emptyList())
        store.segments = listOf(SegmentRow(1, 0, 1, "kept"))
        val out = TranscribeFlow(store, { emptyList() }, { _, _ -> null }, { true }).run(rid, "s", "en", context(null))
        assertEquals(TranscribeFlow.Outcome(false), out)
        assertEquals(listOf("kept"), store.segments.map { it.text })
        assertNull(out.skipped)
    }
}
