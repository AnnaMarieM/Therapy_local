package com.therapylocal.app.ui.design

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveTest {
    @Test fun classesFollowWidth() {
        assertEquals(LayoutClass.Compact, Adaptive.classOf(360))
        assertEquals(LayoutClass.Compact, Adaptive.classOf(599))
        assertEquals(LayoutClass.Medium, Adaptive.classOf(600))
        assertEquals(LayoutClass.Medium, Adaptive.classOf(839))
        assertEquals(LayoutClass.Expanded, Adaptive.classOf(840))
    }

    @Test fun phonesNeverGoTwoPane() {
        assertFalse(Adaptive.twoPane(360, 800))
        assertFalse(Adaptive.twoPane(412, 915))
        assertFalse(Adaptive.twoPane(800 - 441, 1280))
    }

    @Test fun tabletsGoTwoPaneWhenWide() {
        assertTrue(Adaptive.twoPane(1280, 800))
        assertTrue(Adaptive.twoPane(840, 1200))
        assertTrue(Adaptive.twoPane(720, 600))
        assertFalse(Adaptive.twoPane(600, 960))
    }

    @Test fun splitScreenHalvesFallBackToOnePane() {
        assertFalse(Adaptive.twoPane(400, 1280))
        assertFalse(Adaptive.twoPane(560, 800))
    }

    @Test fun listPaneIsBounded() {
        assertEquals(320, Adaptive.listPaneDp(600))
        assertEquals(440, Adaptive.listPaneDp(2000))
        assertTrue(Adaptive.listPaneDp(1280) in 320..440)
    }
}
