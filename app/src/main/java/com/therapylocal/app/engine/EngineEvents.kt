package com.therapylocal.app.engine

/** The one list of engine log event names (catalogue section 7). Code and tests (`LogTape.KNOWN`) both use it, so they cannot drift. */
object EngineEvents {
    // main: connection
    const val BIND = "engine_bind"
    const val CONNECTED = "engine_connected"
    const val BIND_TIMEOUT = "engine_bind_timeout"
    const val STALE_CALLBACK = "engine_stale_callback"
    const val UNBOUND = "engine_unbound"

    // main: calls
    const val CALL = "engine_call"
    const val CALL_FAILED = "engine_call_failed"
    const val CALL_TIMEOUT = "engine_call_timeout"
    const val CALL_PROGRESS = "engine_call_progress"
    const val RETRY = "engine_retry"

    // main: deaths
    const val DIED = "engine_died"
    const val KILL = "engine_kill"
    const val EXIT = "engine_exit"
    const val EXIT_REASON_PENDING = "exit_reason_pending"

    // main: restarts
    const val RESTART = "engine_restart"
    const val CIRCUIT_OPEN = "engine_circuit_open"
    const val UNAVAILABLE = "engine_unavailable"

    // main: lifecycle
    const val IDLE_STOP = "engine_idle_stop"
    const val LIVE_SHUTDOWN_REQUESTED = "live_shutdown_requested"
    const val LIVE_SHUTDOWN_DONE = "engine_live_shutdown_done"
    const val ROLE_VIOLATION = "engine_role_violation"
    const val FAULT_PLAN_IGNORED = "fault_plan_ignored"

    // main: jobs
    const val JOB_DEFERRED = "job_deferred"

    // host
    const val HOST_START = "host_start"
    const val HOST_OPEN = "host_open"
    const val BACKEND_PHASE = "backend_phase"
    const val HOST_CLIENT_DIED = "host_client_died"
    const val HOST_EXIT = "host_exit"

    val ALL: List<String> = listOf(
        BIND, CONNECTED, BIND_TIMEOUT, STALE_CALLBACK, UNBOUND,
        CALL, CALL_FAILED, CALL_TIMEOUT, CALL_PROGRESS, RETRY,
        DIED, KILL, EXIT, EXIT_REASON_PENDING,
        RESTART, CIRCUIT_OPEN, UNAVAILABLE,
        IDLE_STOP, LIVE_SHUTDOWN_REQUESTED, LIVE_SHUTDOWN_DONE, ROLE_VIOLATION, FAULT_PLAN_IGNORED,
        JOB_DEFERRED,
        HOST_START, HOST_OPEN, BACKEND_PHASE, HOST_CLIENT_DIED, HOST_EXIT,
    )
}
