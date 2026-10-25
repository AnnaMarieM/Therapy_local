package com.therapylocal.app.ui.data

import java.text.Normalizer

/** Pure helpers for creating a client: turning a display name into a memory client id, and checking the form. */
object ClientInput {
    /** "Jörg M." -> "jorgm": lowercase ascii letters and digits only, same as MemoryProcessor.clientId. Empty if nothing usable is left. */
    fun slug(name: String): String =
        Normalizer.normalize(name.trim(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "")

    /** [base] if it is free, else base2, base3, ... */
    fun uniqueId(base: String, taken: Set<String>): String {
        if (base !in taken) return base
        var n = 2
        while ("$base$n" in taken) n++
        return "$base$n"
    }

    /** Null if the input is acceptable, else a short message. */
    fun validate(name: String): String? {
        if (slug(name).isEmpty()) return "Enter a name"
        return null
    }
}
