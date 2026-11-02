package com.therapylocal.app.i18n

/**
 * Every language the app can show. To add one: create res/values-<tag>/strings.xml, add the tag to res/xml/locales_config.xml,
 * and add a line here. LanguageParityTest fails until all three agree.
 * [nativeName] is deliberately not a string resource: each language is always written in itself.
 */
data class AppLanguage(val tag: String, val nativeName: String)

object Languages {
    /** The language used when the phone's language is not supported; res/values holds its text. */
    const val FALLBACK = "en"

    val all = listOf(
        AppLanguage("en", "English"),
        AppLanguage("cs", "Čeština"),
    )

    fun byTag(tag: String?): AppLanguage? = all.firstOrNull { it.tag.equals(tag, ignoreCase = true) }

    /** The supported language matching a phone language such as "cs" or "cs-CZ", or the fallback. */
    fun match(phoneTag: String?): AppLanguage {
        val primary = phoneTag?.substringBefore('-')?.substringBefore('_')
        return byTag(primary) ?: byTag(FALLBACK)!!
    }
}
