package com.therapylocal.app.facts

import com.therapylocal.app.data.VectorMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class CategoryClustererTest {

    @Test
    fun findCanonicalMatchesVariationsAndAccents() {
        assertEquals("Rodina a vztahy", CategoryTaxonomy.findCanonical("Rodina a vztahy", PromptLanguage.CS))
        assertEquals("Rodina a vztahy", CategoryTaxonomy.findCanonical("rodina a vztahy", PromptLanguage.CS))
        assertEquals("Práce a kariéra", CategoryTaxonomy.findCanonical("Prace a kariera", PromptLanguage.CS))
        assertEquals("Zdraví, tělo a spánek", CategoryTaxonomy.findCanonical("zdravi telo a spanek", PromptLanguage.CS))
        assertNull(CategoryTaxonomy.findCanonical("Neexistujici kategorie", PromptLanguage.CS))

        assertEquals("Work & Career", CategoryTaxonomy.findCanonical("work & career", PromptLanguage.EN))
        assertEquals("Family & Relationships", CategoryTaxonomy.findCanonical("family and relationships", PromptLanguage.EN))
    }

    @Test
    fun centroidCalculatesNormalisedMeanVector() {
        val v1 = floatArrayOf(1f, 0f, 0f)
        val v2 = floatArrayOf(0f, 1f, 0f)
        val c = CategoryClusterer.centroid(listOf(v1, v2))
        assertNotNull(c)
        val expected = 1f / sqrt(2f)
        assertEquals(expected, c!![0], 1e-4f)
        assertEquals(expected, c[1], 1e-4f)
        assertEquals(0f, c[2], 1e-4f)
    }

    @Test
    fun assignCategoryMergesIntoActiveCategoryWhenSimilarityExceedsThreshold() {
        val activeCentroids = mapOf(
            "Práce a kariéra" to floatArrayOf(1f, 0f, 0f),
            "Rodina a vztahy" to floatArrayOf(0f, 1f, 0f),
        )
        // Candidate vector very close to "Práce a kariéra" (cosine = 0.99)
        val candidateVec = floatArrayOf(0.99f, 0.05f, 0f)
        val fact = Fact(
            heading = "Nový projekt v práci",
            content = "Klient dostal na starost nový projekt a cítí se přetížený.",
            evidence = "mám nový projekt a nestíhám",
            category = "Projekty", // LLM suggested a synonym
        )

        val assigned = CategoryClusterer.assignCategory(
            fact = fact,
            factVec = candidateVec,
            activeCategories = activeCentroids,
            language = PromptLanguage.CS,
        )

        // Must stabilize and merge into the existing "Práce a kariéra", not creating a new "Projekty" category
        assertEquals("Práce a kariéra", assigned)
    }

    @Test
    fun assignCategoryActivatesCanonicalCategoryWhenFarFromExisting() {
        // Client only has "Práce a kariéra" active so far (session 1-3)
        val activeCentroids = mapOf(
            "Práce a kariéra" to floatArrayOf(1f, 0f, 0f),
        )
        // In session 5, client reveals an addiction topic: vector is orthogonal to work
        val candidateVec = floatArrayOf(0f, 0.98f, 0.05f)
        val canonicalAnchors = mapOf(
            "Závislosti a návyky" to floatArrayOf(0f, 1f, 0f),
            "Rodina a vztahy" to floatArrayOf(0f, 0f, 1f),
        )

        val fact = Fact(
            heading = "Problémy s alkoholem",
            content = "Klient přiznal, že každý večer pije lahev vína.",
            evidence = "piju každou noc lahev vína",
            category = "Závislosti",
        )

        val assigned = CategoryClusterer.assignCategory(
            fact = fact,
            factVec = candidateVec,
            activeCategories = activeCentroids,
            canonicalAnchors = canonicalAnchors,
            language = PromptLanguage.CS,
        )

        // Correctly activates the new canonical category "Závislosti a návyky"
        assertEquals("Závislosti a návyky", assigned)
    }

    @Test
    fun assignCategoryAcceptsCustomTopicWhenNotMatchingCanonical() {
        val activeCentroids = mapOf(
            "Práce a kariéra" to floatArrayOf(1f, 0f, 0f),
        )
        // Vector far from active categories
        val candidateVec = floatArrayOf(0f, 0f, 1f)
        val canonicalAnchors = mapOf(
            "Závislosti a návyky" to floatArrayOf(0f, 1f, 0f),
        )

        val fact = Fact(
            heading = "Soudní spor o dědictví",
            content = "Klient řeší složitý právní spor o pozemky po babičce.",
            evidence = "soudíme se o dědictví",
            category = "Právní spory",
        )

        val assigned = CategoryClusterer.assignCategory(
            fact = fact,
            factVec = candidateVec,
            activeCategories = activeCentroids,
            canonicalAnchors = canonicalAnchors,
            language = PromptLanguage.CS,
        )

        // Keeps custom clinical topic
        assertEquals("Právní spory", assigned)
    }

    @Test
    fun agglomerativeClusteringGroupsNearbyFactsAndSelectsMedoid() {
        data class Item(val id: Int, val title: String, val vec: FloatArray)

        // Cluster 1: work items
        val w1 = Item(1, "Práce v kanclu", floatArrayOf(1f, 0.05f, 0f))
        val w2 = Item(2, "Šéf v práci", floatArrayOf(0.98f, 0.08f, 0f))
        val w3 = Item(3, "Vyhoření", floatArrayOf(0.95f, 0.12f, 0f))

        // Cluster 2: sleep items
        val s1 = Item(4, "Nespavost", floatArrayOf(0f, 0.02f, 0.99f))
        val s2 = Item(5, "Brzké probouzení", floatArrayOf(0f, 0.05f, 0.97f))

        val items = listOf(w1, s1, w2, s2, w3)
        val clusters = CategoryClusterer.agglomerative(items, vecOf = { it.vec }, maxDistance = 0.35f)

        assertEquals("Should form exactly 2 thematic clusters", 2, clusters.size)

        val workCluster = clusters.first { it.items.contains(w1) }
        val sleepCluster = clusters.first { it.items.contains(s1) }

        assertEquals(3, workCluster.items.size)
        assertEquals(2, sleepCluster.items.size)

        // Medoid of work cluster is the most central work item
        assertTrue(workCluster.medoid in listOf(w1, w2, w3))
        assertTrue(sleepCluster.medoid in listOf(s1, s2))
    }
}

