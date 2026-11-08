package com.therapylocal.app.facts

import com.therapylocal.app.data.VectorMath
import kotlin.math.sqrt

/**
 * Clustering and category stabilization for client facts.
 *
 * Implements:
 * 1. Two-stage adaptive categorization:
 *    - Stabilizes against client's existing categories (similarity >= [MATCH_THRESHOLD]), preventing duplicate synonyms.
 *    - Allows new categories to emerge at any session when similarity to existing categories is low and matches canonical
 *      or new clinical topics (similarity >= [CANONICAL_THRESHOLD]).
 * 2. Hierarchical agglomerative clustering (Average Linkage) for thematic grouping across sessions, with medoid selection.
 *
 * Pure Kotlin, unit-tested on the JVM.
 */
object CategoryClusterer {
    /** Cosine similarity required to merge into an already active client category. */
    const val MATCH_THRESHOLD = 0.65f

    /** Cosine similarity required to activate an unactivated canonical category. */
    const val CANONICAL_THRESHOLD = 0.60f

    data class Cluster<T>(
        val items: List<T>,
        val medoid: T,
        val centroid: FloatArray,
    )

    /** Computes the normalised centroid (mean vector) of [vectors]. Returns null if empty. */
    fun centroid(vectors: List<FloatArray>): FloatArray? {
        if (vectors.isEmpty()) return null
        val dim = vectors.first().size
        val sum = DoubleArray(dim)
        for (v in vectors) {
            require(v.size == dim) { "Mismatched vector dimension: ${v.size} vs $dim" }
            for (i in 0 until dim) sum[i] += v[i].toDouble()
        }
        var norm = 0.0
        for (i in 0 until dim) norm += sum[i] * sum[i]
        norm = sqrt(norm)
        if (norm == 0.0) return FloatArray(dim)
        val out = FloatArray(dim)
        for (i in 0 until dim) out[i] = (sum[i] / norm).toFloat()
        return out
    }

    /**
     * Assigns a category to a candidate fact with embedding [factVec].
     *
     * @param fact The candidate fact (may have an LLM-suggested category)
     * @param factVec The embedding vector of the fact (heading + content)
     * @param activeCategories Map of category name to its centroid vector for this client
     * @param canonicalAnchors Optional map of canonical category name to its anchor embedding vector
     * @param language Client's prompt language ("cs" or "en")
     */
    fun assignCategory(
        fact: Fact,
        factVec: FloatArray?,
        activeCategories: Map<String, FloatArray>,
        canonicalAnchors: Map<String, FloatArray> = emptyMap(),
        language: String = PromptLanguage.CS,
    ): String {
        // 1. If we have a vector and active categories for this client, check if it matches an existing category.
        if (factVec != null && activeCategories.isNotEmpty()) {
            var bestMatch: String? = null
            var bestSimilarity = -1f
            for ((cat, centroid) in activeCategories) {
                if (centroid.size != factVec.size) continue
                val sim = VectorMath.cosine(factVec, centroid)
                if (sim > bestSimilarity) {
                    bestSimilarity = sim
                    bestMatch = cat
                }
            }
            if (bestSimilarity >= MATCH_THRESHOLD && bestMatch != null) {
                return bestMatch
            }
        }

        // 2. Check if the LLM-suggested category directly matches a canonical category name.
        if (fact.category.isNotBlank()) {
            val canonicalName = CategoryTaxonomy.findCanonical(fact.category, language)
            if (canonicalName != null) return canonicalName
        }

        // 3. If canonical anchor vectors are provided, check if it matches an unactivated canonical category.
        if (factVec != null && canonicalAnchors.isNotEmpty()) {
            var bestCanonical: String? = null
            var bestCanonicalSim = -1f
            for ((cat, anchorVec) in canonicalAnchors) {
                if (anchorVec.size != factVec.size) continue
                val sim = VectorMath.cosine(factVec, anchorVec)
                if (sim > bestCanonicalSim) {
                    bestCanonicalSim = sim
                    bestCanonical = cat
                }
            }
            if (bestCanonicalSim >= CANONICAL_THRESHOLD && bestCanonical != null) {
                return bestCanonical
            }
        }

        // 4. If the LLM suggested a custom non-blank category name, accept it as a new custom topic.
        val suggested = fact.category.trim()
        if (suggested.isNotBlank() && suggested.length in 2..50) {
            return suggested
        }

        // 5. Fallback to default canonical category for the language.
        return CategoryTaxonomy.defaultCategory(language)
    }

    /**
     * Performs Agglomerative Hierarchical Clustering (Average Linkage) on [items] with vector extractor [vecOf].
     * Merges clusters until the distance between any two clusters exceeds [maxDistance]
     * (e.g. 0.35f distance corresponds to cosine similarity 0.65f).
     */
    fun <T> agglomerative(
        items: List<T>,
        vecOf: (T) -> FloatArray,
        maxDistance: Float = 1.0f - MATCH_THRESHOLD,
    ): List<Cluster<T>> {
        if (items.isEmpty()) return emptyList()
        if (items.size == 1) {
            val v = vecOf(items[0])
            return listOf(Cluster(items, items[0], v))
        }

        val n = items.size
        val vectors = items.map(vecOf)
        // Pairwise distance matrix (1 - cosine)
        val dist = Array(n) { FloatArray(n) }
        for (i in 0 until n) {
            dist[i][i] = 0f
            for (j in i + 1 until n) {
                val d = (1f - VectorMath.cosine(vectors[i], vectors[j])).coerceAtLeast(0f)
                dist[i][j] = d
                dist[j][i] = d
            }
        }

        // Each cluster is a list of original item indices
        val clusters = ArrayList<MutableList<Int>>()
        for (i in 0 until n) clusters.add(mutableListOf(i))

        while (clusters.size > 1) {
            var bestI = -1
            var bestJ = -1
            var minAvgDist = Float.MAX_VALUE

            for (i in 0 until clusters.size) {
                val c1 = clusters[i]
                for (j in i + 1 until clusters.size) {
                    val c2 = clusters[j]
                    var sumDist = 0.0
                    for (idx1 in c1) {
                        for (idx2 in c2) {
                            sumDist += dist[idx1][idx2]
                        }
                    }
                    val avgDist = (sumDist / (c1.size * c2.size)).toFloat()
                    if (avgDist < minAvgDist) {
                        minAvgDist = avgDist
                        bestI = i
                        bestJ = j
                    }
                }
            }

            if (minAvgDist > maxDistance || bestI < 0 || bestJ < 0) {
                break
            }

            // Merge cluster bestJ into bestI and remove bestJ
            clusters[bestI].addAll(clusters[bestJ])
            clusters.removeAt(bestJ)
        }

        // Build result clusters with centroid and medoid
        return clusters.map { memberIndices ->
            val memberItems = memberIndices.map { items[it] }
            val memberVecs = memberIndices.map { vectors[it] }
            val centroidVec = centroid(memberVecs) ?: vectors[memberIndices.first()]

            // Medoid: item with minimum average distance to all other members in the cluster
            var bestMedoid = memberItems.first()
            var minTotalDist = Float.MAX_VALUE
            for (i in memberIndices) {
                var total = 0f
                for (j in memberIndices) {
                    total += dist[i][j]
                }
                if (total < minTotalDist) {
                    minTotalDist = total
                    bestMedoid = items[i]
                }
            }

            Cluster(memberItems, bestMedoid, centroidVec)
        }
    }
}

