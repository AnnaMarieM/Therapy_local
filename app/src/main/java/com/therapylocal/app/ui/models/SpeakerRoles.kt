package com.therapylocal.app.ui.models

import com.therapylocal.app.data.Role
import com.therapylocal.app.data.SpeakerMapRow
import com.therapylocal.app.data.SpeakerMaps
import org.json.JSONArray

/**
 * Who is the therapist and who the patient in one session, as the screen needs it. [map] is the raw diarization id to role (`S0` to therapist),
 * [source] says where it came from (`llm`, `fallback` or `user`), [confidence] is 0..1 (0 for a fallback), [evidence] the quotes behind an LLM
 * guess, [canSwap] whether "Is this incorrect?" can exchange the two roles, and [version] the number of this map (it grows with every swap).
 */
data class SpeakerRoles(
    val map: Map<String, Role>,
    val source: String,
    val confidence: Double,
    val evidence: List<String>,
    val canSwap: Boolean,
    val version: Int,
) {
    companion object {
        fun of(row: SpeakerMapRow): SpeakerRoles {
            val map = SpeakerMaps.decode(row.mapJson)
            val evidence = try {
                JSONArray(row.evidenceJson).let { a -> (0 until a.length()).mapNotNull { (a.opt(it) as? String)?.takeIf(String::isNotBlank) } }
            } catch (_: Exception) {
                emptyList()
            }
            return SpeakerRoles(map, row.source, row.confidence, evidence, SpeakerMaps.canSwap(map), row.version)
        }
    }
}
