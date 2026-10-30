package com.therapylocal.app.ui.design

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

/**
 * Every touch that changes something answers with a tick, matched to what the eye sees at the same moment. A haptic out of step with the
 * motion breaks the illusion more than no haptic, so each one is fired by the code that moves the thing, never on a timer.
 * Uses the platform's own feedback constants, which honour the system "touch feedback" setting.
 */
@Stable
class Haptics(private val view: View) {
    /** A name passes the centre of the reel, a letter on the rail. Light, can repeat quickly. */
    fun tick() = perform(HapticFeedbackConstants.CLOCK_TICK)

    /** A swipe crossed its threshold: releasing now will act. */
    fun arm() = perform(if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE else HapticFeedbackConstants.CONTEXT_CLICK)

    /** Backed out of an armed swipe: releasing now does nothing. */
    fun disarm() = perform(if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.GESTURE_THRESHOLD_DEACTIVATE else HapticFeedbackConstants.CLOCK_TICK)

    /** Something happened: a sheet opened, a recording started or was saved. */
    fun confirm() = perform(HapticFeedbackConstants.CONFIRM)

    /** Not allowed or wrong (wrong password, a hold let go too early). */
    fun reject() = perform(HapticFeedbackConstants.REJECT)

    /** A press landed: buttons and rows. */
    fun press() = perform(HapticFeedbackConstants.VIRTUAL_KEY)

    /** A gesture changed mode (rail tier, axis lock). */
    fun mode() = perform(if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_FREQUENT_TICK else HapticFeedbackConstants.CLOCK_TICK)

    private fun perform(c: Int) {
        view.performHapticFeedback(c)
    }
}

@Composable
fun rememberHaptics(): Haptics {
    val view = LocalView.current
    return remember(view) { Haptics(view) }
}
