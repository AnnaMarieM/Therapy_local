package com.therapylocal.app.ui.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GestureMathTest {

    // ---- axis lock ----

    @Test
    fun axisWaitsForTheDeadZone() {
        assertNull(GestureMath.lockAxis(8f, 0f, dead = 12f))
        assertNull(GestureMath.lockAxis(0f, -11.9f, dead = 12f))
        assertEquals(Axis.Horizontal, GestureMath.lockAxis(13f, 2f, dead = 12f))
        assertEquals(Axis.Vertical, GestureMath.lockAxis(-1f, -14f, dead = 12f))
    }

    @Test
    fun diagonalWaitsThenTakesTheLargerComponent() {
        // 45 degrees just past the dead zone: still undecided
        assertNull(GestureMath.lockAxis(10f, 10f, dead = 12f))
        assertEquals(Axis.Horizontal, GestureMath.lockAxis(20f, 19f, dead = 12f))
        assertEquals(Axis.Vertical, GestureMath.lockAxis(-19f, 20f, dead = 12f))
    }

    @Test
    fun deadZoneIsSubtractedWithoutAJump() {
        assertEquals(0f, GestureMath.pastDeadZone(12f, 12f), 0f)
        assertEquals(0f, GestureMath.pastDeadZone(-5f, 12f), 0f)
        assertEquals(-8f, GestureMath.pastDeadZone(-20f, 12f), 0f)
    }

    // ---- rubber band ----

    @Test
    fun rubberBandIsOneToOneThenGives() {
        assertEquals(50f, GestureMath.rubberBand(50f, 100f), 0f)
        assertEquals(100f, GestureMath.rubberBand(100f, 100f), 0f)
        assertEquals(125f, GestureMath.rubberBand(200f, 100f), 0.001f)
        assertEquals(-125f, GestureMath.rubberBand(-200f, 100f), 0.001f)
    }

    // ---- arming ----

    @Test
    fun armingHasHysteresis() {
        assertFalse(GestureMath.armed(false, 0.95f))
        assertTrue(GestureMath.armed(false, 1f))
        // once armed it holds until clearly pulled back
        assertTrue(GestureMath.armed(true, 0.9f))
        assertFalse(GestureMath.armed(true, 0.87f))
    }

    @Test
    fun aRestingFingerNeverFlickers() {
        // jitter of +-0.05 around the threshold: one arm, no disarm
        var armed = false
        var flips = 0
        for (i in 0 until 200) {
            val p = 1f + if (i % 2 == 0) 0.05f else -0.05f
            val next = GestureMath.armed(armed, p)
            if (next != armed) flips++
            armed = next
        }
        assertEquals(1, flips)
    }

    // ---- fling projection ----

    @Test
    fun projectionMatchesPlatformScrolling() {
        assertEquals(0f, GestureMath.projection(0f), 0f)
        // 1000 units/s at the normal rate travels 499 units
        assertEquals(499f, GestureMath.projection(1000f), 0.5f)
        assertEquals(-499f, GestureMath.projection(-1000f), 0.5f)
    }

    @Test
    fun snapAimsWhereTheFlingLandsAndStaysInRange() {
        assertEquals(3, GestureMath.snapIndex(3.2f, 0f, 10))
        assertEquals(4, GestureMath.snapIndex(3.2f, 2f, 10)) // a gentle flick carries to the next name
        assertEquals(9, GestureMath.snapIndex(3f, 500f, 10))
        assertEquals(0, GestureMath.snapIndex(1f, -500f, 10))
        assertEquals(0, GestureMath.snapIndex(0f, 0f, 0))
    }

    @Test
    fun reelRubberBandsPastBothEnds() {
        assertEquals(2f, GestureMath.reelDrag(1f, 1f, 5), 0f)
        assertEquals(-0.5f, GestureMath.reelDrag(0f, -1f, 5), 0f)
        assertEquals(4.5f, GestureMath.reelDrag(4f, 1f, 5), 0f)
        assertEquals(-0.5f, GestureMath.reelDrag(0f, -1f, 1), 0f)
    }

    @Test
    fun scrubBoostIsPreciseWhenSlow() {
        assertEquals(1f, GestureMath.scrubBoost(100f), 0f)
        assertEquals(1f, GestureMath.scrubBoost(380f), 0f)
        assertEquals(2.8f, GestureMath.scrubBoost(5000f), 0.001f)
    }

    @Test
    fun approachIsFrameRateIndependent() {
        var a = 0f
        repeat(6) { a = GestureMath.approach(a, 1f, 9f, 1 / 60f) }
        var b = 0f
        repeat(12) { b = GestureMath.approach(b, 1f, 9f, 1 / 120f) }
        assertEquals(a, b, 1e-5f)
    }

    @Test
    fun fracIsAlwaysPositive() {
        assertEquals(0.25f, GestureMath.frac(2.25f), 1e-6f)
        assertEquals(0.75f, GestureMath.frac(-0.25f), 1e-6f)
    }

    // ---- reel look ----

    @Test
    fun selectedNameIsBigAndNeighboursAreSmall() {
        val c = ReelItemLook.of(0f, near = 128f, far = 46f)
        assertEquals(0f, c.y, 0f)
        assertEquals(1f, c.bigAlpha, 0f)
        assertEquals(0f, c.smallAlpha, 0f)
        assertEquals(1f, c.bigScale, 0f)

        val next = ReelItemLook.of(1f, 128f, 46f)
        assertEquals(128f, next.y, 0f)
        assertEquals(0f, next.bigAlpha, 0f)
        assertEquals(0.45f, next.smallAlpha, 1e-4f)

        val above = ReelItemLook.of(-2f, 128f, 46f)
        assertEquals(-(128f + 46f), above.y, 1e-4f)
        assertTrue(above.smallAlpha < next.smallAlpha)
    }

    @Test
    fun reelLookIsContinuous() {
        // no visible jump anywhere between -4 and 4 items
        var prev = ReelItemLook.of(-4f, 128f, 46f)
        var r = -4f
        while (r <= 4f) {
            val l = ReelItemLook.of(r, 128f, 46f)
            assertTrue("y jump at $r", kotlin.math.abs(l.y - prev.y) < 3f)
            assertTrue("alpha jump at $r", kotlin.math.abs(l.smallAlpha - prev.smallAlpha) < 0.05f)
            assertTrue("big jump at $r", kotlin.math.abs(l.bigAlpha - prev.bigAlpha) < 0.05f)
            prev = l
            r += 0.01f
        }
    }
}
