package com.therapylocal.app.ui.motion

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sign

/** The axis a drag locked to after its dead zone. It stays locked until release, so a gesture never changes meaning half-way. */
enum class Axis { Horizontal, Vertical }

/** Which side of a two-way choice a swipe points at. */
enum class Side { Left, Right }

/** Pure maths behind the gestures. No Android, no Compose: every rule here is unit tested (GestureMathTest). */
object GestureMath {
    /**
     * Locks a drag to an axis once it has moved [dead] px: clearly sideways or clearly vertical (1.4x the other) locks at once, a diagonal
     * waits until [ambiguous] px and then takes the larger component. Null while undecided.
     */
    fun lockAxis(dx: Float, dy: Float, dead: Float, ambiguous: Float = dead * 2.2f): Axis? {
        val ax = abs(dx)
        val ay = abs(dy)
        val moved = kotlin.math.hypot(dx, dy)
        if (moved < dead) return null
        if (ax > ay * 1.4f) return Axis.Horizontal
        if (ay > ax * 1.4f) return Axis.Vertical
        if (moved > ambiguous) return if (ax > ay) Axis.Horizontal else Axis.Vertical
        return null
    }

    /** Distance past the dead zone, keeping the sign: the thing starts moving from where the lock happened, without a jump. */
    fun pastDeadZone(d: Float, dead: Float): Float = sign(d) * max(0f, abs(d) - dead)

    /** Soft boundary: 1:1 up to [limit], then [give] of the extra distance, so the edge is felt rather than hit. */
    fun rubberBand(d: Float, limit: Float, give: Float = 0.25f): Float {
        val a = abs(d)
        return sign(d) * if (a <= limit) a else limit + (a - limit) * give
    }

    /**
     * Arming with hysteresis: a swipe arms at [enter] of the way and only disarms when pulled back below [stay], so a finger resting
     * near the threshold does not flicker between the two (and buzz).
     */
    fun armed(wasArmed: Boolean, progress: Float, enter: Float = 1f, stay: Float = 0.88f): Boolean =
        if (wasArmed) progress >= stay else progress >= enter

    /**
     * Where a fling would come to rest, as if decelerating like a scrolled list: [velocity] in units per second, [decelerationRate] per
     * millisecond (0.998 is the platform's normal scroll). The reel aims for the name the projection lands on, not the nearest one.
     */
    fun projection(velocity: Float, decelerationRate: Float = 0.998f): Float =
        (velocity / 1000f) * decelerationRate / (1f - decelerationRate)

    /** The item a fling settles on: the projected position, rounded and kept in range. */
    fun snapIndex(position: Float, velocity: Float, count: Int, decelerationRate: Float = 0.998f): Int {
        if (count <= 0) return 0
        return (position + projection(velocity, decelerationRate)).roundToInt().coerceIn(0, count - 1)
    }

    /** Position on a reel of [count] items after dragging by [delta] items, with rubber banding past either end. */
    fun reelDrag(position: Float, delta: Float, count: Int): Float {
        val p = position + delta
        val last = (count - 1).coerceAtLeast(0).toFloat()
        return when {
            p < 0f -> p * 0.5f
            p > last -> last + (p - last) * 0.5f
            else -> p
        }
    }

    /**
     * Rail scrubbing speeds up when the finger moves fast (big jumps through a long list) and stays 1:1 when slow (precise picking).
     * [speed] in px per second.
     */
    fun scrubBoost(speed: Float, slow: Float = 380f, range: Float = 1400f, max: Float = 1.8f): Float =
        1f + ((speed - slow) / range).coerceIn(0f, 1f) * max

    fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3 - 2 * t)
    }

    fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    /** Fraction 0..1 of the way between the dead zone and the action distance [tact]. */
    fun progress(d: Float, dead: Float, tact: Float): Float = (abs(d) / (tact - dead)).coerceAtLeast(0f)

    /** Exponential smoothing that does not depend on the frame rate: same result at 60 and 120 Hz. */
    fun approach(current: Float, target: Float, rate: Float, dtSeconds: Float): Float =
        target + (current - target) * exp(-rate * dtSeconds)

    /** Fractional part, always in 0..1 (also for negative positions during an overscroll). */
    fun frac(x: Float): Float = x - floor(x)

    fun pow(x: Float, y: Float): Float = x.toDouble().pow(y.toDouble()).toFloat()
}

/**
 * How one name on the reel looks at a distance [rel] (in items) from the selected position. The selected name is big (display size); a
 * name one step away has already turned into the small line; further names fade out by half per step. Mirrors the mockup's reel.
 * [y] is the offset from the centre line in px, given [near] = gap between the big name and its neighbours and [far] = gap between small ones.
 */
data class ReelItemLook(val y: Float, val bigAlpha: Float, val smallAlpha: Float, val bigScale: Float) {
    companion object {
        fun of(rel: Float, near: Float, far: Float): ReelItemLook {
            val n = abs(rel)
            val f = max(0f, 1f - n)
            val yy = if (n < 1f) n * near else near + (n - 1f) * far
            val big = GestureMath.smoothstep(0.5f, 0.9f, f)
            val alpha = if (n < 1f) GestureMath.lerp(1f, 0.45f, n) else max(0f, 0.45f * GestureMath.pow(0.5f, n - 1f) - 0.02f * (n - 1f))
            return ReelItemLook(sign(rel) * yy, big, alpha * (1f - big), GestureMath.lerp(0.55f, 1f, f))
        }
    }
}
