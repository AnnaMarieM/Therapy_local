package com.therapylocal.app.testsupport

import com.therapylocal.app.ui.logic.UiText
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/** The real English resources (src/main/res/values) read from disk, so unit tests of text-producing logic check what the user reads. */
object EnglishTexts : UiText {
    override val locale: Locale = Locale.US

    private val names: Map<Int, String> by lazy {
        val out = HashMap<Int, String>()
        for (cls in listOf("string", "plurals")) {
            Class.forName("com.therapylocal.app.R\$$cls").fields.forEach { f -> out[f.getInt(null)] = f.name }
        }
        out
    }

    private val strings = HashMap<String, String>()
    private val plurals = HashMap<String, Map<String, String>>()

    init {
        val dir = listOf(File("src/main/res/values"), File("app/src/main/res/values")).first { it.isDirectory }
        dir.listFiles { f -> f.name.startsWith("strings") && f.extension == "xml" }!!.forEach { file ->
            val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
            val nodes = root.childNodes
            for (i in 0 until nodes.length) {
                val el = nodes.item(i) as? org.w3c.dom.Element ?: continue
                when (el.tagName) {
                    "string" -> strings[el.getAttribute("name")] = unescape(el.textContent)
                    "plurals" -> {
                        val items = el.getElementsByTagName("item")
                        plurals[el.getAttribute("name")] = (0 until items.length).associate {
                            val item = items.item(it) as org.w3c.dom.Element
                            item.getAttribute("quantity") to unescape(item.textContent)
                        }
                    }
                }
            }
        }
    }

    private fun unescape(raw: String): String {
        val t = if (raw.length >= 2 && raw.startsWith("\"") && raw.endsWith("\"")) raw.substring(1, raw.length - 1) else raw
        return t.replace("\\'", "'").replace("\\n", "\n").replace("\\\"", "\"")
    }

    private fun name(id: Int) = names[id] ?: error("unknown resource id $id")

    override fun str(id: Int, vararg args: Any): String = String.format(locale, strings[name(id)] ?: error("no string ${name(id)}"), *args)

    override fun plural(id: Int, quantity: Int, vararg args: Any): String {
        val items = plurals[name(id)] ?: error("no plurals ${name(id)}")
        return String.format(locale, items[if (quantity == 1) "one" else "other"] ?: error("no plural form"), *args)
    }
}
