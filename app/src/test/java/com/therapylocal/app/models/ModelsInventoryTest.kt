package com.therapylocal.app.models

import com.therapylocal.app.models.ModelsInventory.Entry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelsInventoryTest {
    private val e2b = ModelRegistry.GEMMA_E2B.files.single()
    private val embed = ModelRegistry.EMBED.files.single()

    private fun classify(vararg f: Entry) = ModelsInventory.classify(f.toList(), ModelRegistry.ALL)

    @Test fun anEmptyFolderHasNothing() {
        val r = classify()
        assertEquals(0, r.known)
        assertTrue(r.unknown.isEmpty())
    }

    @Test fun aFileOfTheRightSizeIsComplete() {
        val r = classify(Entry(e2b.name, e2b.size), Entry("embeddinggemma-2-740m/${embed.name}", embed.size))
        assertEquals(listOf(embed.let { "embeddinggemma-2-740m/${it.name}" }, e2b.name).sorted(), r.complete.map { it.name }.sorted())
        assertTrue(r.partial.isEmpty() && r.unknown.isEmpty())
    }

    @Test fun aShortFileAndAPartFileArePartial() {
        val r = classify(Entry(e2b.name, 10), Entry("embeddinggemma-2-740m/${embed.name}.part", 5))
        assertEquals(2, r.partial.size)
        assertTrue(r.complete.isEmpty() && r.unknown.isEmpty())
    }

    @Test fun theNestedModelsFolderIsOneUnknownItemWithItsTotalSize() {
        val r = classify(
            Entry(e2b.name, e2b.size),
            Entry("models/${e2b.name}", 100), Entry("models/gemma-4-E4B-it.litertlm", 200),
        )
        assertEquals(listOf(ModelsInventory.Item("models", 300)), r.unknown)
        assertEquals(1, r.complete.size)
    }

    @Test fun oldSpeechFoldersAreUnknownAndNeverTouched() {
        val r = classify(Entry("whisper-small/a.bin", 7), Entry("whisper-small/b.bin", 8), Entry("parakeet/x", 1))
        assertEquals(listOf(ModelsInventory.Item("parakeet", 1), ModelsInventory.Item("whisper-small", 15)).sortedBy { it.name }, r.unknown.sortedBy { it.name })
    }

    @Test fun aStrayFileInsideAKnownFolderIsNamedByItsPath() {
        val r = classify(Entry("canary-1b-v2/old.onnx", 3), Entry("stray.txt", 4))
        assertEquals(setOf("canary-1b-v2/old.onnx", "stray.txt"), r.unknown.map { it.name }.toSet())
    }

    @Test fun theLogTextHasNamesAndSizesOnly() {
        assertEquals("a:1,b/c:2", ModelsInventory.render(listOf(ModelsInventory.Item("a", 1), ModelsInventory.Item("b/c", 2))))
    }
}
