package com.therapylocal.app.embed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class EmbedGoldenTest {
    private val ref = EmbedGolden.Reference(mapOf("query" to floatArrayOf(1f, 0f, 0f, 0f)))

    @Test
    fun theSameVectorMatches() = assertNull(ref.mismatch("query", floatArrayOf(2f, 0f, 0f, 0f)))

    @Test
    fun aVectorThatRanksRightButIsWrongFails() {
        // cosine 0.9: far above "unrelated sentences" noise (0.75-0.89 is what unrelated facts score), still not the CPU's output
        val v = floatArrayOf(0.9f, 0.436f, 0f, 0f)
        assertNotNull(ref.mismatch("query", v))
    }

    @Test
    fun zeroOrWrongSizedOutputFails() {
        assertNotNull(ref.mismatch("query", floatArrayOf(0f, 0f, 0f, 0f)))
        assertNotNull(ref.mismatch("query", floatArrayOf(1f, 0f)))
    }

    @Test
    fun anInputWithoutAReferenceIsNotJudged() = assertNull(ref.mismatch("tone", floatArrayOf(0f)))

    @Test
    fun cosineIsScaleFree() = assertEquals(1f, EmbedGolden.cosine(floatArrayOf(3f, 4f), floatArrayOf(6f, 8f)), 1e-6f)
}
