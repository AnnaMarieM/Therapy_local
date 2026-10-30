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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.layout
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
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
 * The bar at the bottom of every screen, and the one rule of the app's swipes: back is on the left, in is on the right. Swiping right (the
 * finger moving toward the left button, like the system back gesture) arms Back; swiping left pulls the next screen in from the right and
 * arms the right button. As the finger moves, the armed-to-be button grows across the bar (the other gives way and its label fades); past
 * the action distance it arms with a tick and its label says what letting go will do. Releasing armed acts; releasing unarmed springs back
 * and does nothing. Backing out disarms with a softer tick.
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

    /** Action distance in px: about a quarter of the screen width, so the same swipe acts on a small phone and a large one. */
    var tact by mutableFloatStateOf(300f)

    /**
     * [total] is the drag past its dead zone, positive to the right; [allow] says which sides can act now (a disabled side still moves a
     * little but never arms). A drag to the right is toward Back (left), a drag to the left is toward the way in (right).
     */
    fun drag(total: Float, allow: (Side) -> Boolean = { true }) {
        val side = if (total > 0) Side.Left else Side.Right
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
    val density = androidx.compose.ui.platform.LocalDensity.current
    val widthDp = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp
    return remember { ChoiceState(scope, haptics) }.also {
        it.tact = with(density) { (widthDp * ACTION_SHARE).coerceIn(ACTION_MIN_DP, ACTION_MAX_DP).dp.toPx() }
    }
}

/** A swipe acts at this share of the screen's width, never under [ACTION_MIN_DP] or over [ACTION_MAX_DP]. */
const val ACTION_SHARE = 0.28f
const val ACTION_MIN_DP = 96f
const val ACTION_MAX_DP = 150f

/**
 * One side of the bar. [armedLabel] is shown while a swipe toward it is armed ("Release to record"). A choice that is not a step back or in
 * ([swipe] false: Pause) is only tapped; it shows no arrow, and the screen does not let a swipe arm it.
 */
data class Choice(
    val label: String,
    val armedLabel: String,
    val color: Color,
    val content: Color,
    val enabled: Boolean = true,
    val swipe: Boolean = true,
    val onClick: () -> Unit,
)

/** The left side of every screen that has somewhere to go back to. */
fun backChoice(label: String = "Back", enabled: Boolean = true, onClick: () -> Unit) =
    Choice(label, "Release to go back", Palette.Tonal, Palette.Ink, enabled = enabled, onClick = onClick)

/**
 * The bar. [center] (the hold-to-finish button while recording) sits between the two and never stretches. Either side may be missing (home
 * has nowhere back, a page you only read has nowhere in); the other then takes the whole bar. [leftRef] and [rightRef] record where the
 * buttons are, so sheets can grow out of them.
 */
@Composable
fun ChoiceBar(
    state: ChoiceState,
    left: Choice?,
    right: Choice?,
    modifier: Modifier = Modifier,
    leftRef: BoundsRef? = null,
    rightRef: BoundsRef? = null,
    center: (@Composable () -> Unit)? = null,
) {
    val haptics = rememberHaptics()
    Layout(
        modifier = modifier,
        content = {
            if (left != null) BarButton(left, Side.Left, state, haptics, Modifier.layoutId("L").let { m -> leftRef?.let { m.trackBounds(it) } ?: m })
            if (center != null) Box(Modifier.layoutId("C")) { center() }
            if (right != null) BarButton(right, Side.Right, state, haptics, Modifier.layoutId("R").let { m -> rightRef?.let { m.trackBounds(it) } ?: m })
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
        val lm = measurables.firstOrNull { it.layoutId == "L" }
        val rm = measurables.firstOrNull { it.layoutId == "R" }
        // One side only: it fills the bar (less the gap a centre button needs).
        val lw = when {
            lm == null -> 0
            rm == null -> free + gap
            else -> (free * gl / (gl + gr)).toInt().coerceIn(minW, free - minW)
        }
        val rw = if (rm == null) 0 else if (lm == null) free + gap else free - lw
        val l = lm?.measure(Constraints.fixed(lw, h))
        val r = rm?.measure(Constraints.fixed(rw, h))
        layout(constraints.maxWidth, h) {
            var x = 0
            l?.let { it.place(0, 0); x = lw + gap }
            c?.let { it.place(x, 0); x += it.width + gap }
            r?.place(x, 0)
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
        // The arrow says which way the swipe goes: ‹ back, › in. A tap-only choice has none.
        val arrow = choice.swipe
        if (arrow && side == Side.Left) Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, null, tint = choice.content, modifier = labelAlpha)
        if (arrow && side == Side.Left) Spacer(Modifier.width(6.dp))
        Text(text, style = Type.Action, color = choice.content, maxLines = 1, overflow = TextOverflow.Clip, modifier = labelAlpha)
        if (arrow && side == Side.Right) Spacer(Modifier.width(6.dp))
        if (arrow && side == Side.Right) Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = choice.content, modifier = labelAlpha)
    }
}

