package com.therapylocal.app.translate

import com.therapylocal.app.config.MemoryConfig
import com.therapylocal.app.config.ProcessingLanguage
import com.therapylocal.app.facts.PromptLanguage
import com.therapylocal.app.facts.Prompts

/** Which language the pipeline works in and what that implies (docs/AD/AD-17.md). Pure Kotlin. */
object Pivot {
    const val EN = PromptLanguage.EN

    /** Version tag of the pivot path; see [tag]. */
    const val TAG_PIVOT = "pivot-en"
    const val TAG_NATIVE = "native"

    /** The transcript is translated to English first: pivot mode and a client who does not speak English. */
    fun translatesInput(mode: ProcessingLanguage, clientLanguage: String): Boolean =
        mode == ProcessingLanguage.English && !clientLanguage.equals(EN, ignoreCase = true)

    /** The prompts are always the English ones in pivot mode; otherwise as before AD-17. */
    fun promptLanguage(mode: ProcessingLanguage, appLanguage: String, clientLanguage: String): String =
        if (mode == ProcessingLanguage.English) PromptLanguage.EN else PromptLanguage.choose(appLanguage, clientLanguage)

    /** Facts and summaries are shown in the app's language: in pivot mode they are stored in English and translated when it is not English. */
    fun translatesOutput(mode: ProcessingLanguage, appLanguage: String): Boolean =
        mode == ProcessingLanguage.English && !appLanguage.equals(EN, ignoreCase = true)

    /**
     * Part of every prompt version and of the window cut version, so switching the mode marks windows, facts and summaries stale (AD-10) with no
     * staleness code of its own. An English client is processed the same way in both modes, so it keeps one tag.
     */
    fun tag(mode: ProcessingLanguage, clientLanguage: String): String = if (translatesInput(mode, clientLanguage)) TAG_PIVOT else TAG_NATIVE

    /** [Prompts.version] over [texts] and the pivot [tag]. */
    fun version(tag: String, vararg texts: String): String = Prompts.version(*texts, tag)

    /** The window cut version with the pivot [tag]. */
    fun windowCutVersion(tag: String): String = "${MemoryConfig.WINDOW_CUT_VERSION}-$tag"
}
