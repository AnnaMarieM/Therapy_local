package com.therapylocal.app.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** What the settings screen shows of the engine hosts. The connections publish into it; nothing here binds a host. */
object EngineState {
    private val _views = MutableStateFlow<Map<EngineRole, HostView>>(emptyMap())
    val views: StateFlow<Map<EngineRole, HostView>> = _views.asStateFlow()

    fun publish(role: EngineRole, view: HostView) = _views.update { it + (role to view) }

    /**
     * Refreshes the host's PSS from a `Status` call, only while it is bound, on IO and bounded by [timeoutMs]. Never binds a host just to
     * show it, and never runs on the main thread.
     */
    suspend fun refreshPss(host: RemoteEngineHost, timeoutMs: Long) {
        val status = withTimeoutOrNull(timeoutMs) { withContext(Dispatchers.IO) { runInterruptible { host.connection.statusIfBound() } } } ?: return
        host.connection.noteStatus(status.pssKb)
    }

    /** What the settings row says of a host. */
    enum class Shown { STOPPED, STARTING, RUNNING, STOPPING, RESTARTING, UNAVAILABLE }

    /**
     * Maps a view to the row state. A host that died is "restarting" until the storm cap is reached, then "unavailable" (the circuit is open
     * until old deaths leave the window). [stormDeaths] is `EngineTuning.stormDeaths`.
     */
    fun shown(view: HostView?, stormDeaths: Int): Shown = when {
        view == null -> Shown.STOPPED
        view.state == HostState.BINDING -> Shown.STARTING
        view.state == HostState.BOUND -> Shown.RUNNING
        view.state == HostState.STOPPING -> Shown.STOPPING
        view.state == HostState.DEAD && view.deaths >= stormDeaths -> Shown.UNAVAILABLE
        view.state == HostState.DEAD -> Shown.RESTARTING
        else -> if (view.deaths >= stormDeaths) Shown.UNAVAILABLE else Shown.STOPPED
    }
}
