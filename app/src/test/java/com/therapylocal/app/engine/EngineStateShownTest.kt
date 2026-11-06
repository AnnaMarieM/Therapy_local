package com.therapylocal.app.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class EngineStateShownTest {
    private fun v(state: HostState, deaths: Int = 0) = HostView(state, 1, 1, 0, deaths, null, emptyList(), null)

    @Test fun noViewIsStopped() = assertEquals(EngineState.Shown.STOPPED, EngineState.shown(null, 6))

    @Test fun boundIsRunning() = assertEquals(EngineState.Shown.RUNNING, EngineState.shown(v(HostState.BOUND, 2), 6))

    @Test fun deadBelowTheCapRestarts() = assertEquals(EngineState.Shown.RESTARTING, EngineState.shown(v(HostState.DEAD, 2), 6))

    @Test fun deadAtTheCapIsUnavailable() = assertEquals(EngineState.Shown.UNAVAILABLE, EngineState.shown(v(HostState.DEAD, 6), 6))

    @Test fun unboundWithManyDeathsIsUnavailable() = assertEquals(EngineState.Shown.UNAVAILABLE, EngineState.shown(v(HostState.UNBOUND, 6), 6))

    @Test fun bindingAndStopping() {
        assertEquals(EngineState.Shown.STARTING, EngineState.shown(v(HostState.BINDING), 6))
        assertEquals(EngineState.Shown.STOPPING, EngineState.shown(v(HostState.STOPPING), 6))
    }
}
