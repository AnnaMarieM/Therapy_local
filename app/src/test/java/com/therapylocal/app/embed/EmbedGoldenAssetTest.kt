package com.therapylocal.app.embed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The reference vectors must belong to the model file the app downloads: a model bump without regenerating them fails here, not on a phone. */
class EmbedGoldenAssetTest {
    private val file = listOf("src/main/assets/embed_golden.json", "app/src/main/assets/embed_golden.json").map(::File).first { it.exists() || it.path.startsWith("src") }

    @Test
    fun theReferenceIsMadeForTheModelFileInTheRegistry() {
        assertTrue("missing ${file.absolutePath}; make it with BackendProbeActivity --es backend CPU --ez golden true", file.exists())
        val sha = Regex("\"sha256\"\\s*:\\s*\"([0-9a-fA-F]{64})\"").find(file.readText())?.groupValues?.get(1)
        assertEquals(EmbedGolden.expectedSha().lowercase(), sha?.lowercase())
    }

    @Test
    fun itHoldsEveryFixedInputOfTheSelfTest() {
        val t = file.readText()
        for (k in listOf("query", "related", "unrelated", "tone")) assertTrue("no vector '$k'", t.contains("\"$k\""))
    }
}
