package com.therapylocal.app.ui.design

import org.junit.Assert.assertEquals
import org.junit.Test

/** The table in the KDoc of [Adaptive], one row per width the app is meant to meet: the POCO (385), the Pixel 8 (411), tablets. */
class BreakpointTableTest {
    private data class Row(val w: Int, val h: Int, val cls: LayoutClass, val twoPane: Boolean)

    private val rows = listOf(
        // phones: portrait lock, so the landscape rows are only what a free-form or split window could give
        Row(360, 800, LayoutClass.Compact, false), Row(800, 360, LayoutClass.Medium, true),
        Row(385, 835, LayoutClass.Compact, false), Row(835, 385, LayoutClass.Medium, true),
        Row(411, 914, LayoutClass.Compact, false), Row(914, 411, LayoutClass.Expanded, true),
        Row(600, 960, LayoutClass.Medium, false), Row(960, 600, LayoutClass.Expanded, true),
        Row(840, 1200, LayoutClass.Expanded, true), Row(1200, 840, LayoutClass.Expanded, true),
        Row(1200, 1700, LayoutClass.Expanded, true), Row(1700, 1200, LayoutClass.Expanded, true),
    )

    @Test fun everyRowGivesTheDocumentedLayout() {
        for (r in rows) {
            val l = Adaptive.layoutFor(r.w, r.h)
            assertEquals("${r.w}x${r.h} class", r.cls, l.layoutClass)
            assertEquals("${r.w}x${r.h} two-pane", r.twoPane, l.twoPane)
        }
    }

    @Test fun theWidthsOfTheTwoReportedPhonesGiveTheSameLayout() {
        assertEquals(Adaptive.layoutFor(385, 835).copy(listPaneDp = 0), Adaptive.layoutFor(411, 914).copy(listPaneDp = 0))
    }

    @Test fun layoutDependsOnlyOnWidthAndHeight() {
        for (w in 300..1400 step 7) for (h in 300..1800 step 97) {
            assertEquals(Adaptive.layoutFor(w, h), Adaptive.layoutFor(w, h))
            assertEquals(Adaptive.twoPane(w, h), Adaptive.layoutFor(w, h).twoPane)
            if (w < 600) assertEquals("$w x $h", false, Adaptive.twoPane(w, h))
        }
    }

    @Test fun aWindowIsTheSameWidthWhateverDisplaySizeTheUserChose() {
        // POCO: 1156 px. At the stable 480 dpi it is 385 dp; if the user picked a smaller display size (400 dpi) the system says 462 dp.
        assertEquals(385, DisplayPin.pinnedDp(462, 400, 480))
        assertEquals(385, DisplayPin.pinnedDp(385, 480, 480))
    }
}