/**
 * Whether the person has stopped touching the screen for a while ([IDLE_MS]). The app's root feeds every touch into [touch]; the swipe
 * hints read [idle], so they stay out of the way while someone is working and come back only when they pause, as if to ask "what now?".
 */
@Stable
class IdleState internal constructor(private val scope: CoroutineScope) {
    var idle by mutableStateOf(false)
        private set
    private var timer: kotlinx.coroutines.Job? = null

    fun touch() {
        idle = false
        timer?.cancel()
        timer = scope.launch { kotlinx.coroutines.delay(IDLE_MS); idle = true }
    }

    companion object { const val IDLE_MS = 2_500L }
}

/** How long a hint stays before a fingertip comes and swipes it away. */
private const val HINT_LINGER_MS = 4_000L

@Composable
fun rememberIdleState(): IdleState {
    val scope = rememberCoroutineScope()
    return remember { IdleState(scope).also { it.touch() } }
}

/**
 * Room kept free at the bottom of every page for the running session's card, which floats above all pages (see MindVault for the order
 * of layers). The hints, always the lowest thing on a page, end with this much space, so the card never covers them.
 */
val LocalDockInset = androidx.compose.runtime.compositionLocalOf { 0.dp }

/** No provider (previews, tests): the hints always show. */
val LocalIdle = staticCompositionLocalOf<IdleState?> { null }

/** Every touch anywhere under this resets the idle timer. Watches only, in the Initial pass; never consumes. */
fun Modifier.watchIdle(idle: IdleState): Modifier = pointerInput(idle) {
    awaitPointerEventScope {
        while (true) {
            awaitPointerEvent(PointerEventPass.Initial)
            idle.touch()
        }
    }
}

/** One way off a screen: what going that way does ("go back", "record"). */
data class Way(val verb: String, val enabled: Boolean = true, val onClick: () -> Unit)

/**
 * The quiet form of the bar: no buttons, just a line of small text per way off the screen, saying the whole gesture (from which edge to
 * which, with the app's chevron for where it leads: ‹ back, › in) and what it does. Back on the left, in on the right, the same on every screen. They fade in after
 * a few seconds without a touch and away again when the screen is touched; during a swipe the line it heads for comes up as it goes, ink
 * dark once letting go would act ("Release to record"). A line can be tapped too, and with TalkBack on they are always there.
 * [center] (finishing, pausing) is never hidden.
 */
@Composable
fun SwipeHints(
    state: ChoiceState,
    back: Way?,
    forward: Way?,
    modifier: Modifier = Modifier,
    forwardRef: BoundsRef? = null,
    center: (@Composable () -> Unit)? = null,
) {
    val idle = LocalIdle.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val talkback = remember(ctx) { ctx.getSystemService(android.view.accessibility.AccessibilityManager::class.java)?.isTouchExplorationEnabled == true }
    val shown = idle == null || idle.idle || talkback
    // A hint's life: after a pause it fades in and stays a few seconds; then a fingertip lands on it and swipes it away, the very gesture
    // it names, and it is gone until the next pause. A touch sends it away at once. With TalkBack it stays.
    val rest = remember { Animatable(0f) }
    val grab = remember { Animatable(0f) }
    val fly = remember { Animatable(0f) }
    var live by remember { mutableStateOf(false) }
    val reduced = LocalReducedMotion.current
    val steady = talkback || idle == null // TalkBack, or no idle tracking at all (tests, previews): always there, never moving
    LaunchedEffect(shown, steady) {
        when {
            steady -> { fly.snapTo(0f); grab.snapTo(0f); rest.snapTo(1f); live = true }
            !shown -> { live = false; rest.animateTo(0f, Motion.Fade); fly.snapTo(0f); grab.snapTo(0f) }
            else -> while (true) {
                // Still nobody touching: show it, let it stay, swipe it off, and again after the next pause.
                fly.snapTo(0f); grab.snapTo(0f)
                live = true
                rest.animateTo(1f, Motion.Fade)
                kotlinx.coroutines.delay(HINT_LINGER_MS)
                live = false
                if (!reduced) {
                    grab.animateTo(1f, androidx.compose.animation.core.tween(320, easing = androidx.compose.animation.core.FastOutSlowInEasing))
                    kotlinx.coroutines.delay(120) // pressed: a beat before it moves, as a finger would
                    fly.animateTo(1f, androidx.compose.animation.core.tween(520, easing = androidx.compose.animation.core.FastOutSlowInEasing))
                } else {
                    rest.animateTo(0f, Motion.Fade)
                }
                rest.snapTo(0f); fly.snapTo(0f); grab.snapTo(0f)
                kotlinx.coroutines.delay(IdleState.IDLE_MS)
            }
        }
    }
    val seen = { rest.value * (1f - fly.value) }
    val haptics = rememberHaptics()
    androidx.compose.foundation.layout.Column(modifier) {
        if (center != null) {
            Box(Modifier.align(Alignment.CenterHorizontally).padding(bottom = 6.dp)) { center() }
        }
        val lines = @Composable { m: Modifier ->
            androidx.compose.foundation.layout.Column(m) {
                // A hidden line is not an invisible button: it only takes taps while it shows.
                if (back != null) HintLine(back, Side.Left, "Swipe from left to right to ${back.verb}", live, state, seen, { grab.value }, { fly.value }, haptics, null)
                if (forward != null) HintLine(forward, Side.Right, "Swipe from right to left to ${forward.verb}", live, state, seen, { grab.value }, { fly.value }, haptics, forwardRef)
            }
        }
        if (center != null) {
            // Under the session's controls: an ordinary line of the layout.
            lines(Modifier.fillMaxWidth())
        } else {
            // Everywhere else the hints take no room: they are hidden most of the time, so they float over the bottom of the page instead
            // of pushing it up, with the paper fading in behind them while they show so they stay readable over what scrolls under them.
            lines(
                Modifier.fillMaxWidth()
                    .layout { m, c ->
                        val p = m.measure(c)
                        layout(p.width, 0) { p.place(0, -p.height) }
                    }
                    .drawBehind {
                        // The fade behind comes and goes with the hint, so there is never an unexplained blank band.
                        val a = maxOf(seen(), state.left.value, state.right.value).coerceIn(0f, 1f)
                        if (a > 0f) {
                            val lift = 28.dp.toPx()
                            drawRect(
                                androidx.compose.ui.graphics.Brush.verticalGradient(
                                    0f to Palette.Paper.copy(alpha = 0f), 0.35f to Palette.Paper.copy(alpha = 0.92f * a), 1f to Palette.Paper.copy(alpha = 0.92f * a),
                                    startY = -lift, endY = size.height,
                                ),
                                topLeft = androidx.compose.ui.geometry.Offset(-size.width, -lift),
                                size = androidx.compose.ui.geometry.Size(size.width * 3f, size.height + lift),
                            )
                        }
                    },
            )
        }
        Spacer(Modifier.height(LocalDockInset.current))
    }
}

