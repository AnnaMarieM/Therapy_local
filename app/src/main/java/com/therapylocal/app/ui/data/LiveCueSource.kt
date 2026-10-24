package com.therapylocal.app.ui.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A fact from an earlier session that the live audio resembles. [chunk] and [session] locate the transcript words it came from. */
data class UiCue(val factId: String, val text: String, val category: String, val session: String, val score: Float, val chunk: Int)

/**
 * Where the Live Session screen gets its "possibly relevant" cues. Kept as an interface so the screen does not depend on how they are found.
 * [client] is the lowercase memory client id (e.g. "george"); start/stop are called when a session starts and ends.
 */
interface LiveCueSource {
    val cues: StateFlow<List<UiCue>>
    val running: StateFlow<Boolean>
    val error: StateFlow<String?>
    fun start(client: String)
    fun stop()
}

/**
 * TODO: temporary stub, never produces a cue. Replace with an adapter over memory.LiveCues (branch feature/realtime-embedding-cues) that maps
 * memory.Cue to [UiCue] field by field and forwards start/stop; then point [LiveCueSources.default] at it.
 */
object StubLiveCueSource : LiveCueSource {
    override val cues: StateFlow<List<UiCue>> = MutableStateFlow(emptyList<UiCue>()).asStateFlow()
    override val running: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()
    override val error: StateFlow<String?> = MutableStateFlow<String?>(null).asStateFlow()
    override fun start(client: String) {}
    override fun stop() {}
}

object LiveCueSources {
    val default: LiveCueSource = StubLiveCueSource
}
