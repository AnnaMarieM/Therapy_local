package com.therapylocal.app.engine

import android.content.Context
import android.os.Process
import com.therapylocal.app.embed.Engines
import com.therapylocal.app.models.BackendGuard.ExitKind

/**
 * Main-tagged kills for the lab (catalogue B10, F03): tags the host's coming end as [asKind] through the connection's own `expectedExit`
 * (the mechanism of idle and timeout kills), logs `engine_kill why=test`, then kills the process. No AIDL method is involved.
 */
object EngineLabKills {
    /** True when a host of [role] was bound and has been killed. [asKind] is how the connection records the end (e.g. KILLED_FOR_MEMORY). */
    fun killForTest(context: Context, role: EngineRole, asKind: ExitKind): Boolean {
        val host = (if (role == EngineRole.LIVE) Engines.liveHost(context) else Engines.batchHost(context)) as? RemoteEngineHost ?: return false
        val pid = host.connection.expectedExit(asKind, "test") ?: return false
        Process.killProcess(pid)
        return true
    }
}
