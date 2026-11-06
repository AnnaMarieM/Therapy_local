package com.therapylocal.app.config

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackendPolicyForceTest {
    @After fun reset() { BackendPolicy.force = false; BackendPolicy.override = "auto" }

    @Test fun forceNeedsASpecificAccelerator() {
        BackendPolicy.force = true
        for (o in listOf("auto", "AUTO", "", "  ")) { BackendPolicy.override = o; assertFalse("'$o'", BackendPolicy.forcing()) }
        BackendPolicy.override = "GPU"
        assertTrue(BackendPolicy.forcing())
    }

    @Test fun anAcceleratorWithoutForceIsNotForcing() {
        BackendPolicy.force = false; BackendPolicy.override = "GPU"
        assertFalse(BackendPolicy.forcing())
    }
}
