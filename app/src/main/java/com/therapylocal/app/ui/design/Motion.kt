package com.therapylocal.app.ui.design

import android.provider.Settings
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import kotlin.math.sqrt

/**
 * Motion is springs, never fixed durations: a spring starts from whatever velocity the finger left, can be grabbed and re-aimed mid-flight,
 * and settles on its own. The values come from the swipe mockup (scratchpad/swipe-ui), where they were tuned by hand, converted from
 * (stiffness k, damping c) to a damping ratio c / (2·√k).
 */
@Immutable
object Motion {
    private fun ratio(k: Float, c: Float) = c / (2 * sqrt(k))

    /** Buttons growing as a swipe pulls them; bars and small surfaces. Quick, a touch of give. */
    val Pull: SpringSpec<Float> = spring(dampingRatio = ratio(300f, 26f), stiffness = 300f)

    /** The little pop when a swipe arms. Bouncy on purpose, it is tiny. */
    val Pop: SpringSpec<Float> = spring(dampingRatio = ratio(300f, 12f), stiffness = 300f)

    /** Sheets opening from and closing into the thing that opened them. Nearly critical: no wobble on a large surface. */
    val Sheet: SpringSpec<Float> = spring(dampingRatio = ratio(300f, 31f), stiffness = 300f)

    /** Things thrown sideways (the cue, the reel shifting with a swipe). Snappy, follows through. */
    val Throw: SpringSpec<Float> = spring(dampingRatio = ratio(420f, 26f), stiffness = 420f)

    /** The name reel settling on a name. Heavier: names have weight. */
    val Reel: SpringSpec<Float> = spring(dampingRatio = ratio(150f, 21f), stiffness = 150f)

    /** Things fading in or out on their own (labels, hints, the greeting). Critically damped and soft. */
    val Fade: SpringSpec<Float> = spring(dampingRatio = 1f, stiffness = 200f)

    /** First appearance of a screen. Slow and calm, happens once. */
    val Arrive: SpringSpec<Float> = spring(dampingRatio = 1f, stiffness = 70f)

    val PullOffset: SpringSpec<Offset> = spring(dampingRatio = ratio(300f, 26f), stiffness = 300f)

    /** With reduced motion, things that would travel only fade. */
    val ReducedFade: AnimationSpec<Float> = tween(150)
}

/** True when the user turned animations off (developer options or accessibility "Remove animations"). */
val LocalReducedMotion = staticCompositionLocalOf { false }

@Composable
fun rememberReducedMotion(): Boolean {
    val ctx = LocalContext.current
    return remember(ctx) { Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
}