/** Room to leave at the end of a scrolling page so its last row can scroll clear of the floating hints (and the session's card). */
@Composable
fun hintsClearance(): androidx.compose.ui.unit.Dp = 96.dp + LocalDockInset.current

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.HintLine(
    way: Way, side: Side, text: String, shown: Boolean, state: ChoiceState, rest: () -> Float, grab: () -> Float, fly: () -> Float,
    haptics: Haptics, ref: BoundsRef?,
) {
    val pull = if (side == Side.Left) state.left else state.right
    val other = if (side == Side.Left) state.right else state.left
    val armed = state.armed == side
    // Which way the finger goes: +1 to the right (back), -1 to the left (in).
    val dir = if (side == Side.Left) 1f else -1f
    val armedText = "Release to ${way.verb}"
    val distance = 140.dp
    Row(
        Modifier.align(if (side == Side.Left) Alignment.Start else Alignment.End)
            .let { m -> ref?.let { m.trackBounds(it) } ?: m }
            .clickable(
                enabled = way.enabled && shown,
                interactionSource = remember { MutableInteractionSource() },
                indication = null, // a line of text, not a button: no box flashes behind it
                role = Role.Button,
                onClickLabel = way.verb,
            ) { haptics.press(); way.onClick() }
            .semantics(mergeDescendants = true) { contentDescription = if (armed) armedText else text }
            .padding(horizontal = 12.dp, vertical = 7.dp)
            .graphicsLayer {
                // Resting: the idle fade. Swiping toward it: comes up with the pull. Swiping the other way: gives way. Swiped off by the
                // fingertip: carried the way the gesture goes. Drawn moved, never laid out moved: nothing around it shifts.
                val toward = pull.value.coerceIn(0f, 1f)
                alpha = (maxOf(rest(), toward) * (1f - other.value.coerceIn(0f, 1f))).coerceIn(0f, 1f) * (if (way.enabled) 1f else 0.42f)
                translationX = dir * (10.dp.toPx() * toward + distance.toPx() * fly())
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val color = if (armed) Palette.Ink else Palette.InkSoft
        // The fingertip: lands on the edge the swipe starts from (it presses in, from a little larger), then carries the line away.
        val tip = @Composable {
            Box(
                Modifier.size(14.dp).graphicsLayer {
                    val g = grab()
                    alpha = (g * 0.9f).coerceIn(0f, 1f)
                    val press = 1.7f - 0.7f * g
                    scaleX = press; scaleY = press
                }.clip(androidx.compose.foundation.shape.CircleShape).background(Palette.Ink.copy(alpha = 0.55f)),
            )
        }
        val words = @Composable {
            SteadyText(listOf(text, armedText), Type.Label, Modifier.weight(1f, fill = false).clearAndSetSemantics { }) {
                Text(
                    if (armed) armedText else text,
                    style = Type.Label,
                    color = color,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = if (side == Side.Left) TextAlign.Start else TextAlign.End,
                )
            }
        }
        if (side == Side.Left) { tip(); Spacer(Modifier.width(8.dp)); words() } else { words(); Spacer(Modifier.width(8.dp)); tip() }
    }
}
