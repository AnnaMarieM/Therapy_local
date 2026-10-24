package com.therapylocal.app.ui.data

import java.text.Normalizer

/** Pure helpers for creating a client: turning a display name into a memory client id, and checking the form. */
object ClientInput {
    const val MAX_AGE = 120

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

    /** Null if the input is acceptable, else a short message. [ageText] may be empty (age is optional). */
    fun validate(name: String, ageText: String): String? {
        if (slug(name).isEmpty()) return "Enter a name"
        if (ageText.isNotBlank()) {
            val age = ageText.trim().toIntOrNull() ?: return "Age must be a number"
            if (age !in 1..MAX_AGE) return "Age must be between 1 and $MAX_AGE"
        }
        return null
    }
}
