package com.therapylocal.app.ui.logic

import java.util.Locale

data class DisambiguationTarget(
    val id: String,
    val firstName: String,
    val lastName: String,
    val createdAt: Long = 0L,
)

object ClientDisambiguation {

    /**
     * Given a list of clients, returns a map of id -> disambiguated display name.
     * E.g.:
     *   "John Doe" + "John Dill" -> "John Do." and "John Di."
     *   "Karel Novotny" + "Karel Novak" -> "Karel Novo." and "Karel Nova."
     *   "Jan Nina" + two "Jan Novak" -> "Jan Ni.", "Jan No. I.", and "Jan No. II."
     */
    fun disambiguate(clients: List<DisambiguationTarget>): Map<String, String> {
        if (clients.isEmpty()) return emptyMap()

        val result = mutableMapOf<String, String>()

        // Group by normalized first name (case-insensitive)
        val byFirst = clients.groupBy { normalize(it.firstName) }

        for ((_, group) in byFirst) {
            val formattedFirst = group.firstOrNull { it.firstName.isNotBlank() }?.firstName?.trim()?.replaceFirstChar { it.titlecase(Locale.getDefault()) }
                ?: group.first().firstName.trim()

            // Group by normalized last name within the same first name
            val byLast = group.groupBy { normalize(it.lastName.trimEnd('.')) }

            // Clients with no surname
            val noLast = byLast[""] ?: emptyList()
            if (noLast.size == 1) {
                result[noLast[0].id] = formattedFirst
            } else if (noLast.size > 1) {
                val sortedNoLast = noLast.sortedWith(compareBy({ it.createdAt }, { it.id }))
                sortedNoLast.forEachIndexed { idx, client ->
                    result[client.id] = "$formattedFirst ${toRoman(idx + 1)}."
                }
            }

            // Clients with a surname
            val withLast = byLast.filterKeys { it.isNotEmpty() }
            if (withLast.isEmpty()) continue

            // For each distinct surname in this first-name group, find the shortest prefix needed to distinguish from other surnames
            val prefixLengths = mutableMapOf<String, Int>()
            for (normLast in withLast.keys) {
                var maxCommon = 0
                for (otherLast in withLast.keys) {
                    if (otherLast == normLast) continue
                    val common = commonPrefixLength(normLast, otherLast)
                    if (common > maxCommon) maxCommon = common
                }
                // Shortest prefix needs maxCommon + 1 characters (at least 1, at most surname length)
                val cleanSample = withLast[normLast]!!.first().lastName.trim().trimEnd('.')
                val needed = (maxCommon + 1).coerceIn(1, cleanSample.length.coerceAtLeast(1))
                prefixLengths[normLast] = needed
            }

            for ((normLast, clientsWithThisLast) in withLast) {
                val sampleLast = clientsWithThisLast.first().lastName.trim().trimEnd('.')
                val formattedLast = formatSurnamePrefix(sampleLast, prefixLengths[normLast] ?: 1)

                if (clientsWithThisLast.size == 1) {
                    result[clientsWithThisLast[0].id] = "$formattedFirst $formattedLast"
                } else {
                    // Full duplicates: sort chronologically by createdAt (then id) and assign Roman numerals
                    val sorted = clientsWithThisLast.sortedWith(compareBy({ it.createdAt }, { it.id }))
                    sorted.forEachIndexed { idx, client ->
                        result[client.id] = "$formattedFirst $formattedLast ${toRoman(idx + 1)}."
                    }
                }
            }
        }

        return result
    }

    /**
     * Previews what a new client's display name will be when added alongside [existingClients].
     */
    fun preview(firstName: String, lastName: String, existingClients: List<DisambiguationTarget>): String {
        val f = firstName.trim()
        val l = lastName.trim()
        if (f.isEmpty()) return ""
        val tempId = "__new_preview__"
        val target = DisambiguationTarget(tempId, f, l, System.currentTimeMillis())
        val all = existingClients.filter { it.id != tempId } + target
        return disambiguate(all)[tempId] ?: f
    }

    private fun normalize(s: String): String = s.trim().lowercase(Locale.getDefault())

    private fun commonPrefixLength(a: String, b: String): Int {
        var i = 0
        val minLen = minOf(a.length, b.length)
        while (i < minLen && a[i].lowercaseChar() == b[i].lowercaseChar()) {
            i++
        }
        return i
    }

    private fun formatSurnamePrefix(surname: String, length: Int): String {
        val clean = surname.trim().trimEnd('.')
        val len = length.coerceIn(1, clean.length.coerceAtLeast(1))
        val prefix = clean.substring(0, len)
        val formatted = prefix.lowercase(Locale.getDefault()).replaceFirstChar { it.titlecase(Locale.getDefault()) }
        return "$formatted."
    }

    fun toRoman(number: Int): String {
        require(number in 1..3999) { "Number out of Roman numeral range (1..3999): $number" }
        val romanNumerals = listOf(
            1000 to "M", 900 to "CM", 500 to "D", 400 to "CD",
            100 to "C", 90 to "XC", 50 to "L", 40 to "XL",
            10 to "X", 9 to "IX", 5 to "V", 4 to "IV", 1 to "I"
        )
        var n = number
        val sb = StringBuilder()
        for ((value, sym) in romanNumerals) {
            while (n >= value) {
                sb.append(sym)
                n -= value
            }
        }
        return sb.toString()
    }
}

