package com.therapylocal.app.data

import org.json.JSONObject

/** What a speaker is in the session. [json] is the name stored in `speaker_maps.map_json`. */
enum class Role(val json: String) { THERAPIST("therapist"), PATIENT("patient"), OTHER("other") }

/**
 * The map from raw diarization ids ("S0") to roles, and its JSON form `{"S0":"therapist","S1":"patient"}`. Utterances keep the raw ids and roles
 * are resolved at read time, so swapping roles never rewrites the transcript. Pure Kotlin apart from org.json.
 */
object SpeakerMaps {
    fun encode(map: Map<String, Role>): String = JSONObject().also { o -> for (k in map.keys.sorted()) o.put(k, map.getValue(k).json) }.toString()

    /** Empty for malformed JSON; a role name that is not known counts as [Role.OTHER]. */
    fun decode(json: String): Map<String, Role> = try {
        val o = JSONObject(json)
        o.keys().asSequence().sorted().associateWith { k -> Role.entries.firstOrNull { it.json == o.optString(k).trim().lowercase() } ?: Role.OTHER }
    } catch (_: Exception) {
        emptyMap()
    }

    /** A swap makes sense only with exactly one therapist and one patient. */
    fun canSwap(map: Map<String, Role>): Boolean = map.values.count { it == Role.THERAPIST } == 1 && map.values.count { it == Role.PATIENT } == 1

    /** Therapist and patient trade places, everything else stays; the map is returned unchanged when [canSwap] is false. */
    fun swap(map: Map<String, Role>): Map<String, Role> =
        if (!canSwap(map)) map else map.mapValues { (_, r) -> when (r) { Role.THERAPIST -> Role.PATIENT; Role.PATIENT -> Role.THERAPIST; else -> r } }
}
