package com.therapylocal.app.models

/** The Gemma 4 build used as the memory LLM (and Gemma speech recognition). [key] is the value stored in the settings. */
enum class GemmaVariant(val key: String, val title: String) {
    E2B("e2b", "Gemma 4 E2B"),
    E4B("e4b", "Gemma 4 E4B"),
}

/** Which Gemma build to use, from the `gemma_variant` setting. */
object ModelChoice {
    const val PREF = "gemma_variant"

    /**
     * Our own threshold: the model cards state no RAM requirement (E4B peaked at ~3.3 GB on CPU, E2B at ~1.7 GB). It is a little under
     * 12 GiB because a phone sold with 12 GB reports somewhat less as its total memory.
     */
    const val E4B_MIN_TOTAL_MEM = 11L shl 30

    /** An unset or unknown value keeps E2B, the build installed before the choice existed, so nothing large is downloaded unasked. */
    fun resolve(pref: String?): GemmaVariant = GemmaVariant.entries.firstOrNull { it.key == pref } ?: GemmaVariant.E2B

    /** The build suggested for a phone with [totalMem] bytes of RAM. */
    fun suggested(totalMem: Long): GemmaVariant = if (totalMem >= E4B_MIN_TOTAL_MEM) GemmaVariant.E4B else GemmaVariant.E2B

    fun other(variant: GemmaVariant): GemmaVariant = if (variant == GemmaVariant.E2B) GemmaVariant.E4B else GemmaVariant.E2B
}
