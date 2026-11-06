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
}
