package com.therapylocal.app.ui.design

import android.content.Context
import android.content.res.Configuration
import android.util.DisplayMetrics

/**
 * The look does not depend on the user's display settings (AD-05). Applied in every attachBaseContext of the app, it overrides three
 * system settings that change size and weight of everything: font size ([Configuration.fontScale]), bold text / the ROM's font-weight
 * slider ([Configuration.fontWeightAdjustment], Android 12+) and display size ([Configuration.densityDpi]).
 *
 * The density is the device's stable one (`DENSITY_DEVICE_STABLE`: the panel's own, which the "display size" setting does not move). The
 * override leaves the window's dp widths alone (they must follow rotation and split screen); the system computed them at the user's
 * density, so layout code converts them with [pinnedDp] ([windowWidthDp]).
 *
 * Accessibility trade-off: a user who needs larger text or bold text no longer gets it inside this app. The app's text sizes are chosen
 * for reading at arm's length on paper-like contrast, but this is a loss for low-vision users. Set [ENABLED] to false to give the
 * settings back (then text may wrap and layouts move; [Adaptive] and the fitted wordmark still hold the breakpoints).
 */
object DisplayPin {
    const val ENABLED = true

    /** [Configuration.fontWeightAdjustment] 0: defined and neutral. (UNDEFINED would not override the system's value in an override.) */
    private const val NO_WEIGHT_ADJUSTMENT = 0

    fun pinned(base: Configuration, stableDpi: Int = DisplayMetrics.DENSITY_DEVICE_STABLE): Configuration {
        val c = Configuration(base)
        if (!ENABLED) return c
        c.fontScale = 1f
        c.fontWeightAdjustment = NO_WEIGHT_ADJUSTMENT
        if (stableDpi > 0) c.densityDpi = stableDpi
        return c
    }

    /** A window size the system measured in dp at [systemDpi], in dp at [appDpi] (the pinned density the app draws with). The same panel pixels. */
    fun pinnedDp(dp: Int, systemDpi: Int, appDpi: Int): Int = if (dp <= 0 || systemDpi <= 0 || appDpi <= 0) dp else (dp.toLong() * systemDpi / appDpi).toInt()

    fun wrap(base: Context): Context = if (ENABLED) base.createConfigurationContext(pinned(base.resources.configuration)) else base
}
