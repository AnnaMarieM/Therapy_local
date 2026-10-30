package com.therapylocal.app.ui.motion

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.dp

/** What a locked drag reports. [total] is past the dead zone on the locked axis, so motion starts where the lock happened, without a jump. */
@Stable
interface AxisDragHandler {
    /** Which drags this surface wants right now. One it does not accept is left to the children (a list scrolls, a field selects). */
    fun accepts(axis: Axis, start: Offset, direction: Float): Boolean = true
    fun onStart(axis: Axis, start: Offset) {}
    fun onDrag(axis: Axis, total: Float, delta: Float) {}

    /** [velocity] in px/s along the locked axis. Also called with zeros when the gesture is cancelled (another window took the touch). */
    fun onEnd(axis: Axis, total: Float, velocity: Float) {}
}

/** Same as [AxisDragHandler] but built from lambdas, for the simple cases. */
fun axisDragHandler(
    accepts: (Axis, Offset, Float) -> Boolean = { _, _, _ -> true },
    onStart: (Axis, Offset) -> Unit = { _, _ -> },
    onDrag: (Axis, Float, Float) -> Unit = { _, _, _ -> },
    onEnd: (Axis, Float, Float) -> Unit = { _, _, _ -> },
): AxisDragHandler = object : AxisDragHandler {
    override fun accepts(axis: Axis, start: Offset, direction: Float) = accepts(axis, start, direction)
    override fun onStart(axis: Axis, start: Offset) = onStart(axis, start)
    override fun onDrag(axis: Axis, total: Float, delta: Float) = onDrag(axis, total, delta)
    override fun onEnd(axis: Axis, total: Float, velocity: Float) = onEnd(axis, total, velocity)
}

/**
 * One finger, locked to one axis after a 12 dp dead zone and kept there until release. Runs in the Initial pass so it can claim a drag
 * before a child list or button sees it, but only claims what the [handler] accepts. Taps are never claimed, so buttons and rows inside keep
 * their own click handling; once a drag is claimed the child's press is cancelled, so a swipe that starts on a button never also taps it.
 */
fun Modifier.axisDrag(handler: AxisDragHandler, enabled: Boolean = true): Modifier = if (!enabled) this else composed {
    val dead = with(LocalDensity.current) { 12.dp.toPx() }
    val deadZone = maxOf(dead, LocalViewConfiguration.current.touchSlop)
    pointerInput(handler, deadZone) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val tracker = VelocityTracker().apply { addPosition(down.uptimeMillis, down.position) }
            var axis: Axis? = null
            var claimed = false
            var ended = false
            var total = Offset.Zero
            var last = 0f
            try {
                while (true) {
                    val ev = awaitPointerEvent(PointerEventPass.Initial)
                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                    if (!ch.pressed) {
                        val a = axis
                        if (claimed && a != null) {
                            val v = tracker.calculateVelocity()
                            ended = true
                            handler.onEnd(a, last, if (a == Axis.Horizontal) v.x else v.y)
                            ch.consume()
                        }
                        break
                    }
                    if (ch.isConsumed && !claimed) break // something closer to the finger already owns this gesture
                    tracker.addPosition(ch.uptimeMillis, ch.position)
                    total += ch.positionChange()
                    if (axis == null) {
                        val a = GestureMath.lockAxis(total.x, total.y, deadZone) ?: continue
                        axis = a
                        if (!handler.accepts(a, down.position, along(a, total))) break // not ours: the children have it
                        claimed = true
                        handler.onStart(a, down.position)
                    }
                    val a = axis!!
                    val now = GestureMath.pastDeadZone(along(a, total), deadZone)
                    handler.onDrag(a, now, now - last)
                    last = now
                    ch.consume()
                }
            } finally {
                // Cancelled (pointerInput restarted, the window lost focus) or the pointer vanished: never leave a surface half-pulled.
                val a = axis
                if (claimed && !ended && a != null) handler.onEnd(a, 0f, 0f)
            }
        }
    }
}

private fun along(a: Axis, o: Offset) = if (a == Axis.Horizontal) o.x else o.y
