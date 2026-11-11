package com.therapylocal.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Staleness by comparing provenance with the current inputs, and the order of a rebuild (docs/AD/AD-10.md). */
class ProvenanceTest {
    private val current = Provenance("sha-2", 3, "prompt-b", "gemma-4")
    private val stored = Provenance("sha-2", 3, "prompt-b", "gemma-4")
    private val expect = Expectation("prompt-b", "gemma-4")

    @Test
    fun sameInputsAreNotStale() = assertFalse(Staleness.isStale(Derived.FACTS, stored, current, expect))

    @Test
    fun anotherTranscriptIsStale() = assertTrue(Staleness.isStale(Derived.FACTS, stored.copy(transcriptSha = "sha-1"), current, expect))

    @Test
    fun anotherPromptOrModelIsStale() {
        assertTrue(Staleness.isStale(Derived.FACTS, stored.copy(promptVersion = "prompt-a"), current, expect))
        assertTrue(Staleness.isStale(Derived.FACTS, stored.copy(model = "gemma-3"), current, expect))
    }

    @Test
    fun promptAndModelAreOnlyComparedWhenExpected() {
        assertFalse(Staleness.isStale(Derived.FACTS, stored.copy(promptVersion = "prompt-a", model = "gemma-3"), current))
        assertTrue(Staleness.isStale(Derived.FACTS, stored.copy(promptVersion = "prompt-a"), current, Expectation(promptVersion = "prompt-b")))
        assertFalse(Staleness.isStale(Derived.FACTS, stored.copy(model = "gemma-3"), current, Expectation(promptVersion = "prompt-b")))
    }

    @Test
    fun aSpeakerMapChangeMattersOnlyToKindsThatUseTheMap() {
        val older = stored.copy(speakerMapVersion = 2)
        assertFalse(Staleness.isStale(Derived.FACTS, older, current, expect))
        assertFalse(Staleness.isStale(Derived.WINDOWS, older, current, expect))
        // A kind that reads roles (a session summary, AD-10) is stale after a swap, and the version 0 -> 1 step (a map appeared) counts too.
        assertTrue(Staleness.isStale(true, older, current, expect))
        assertTrue(Staleness.isStale(true, stored.copy(speakerMapVersion = Provenance.NO_MAP), current, expect))
        assertFalse(Staleness.isStale(true, stored, current, expect))
        assertFalse(Staleness.isStale(true, stored.copy(speakerMapVersion = Provenance.UNKNOWN_VERSION), current, expect))
    }

    @Test
    fun unknownStoredPartsAreNeverStale() {
        val unknown = Provenance(Provenance.UNKNOWN_TEXT, Provenance.UNKNOWN_VERSION, Provenance.UNKNOWN_TEXT, Provenance.UNKNOWN_TEXT)
        assertFalse(Staleness.isStale(Derived.FACTS, unknown, current, expect))
        // Known parts of a partly known row still count.
        assertTrue(Staleness.isStale(Derived.WINDOWS, unknown.copy(model = "old-embedder"), current, Expectation(model = "embedder")))
    }

    @Test
    fun noTranscriptNowAndUnknownStoredIsNotStale() =
        assertFalse(Staleness.isStale(Derived.WINDOWS, stored.copy(transcriptSha = Provenance.UNKNOWN_TEXT), current.copy(transcriptSha = Provenance.UNKNOWN_TEXT), expect))

    @Test
    fun everyDerivedKindIsAnotherTable() {
        assertEquals(Derived.entries.size, Derived.entries.map { it.table }.toSet().size)
        assertEquals(listOf("windows", "facts"), Derived.entries.map { it.table })
    }

    @Test
    fun theColumnsAreTheOnesTheStoreWritesAndReads() {
        assertEquals(
            listOf("prov_transcript_sha", "prov_speaker_map_version", "prov_prompt_version", "prov_model", "stale"),
            Provenance.COLUMNS.map { it.substringBefore(' ') },
        )
        // Every column has a default, so an ALTER TABLE ADD COLUMN on a table with rows works.
        assertTrue(Provenance.COLUMNS.all { it.contains("DEFAULT") })
    }

    @Test
    fun rebuildQueuesEachClientsSessionsOldestFirstAndSkipsBusyOnes() {
        val stale = listOf(
            SessionStamp("b3", "b", 30), SessionStamp("a2", "a", 20), SessionStamp("b1", "b", 10),
            SessionStamp("a1", "a", 10), SessionStamp("a3", "a", 30), SessionStamp("b2", "b", 20),
        )
        assertEquals(listOf("a1", "a2", "b1", "b2", "b3"), Rebuild.order(stale, busy = setOf("a3")))
    }

    @Test
    fun rebuildOrderBreaksTiesByIdAndIsEmptyForNothingStale() {
        assertEquals(listOf("x", "y"), Rebuild.order(listOf(SessionStamp("y", "a", 5), SessionStamp("x", "a", 5)), emptySet()))
        assertEquals(emptyList<String>(), Rebuild.order(emptyList(), emptySet()))
    }
}
