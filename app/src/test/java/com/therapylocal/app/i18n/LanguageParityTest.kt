package com.therapylocal.app.i18n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Adding a language means a values-xx folder, a locales_config entry and a Languages entry; this fails until all agree and nothing is missing. */
class LanguageParityTest {
    private val res = File("src/main/res")

    private class Entry(val placeholders: List<String>, val quantities: Set<String>)

    private fun load(dir: File): Map<String, Entry> {
        val out = HashMap<String, Entry>()
        dir.listFiles { f -> f.name.startsWith("strings") && f.extension == "xml" }.orEmpty().sortedBy { it.name }.forEach { file ->
            val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
            val nodes = root.childNodes
            for (i in 0 until nodes.length) {
                val e = nodes.item(i) as? Element ?: continue
                if (e.getAttribute("translatable") == "false") continue
                val name = e.getAttribute("name")
                val key = "${e.tagName}:$name"
                assertTrue("Duplicate $key in ${dir.name}", key !in out)
                val items = if (e.tagName == "plurals") (0 until e.childNodes.length).mapNotNull { e.childNodes.item(it) as? Element } else listOf(e)
                val text = items.joinToString("\u0000") { it.textContent }
                val placeholders = Regex("%(?:\\d+\\$)?[a-zA-Z]").findAll(text.replace("%%", "")).map { it.value }.toList()
                if (e.tagName == "string") assertTrue("$key in ${dir.name}: use positional placeholders (%1\$s)", placeholders.all { it.contains('$') })
                out[key] = Entry(
                    placeholders.map { it.substringBefore('$') }.distinct().sorted(),
                    if (e.tagName == "plurals") items.map { it.getAttribute("quantity") }.toSet() else emptySet(),
                )
            }
        }
        return out
    }

    private fun requiredQuantities(tag: String) = when (tag) {
        "cs", "sk", "pl", "ru", "uk" -> setOf("one", "few", "many", "other")
        else -> setOf("one", "other")
    }

    @Test fun everyLanguageHasTheSameStringsAsTheDefault() {
        val base = load(File(res, "values"))
        assertTrue("default strings missing", base.isNotEmpty())
        Languages.all.filter { it.tag != Languages.FALLBACK }.forEach { lang ->
            val dir = File(res, "values-${lang.tag}")
            assertTrue("${dir.path} is missing", dir.isDirectory)
            val other = load(dir)
            assertEquals("Keys differ in ${lang.tag}: missing ${base.keys - other.keys}, extra ${other.keys - base.keys}", base.keys, other.keys)
            base.forEach { (key, entry) ->
                assertEquals("Placeholders differ for $key in ${lang.tag}", entry.placeholders, other.getValue(key).placeholders)
                if (key.startsWith("plurals:")) {
                    assertEquals("Plural quantities for $key in ${lang.tag}", requiredQuantities(lang.tag), other.getValue(key).quantities)
                }
            }
        }
    }

    @Test fun defaultPluralsHaveOneAndOther() {
        load(File(res, "values")).filterKeys { it.startsWith("plurals:") }.forEach { (key, entry) ->
            assertEquals("Plural quantities for $key in default", requiredQuantities(Languages.FALLBACK), entry.quantities)
        }
    }

    @Test fun noStrayLanguageFolders() {
        val tags = Languages.all.map { it.tag }.toSet()
        val folders = res.listFiles { f -> f.isDirectory && f.name.startsWith("values-") }.orEmpty()
            .map { it.name.removePrefix("values-") }
            .filter { it.length in 2..3 || it.contains('-') && !it.startsWith("night") }
            .filter { Regex("[a-z]{2,3}(-r[A-Z]{2})?").matches(it) }
        folders.forEach { assertTrue("values-$it exists but $it is not in Languages.all", it.substringBefore('-') in tags) }
    }

    @Test fun localesConfigListsEveryLanguage() {
        val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(res, "xml/locales_config.xml")).documentElement
        val listed = (0 until root.childNodes.length).mapNotNull { root.childNodes.item(it) as? Element }
            .map { it.getAttribute("android:name") }.toSet()
        assertEquals(Languages.all.map { it.tag }.toSet(), listed)
    }

    @Test fun matchesPhoneLanguageOrFallsBackToEnglish() {
        assertEquals("cs", Languages.match("cs").tag)
        assertEquals("cs", Languages.match("cs-CZ").tag)
        assertEquals("cs", Languages.match("cs_CZ").tag)
        assertEquals("en", Languages.match("de").tag)
        assertEquals("en", Languages.match(null).tag)
    }
}
