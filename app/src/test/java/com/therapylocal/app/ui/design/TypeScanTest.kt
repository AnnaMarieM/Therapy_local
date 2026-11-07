package com.therapylocal.app.ui.design

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** AD-05: no text in the product draws with a system font, because the system font is a different font on every ROM. */
class TypeScanTest {
    private val styles = mapOf(
        "Hero" to Type.Hero, "Statement" to Type.Statement, "Title" to Type.Title, "Lead" to Type.Lead, "Body" to Type.Body,
        "BodySmall" to Type.BodySmall, "Action" to Type.Action, "Label" to Type.Label,
    )

    @Test fun everyTextStyleUsesABundledFamilyAndStatesItsWeight() {
        for ((n, s) in styles) {
            assertTrue("$n: family is not bundled", s.fontFamily in Type.Bundled)
            assertTrue("$n: weight is not explicit", s.fontWeight != null)
        }
    }

    @Test fun everyMaterialSlotUsesABundledFamily() {
        val t = paperTypography()
        val slots = listOf(
            t.displayLarge, t.displayMedium, t.displaySmall, t.headlineLarge, t.headlineMedium, t.headlineSmall, t.titleLarge, t.titleMedium,
            t.titleSmall, t.bodyLarge, t.bodyMedium, t.bodySmall, t.labelLarge, t.labelMedium, t.labelSmall,
        )
        slots.forEachIndexed { i, s -> assertTrue("slot $i has a system family", s.fontFamily in Type.Bundled) }
    }

    /** Debug screens (the developer harness) are exempt; nothing a user sees is. */
    private val exempt = setOf("MainActivity.kt", "MemoryTab.kt")

    @Test fun noSourceNamesASystemFontFamily() {
        val root = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
        val bad = Regex("""FontFamily\.(Default|SansSerif|Serif|Monospace|Cursive)|Typeface\.(DEFAULT|SANS_SERIF|SERIF|MONOSPACE)|fontFamily\s*=\s*null""")
        val hits = root.walkTopDown().filter { it.isFile && it.extension == "kt" && it.name !in exempt }
            .flatMap { f -> f.readLines().withIndex().filter { bad.containsMatchIn(it.value) }.map { "${f.name}:${it.index + 1}" } }.toList()
        assertTrue("system font families in product code: $hits", hits.isEmpty())
    }
}
