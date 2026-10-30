package com.therapylocal.app.ui.motion

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.therapylocal.app.ui.design.LocalReducedMotion
import com.therapylocal.app.ui.design.Motion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Where something lives on screen: its bounds in the root, so a sheet can grow out of it and fold back into it. */
@Stable
class BoundsRef {
    var rect by mutableStateOf(Rect.Zero)
        internal set
}

fun Modifier.trackBounds(ref: BoundsRef): Modifier = onGloballyPositioned { ref.rect = it.boundsInRoot() }

/** The thing a sheet grows out of: its bounds, its colour (the sheet starts that colour) and its corner radius in px. */
data class Origin(val rect: Rect, val color: Color, val radius: Float)

/**
 * A full-screen surface that grows out of the control that opened it and folds back into it ("container transform"). One [Animatable],
 * [progress] (0 folded, 1 open), drives everything; taps, drags and the predictive back gesture all move the same value, so the sheet can be
 * grabbed mid-flight and reversed at any moment. Nothing about it ever waits for an animation to finish.
 */
@Stable
class SheetState internal constructor(private val scope: CoroutineScope) {
    val progress = Animatable(0f)

    var origin by mutableStateOf(Origin(Rect.Zero, Color.Transparent, 0f))
        private set

    /** Where it should go: true while opening or open. */
    var target by mutableStateOf(false)
        private set

    /** On screen at all: composed while opening, open, or still folding away. */
    val visible by derivedStateOf { target || progress.value > 0.0005f }

    private var onFolded: (() -> Unit)? = null

    fun open(from: Origin) {
        origin = from
        target = true
        onFolded = null
        scope.launch { progress.animateTo(1f, Motion.Sheet) }
    }

    /** Folds into [into] (the same origin when null) from wherever it is now, keeping its current velocity. [then] runs once fully folded. */
    fun close(into: Origin? = null, velocity: Float = progress.velocity, then: (() -> Unit)? = null) {
        if (into != null) origin = into
        target = false
        onFolded = then
        scope.launch {
            try {
                progress.animateTo(0f, Motion.Sheet, initialVelocity = velocity)
                onFolded?.invoke()
                onFolded = null
            } catch (_: CancellationException) {
                // re-grabbed or reopened: the new motion owns it now
            }
        }
    }

    /** Follows a finger or the back gesture: [p] 0..1, applied at once. */
    fun follow(p: Float) {
        scope.launch { progress.snapTo(p.coerceIn(0f, 1f)) }
    }

    /** Back to open after a drag that did not commit, with the release velocity. */
    fun settleOpen(velocity: Float = 0f) {
        target = true
        scope.launch { progress.animateTo(1f, Motion.Sheet, initialVelocity = velocity) }
    }

    /** Reconnects to a different origin while open (the mini pill moved, the list scrolled). */
    fun retarget(to: Origin) {
        origin = to
    }
}

@Composable
fun rememberSheetState(): SheetState {
    val scope = rememberCoroutineScope()
    return remember { SheetState(scope) }
}

/**
 * Draws [state] as a rounded rect interpolated from its origin to the full screen, filled with a colour that turns from the origin's colour
 * into [color] early on (so it reads as the button becoming the page), with [content] fading in during the second half.
 * With reduced motion it only fades. All reads of the animated value happen in the draw and layer phases, never in composition.
 * [onBack] handles the system back gesture with a live preview: the sheet shrinks toward its origin as the finger moves.
 */
@Composable
fun ContainerTransform(
    state: SheetState,
    color: Color,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    if (!state.visible) return
    val reduced = LocalReducedMotion.current
    if (onBack != null) {
        PredictiveBackHandler(enabled = state.target) { events ->
            try {
                events.collect { e -> state.follow(1f - 0.32f * GestureMath.smoothstep(0f, 1f, e.progress)) }
                onBack()
            } catch (e: CancellationException) {
                state.settleOpen()
                throw e
            }
        }
    }
    Box(
        modifier
            .fillMaxSize()
            .graphicsLayer {
                val q = state.progress.value
                if (reduced) {
                    alpha = q
                    return@graphicsLayer
                }
                val o = state.origin.rect
                val k = 1f - q
                val full = Rect(0f, 0f, size.width, size.height)
                val r = if (o == Rect.Zero) full else Rect(o.left * k, o.top * k, full.right - (full.right - o.right) * k, full.bottom - (full.bottom - o.bottom) * k)
                clip = true
                shape = RectShape(r, (state.origin.radius * k).coerceAtLeast(0f))
            }
            .drawBehind {
                val q = state.progress.value
                val m = if (reduced) 1f else GestureMath.smoothstep(0.12f, 0.65f, q)
                drawRect(lerp(state.origin.color, color, m))
            },
    ) {
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = if (reduced) 1f else ((state.progress.value - 0.5f) / 0.4f).coerceIn(0f, 1f) }, content = content)
    }
}

private class RectShape(private val r: Rect, private val radius: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rounded(RoundRect(r, CornerRadius(radius)))
}
