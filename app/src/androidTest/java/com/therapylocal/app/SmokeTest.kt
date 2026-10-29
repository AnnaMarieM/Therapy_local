package com.therapylocal.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

// Proves the instrumentation runner wires up on the emulator. Loads no native libraries.
@RunWith(AndroidJUnit4::class)
class SmokeTest {
    @Test
    fun runsInTheAppUnderTest() {
        assertEquals("com.therapylocal.app", InstrumentationRegistry.getInstrumentation().targetContext.packageName)
    }
}
