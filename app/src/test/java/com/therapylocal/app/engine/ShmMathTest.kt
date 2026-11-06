package com.therapylocal.app.engine

import com.therapylocal.app.config.EngineConfig
import com.therapylocal.app.engine.wire.ShmMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShmMathTest {
    // N19 Shm size math (see also N19 edge tests in EngineFamilyNTest)
    @Test fun emptyPayloadStillGetsATwoByteRegion() {
        assertEquals(2, ShmMath.regionBytes(0, 2))
        assertEquals(2, ShmMath.regionBytes(0, 1))
    }

    @Test fun oneByteRoundsUpToTwo() = assertEquals(2, ShmMath.regionBytes(1, 1))

    @Test fun shortsAreTwoBytesEach() {
        assertEquals(2, ShmMath.regionBytes(1, 2))
        assertEquals(6, ShmMath.regionBytes(3, 2))
        assertEquals(20 * 16_000 * 2, ShmMath.regionBytes(20 * 16_000, 2))
    }

    @Test fun overflowIsAnErrorNotANegativeSize() {
        try {
            ShmMath.regionBytes(Int.MAX_VALUE, 2)
            org.junit.Assert.fail("expected overflow")
        } catch (_: ArithmeticException) {
        }
    }

    @Test fun stringsGoInlineUpToTheLimitAndThroughShmAbove() {
        assertTrue(ShmMath.goesInline(0))
        assertTrue(ShmMath.goesInline(EngineConfig.INLINE_MAX_BYTES))
        assertFalse(ShmMath.goesInline(EngineConfig.INLINE_MAX_BYTES + 1))
    }
}
