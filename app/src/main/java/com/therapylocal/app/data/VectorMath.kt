package com.therapylocal.app.data

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/** Embedding helpers: brute-force top-k by cosine, and the little-endian float BLOB the database stores. Pure Kotlin, so unit-tested on the JVM. */
object VectorMath {
    fun toBlob(v: FloatArray): ByteArray {
        val bb = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        bb.asFloatBuffer().put(v)
        return bb.array()
    }

    fun fromBlob(b: ByteArray): FloatArray {
        val out = FloatArray(b.size / 4)
        ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(out)
        return out
    }

    /** 0 when either vector is all zeros. Vectors must have the same size. */
    fun cosine(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size) { "vector sizes differ: ${a.size} vs ${b.size}" }
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        if (na == 0.0 || nb == 0.0) return 0f
        return (dot / (sqrt(na) * sqrt(nb))).toFloat()
    }

    /** The [k] items closest to [query], best first. Items whose vector has another size (another embedding model) are skipped. */
    fun <T> topK(items: Iterable<Pair<T, FloatArray>>, query: FloatArray, k: Int): List<Pair<T, Float>> =
        items.filter { it.second.size == query.size }
            .map { (item, v) -> item to cosine(v, query) }
            .sortedByDescending { it.second }
            .take(k)
}
