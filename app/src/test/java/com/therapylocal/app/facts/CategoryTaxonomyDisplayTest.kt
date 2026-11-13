package com.therapylocal.app.facts

import org.junit.Assert.assertEquals
import org.junit.Test

class CategoryTaxonomyDisplayTest {
    @Test
    fun theListsAreParallel() {
        assertEquals(CategoryTaxonomy.CANONICAL_EN.size, CategoryTaxonomy.CANONICAL_CS.size)
    }

    @Test
    fun everyEnglishCanonicalCategoryMapsToTheCzechOneAtTheSamePlace() {
        for ((i, en) in CategoryTaxonomy.CANONICAL_EN.withIndex()) {
            assertEquals(en.name, CategoryTaxonomy.CANONICAL_CS[i].name, CategoryTaxonomy.display(en.name, "cs"))
        }
    }

    @Test
    fun theEnglishDefaultMapsToTheCzechDefault() {
        assertEquals(CategoryTaxonomy.defaultCategory("cs"), CategoryTaxonomy.display(CategoryTaxonomy.defaultCategory("en"), "cs"))
    }

    @Test
    fun unknownAndBlankNamesPassThrough() {
        assertEquals("Gaming habits", CategoryTaxonomy.display("Gaming habits", "cs"))
        assertEquals("", CategoryTaxonomy.display("", "cs"))
    }

    @Test
    fun englishIsShownAsItIs() {
        assertEquals("Work & Career", CategoryTaxonomy.display("Work & Career", "en"))
    }
}
