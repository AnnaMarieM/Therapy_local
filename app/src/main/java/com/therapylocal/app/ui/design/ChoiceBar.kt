package com.therapylocal.app.ui.design

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.therapylocal.app.ui.motion.BoundsRef
import com.therapylocal.app.ui.motion.GestureMath
import com.therapylocal.app.ui.motion.Side
import com.therapylocal.app.ui.motion.trackBounds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The two-way choice at the bottom of every screen: swiping toward a button is the same as tapping it. As the finger moves, the button it
 * points at grows across the bar (the other gives way and its label fades); past the action distance it arms with a tick and its label
 * says what letting go will do. Releasing armed acts; releasing unarmed springs back and does nothing. Backing out disarms with a softer tick.
 *
 * A screen feeds its horizontal drags into [drag] and [release]; the bar itself only draws.
 */
@Stable
class ChoiceState internal constructor(private val scope: CoroutineScope, private val haptics: Haptics) {
    val left = Animatable(0f)
    val right = Animatable(0f)
    internal val pop = Animatable(0f)

    var armed by mutableStateOf<Side?>(null)
        private set

    /** Action distance in px: about a quarter of the screen width (set by the screen once it knows its size). */
    var tact by mutableFloatStateOf(300f)

    /** [total] is the drag past its dead zone; [allow] says which sides can act now (a disabled side still moves a little but never arms). */
    fun drag(total: Float, allow: (Side) -> Boolean = { true }) {
        val side = if (total >= 0) Side.Right else Side.Left
        val can = allow(side)
        val p = GestureMath.progress(if (can) total else total * 0.2f, 0f, tact)
        scope.launch { (if (side == Side.Left) left else right).snapTo(p.coerceAtMost(1.25f)) }
        scope.launch { (if (side == Side.Left) right else left).snapTo(0f) }
        val now = if (can && GestureMath.armed(armed == side, p)) side else null
        if (now != armed) {
            armed = now
            if (now != null) {
                haptics.arm()
                scope.launch { pop.snapTo(0f); pop.animateTo(0f, Motion.Pop, initialVelocity = 7f) }
            } else {
                haptics.disarm()
            }
        }
    }

    /** Ends the drag: the armed side, if any, which the screen then acts on. The bar springs back either way. */
    fun release(): Side? {
        val a = armed
        armed = null
        reset()
        return a
    }

    fun reset() {
        armed = null
        scope.launch { left.animateTo(0f, Motion.Pull) }
        scope.launch { right.animateTo(0f, Motion.Pull) }
    }

    /** A short pull toward [side] and back: shows that the buttons can be swiped (once, after the first pick). */
    fun nudge(side: Side) {
        val a = if (side == Side.Left) left else right
        scope.launch {
            a.animateTo(0.3f, Motion.Pull)
            a.animateTo(0f, Motion.Pull)
        }
    }
}

@Composable
fun rememberChoiceState(): ChoiceState {
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    return remember { ChoiceState(scope, haptics) }
}

/** One side of the bar. [armedLabel] is shown while a swipe toward it is armed ("Release to record"). */
data class Choice(
    val label: String,
    val armedLabel: String,
    val color: Color,
    val content: Color,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

/**
 * The bar. [center] (the hold-to-finish button while recording) sits between the two and never stretches. [leftRef] and [rightRef] record
 * where the buttons are, so sheets can grow out of them.
 */
@Composable
fun ChoiceBar(
    state: ChoiceState,
    left: Choice,
    right: Choice,
    modifier: Modifier = Modifier,
    leftRef: BoundsRef? = null,
    rightRef: BoundsRef? = null,
    center: (@Composable () -> Unit)? = null,
) {
    val haptics = rememberHaptics()
    Layout(
        modifier = modifier,
        content = {
            BarButton(left, Side.Left, state, haptics, Modifier.layoutId("L").let { m -> leftRef?.let { m.trackBounds(it) } ?: m })
            if (center != null) Box(Modifier.layoutId("C")) { center() }
            BarButton(right, Side.Right, state, haptics, Modifier.layoutId("R").let { m -> rightRef?.let { m.trackBounds(it) } ?: m })
        },
    ) { measurables, constraints ->
        // Read here, in layout, so a swipe re-lays out the bar without recomposing it.
        val gl = 1f + 2.4f * state.left.value
        val gr = 1f + 2.4f * state.right.value
        val gap = 10.dp.roundToPx()
        val h = 60.dp.roundToPx()
        val c = measurables.firstOrNull { it.layoutId == "C" }?.measure(Constraints.fixed(h, h))
        val free = constraints.maxWidth - gap - (c?.let { it.width + gap } ?: 0)
        val minW = 58.dp.roundToPx()
        val lw = (free * gl / (gl + gr)).toInt().coerceIn(minW, free - minW)
        val rw = free - lw
        val l = measurables.first { it.layoutId == "L" }.measure(Constraints.fixed(lw, h))
        val r = measurables.first { it.layoutId == "R" }.measure(Constraints.fixed(rw, h))
        layout(constraints.maxWidth, h) {
            l.place(0, 0)
            var x = lw + gap
            c?.let { it.place(x, 0); x += it.width + gap }
            r.place(x, 0)
        }
    }
}

@Composable
private fun BarButton(choice: Choice, side: Side, state: ChoiceState, haptics: Haptics, modifier: Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press = remember { Animatable(0f) }
    LaunchedEffect(pressed) { press.animateTo(if (pressed) 1f else 0f, Motion.Pull) }
    val isArmed = state.armed == side
    val text = if (isArmed) choice.armedLabel else choice.label
    Row(
        modifier
            .graphicsLayer {
                val armedScale = if (state.armed == side) 1f + 0.03f * (1f + state.pop.value) else 1f
                val s = armedScale * (1f - 0.03f * press.value)
                scaleX = s
                scaleY = s
                alpha = if (choice.enabled) 1f else 0.42f
            }
            .clip(RoundedCornerShape(30.dp))
            .background(choice.color)
            .clickable(
                enabled = choice.enabled,
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClickLabel = choice.label,
                onClick = { haptics.press(); choice.onClick() },
            ),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val other = if (side == Side.Left) state.right else state.left
        val labelAlpha = Modifier.graphicsLayer { alpha = (1f - other.value * 1.7f).coerceIn(0f, 1f) }
        if (side == Side.Left) Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, null, tint = choice.content, modifier = labelAlpha)
        if (side == Side.Left) Spacer(Modifier.width(6.dp))
        Text(text, style = Type.Action, color = choice.content, maxLines = 1, overflow = TextOverflow.Clip, modifier = labelAlpha)
        if (side == Side.Right) Spacer(Modifier.width(6.dp))
        if (side == Side.Right) Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = choice.content, modifier = labelAlpha)
    }
}
