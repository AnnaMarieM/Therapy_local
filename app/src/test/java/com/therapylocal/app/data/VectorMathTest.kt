package com.therapylocal.app.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VectorMathTest {
    @Test
    fun blobIsLittleEndianFloat32() {
        // 1.0f is 0x3F800000, so little-endian bytes 00 00 80 3F.
        assertArrayEquals(byteArrayOf(0, 0, 0x80.toByte(), 0x3F), VectorMath.toBlob(floatArrayOf(1f)))
        assertEquals(12, VectorMath.toBlob(FloatArray(3)).size)
    }

    @Test
    fun blobRoundTripKeepsEveryValue() {
        val v = floatArrayOf(0f, -0.5f, 1e-30f, 3.4e38f, Float.MIN_VALUE, -123.456f)
        assertArrayEquals(v, VectorMath.fromBlob(VectorMath.toBlob(v)), 0f)
        assertEquals(0, VectorMath.fromBlob(ByteArray(0)).size)
    }

    @Test
    fun cosineIgnoresLengthAndKeepsSign() {
        assertEquals(1f, VectorMath.cosine(floatArrayOf(1f, 2f), floatArrayOf(3f, 6f)), 1e-6f)
        assertEquals(-1f, VectorMath.cosine(floatArrayOf(1f, 2f), floatArrayOf(-2f, -4f)), 1e-6f)
        assertEquals(0f, VectorMath.cosine(floatArrayOf(1f, 0f), floatArrayOf(0f, 5f)), 1e-6f)
        // 45 degrees
        assertEquals(0.70710677f, VectorMath.cosine(floatArrayOf(1f, 0f), floatArrayOf(1f, 1f)), 1e-6f)
    }

    @Test
    fun cosineOfZeroVectorIsZeroNotNaN() {
        assertEquals(0f, VectorMath.cosine(floatArrayOf(0f, 0f), floatArrayOf(1f, 1f)), 0f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun cosineRejectsDifferentSizes() {
        VectorMath.cosine(floatArrayOf(1f), floatArrayOf(1f, 0f))
    }

    @Test
    fun topKReturnsClosestFirstAndAtMostK() {
        val q = floatArrayOf(1f, 0f)
        val items = listOf(
            "far" to floatArrayOf(-1f, 0f),
            "near" to floatArrayOf(1f, 0.1f),
            "mid" to floatArrayOf(1f, 1f),
            "exact" to floatArrayOf(2f, 0f),
        )
        assertEquals(listOf("exact", "near", "mid"), VectorMath.topK(items, q, 3).map { it.first })
        assertEquals(listOf("exact", "near", "mid", "far"), VectorMath.topK(items, q, Int.MAX_VALUE).map { it.first })
        val scores = VectorMath.topK(items, q, 4).map { it.second }
        assertEquals(1f, scores.first(), 1e-6f)
        assertEquals(-1f, scores.last(), 1e-6f)
    }

    @Test
    fun topKSkipsVectorsOfAnotherModel() {
        val items = listOf("other" to floatArrayOf(1f, 0f, 0f), "same" to floatArrayOf(0f, 1f))
        assertEquals(listOf("same"), VectorMath.topK(items, floatArrayOf(1f, 0f), 5).map { it.first })
        assertTrue(VectorMath.topK(emptyList<Pair<String, FloatArray>>(), floatArrayOf(1f), 5).isEmpty())
    }
}
