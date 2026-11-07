package com.therapylocal.app.ui

import android.content.res.Configuration
import android.util.DisplayMetrics
import androidx.test.platform.app.InstrumentationRegistry
import com.therapylocal.app.ui.design.DisplayPin
import org.junit.Assert.assertEquals
import org.junit.Test

/** AD-05: whatever font size, bold text and display size the user chose, the app's resources see the pinned values. */
class DisplayPinTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun aLargeFontBoldTextAndBigDisplaySizeAreOverridden() {
        val user = Configuration(context.resources.configuration).apply {
            fontScale = 1.5f
            fontWeightAdjustment = 300
            densityDpi = 560
            screenWidthDp = 300
        }
        val pinned = DisplayPin.pinned(user)
        assertEquals(1f, pinned.fontScale, 0f)
        assertEquals(0, pinned.fontWeightAdjustment)
        assertEquals(DisplayMetrics.DENSITY_DEVICE_STABLE, pinned.densityDpi)
        // The same panel pixels, in dp at the pinned density.
        assertEquals(300 * 560 / DisplayMetrics.DENSITY_DEVICE_STABLE, DisplayPin.pinnedDp(300, 560, DisplayMetrics.DENSITY_DEVICE_STABLE))
    }

    @Test
    fun aContextBuiltFromItSeesThePinnedMetrics() {
        val user = Configuration(context.resources.configuration).apply { fontScale = 1.3f; densityDpi = 400 }
        val ctx = context.createConfigurationContext(DisplayPin.pinned(user))
        assertEquals(1f, ctx.resources.configuration.fontScale, 0f)
        assertEquals(DisplayMetrics.DENSITY_DEVICE_STABLE, ctx.resources.configuration.densityDpi)
        assertEquals(DisplayMetrics.DENSITY_DEVICE_STABLE / 160f, ctx.resources.displayMetrics.density, 0.01f)
    }
}
