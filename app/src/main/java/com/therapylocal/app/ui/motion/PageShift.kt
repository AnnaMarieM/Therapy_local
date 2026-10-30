package com.therapylocal.app.ui.motion

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import com.therapylocal.app.ui.design.LocalReducedMotion
import com.therapylocal.app.ui.design.Motion
import com.therapylocal.app.ui.design.drawPaper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Where something lives on screen: its bounds (in the root, or in a [Frame]), so it can travel from one page to the next. */
@Stable
class BoundsRef {
    var rect by mutableStateOf(Rect.Zero)
        internal set

    /** False while the thing is scrolled out of sight (its last [rect] is stale then). */
    var present by mutableStateOf(true)
}

/** A page's own coordinate space, before any motion is applied to it: bounds measured in it are where things are at rest. */
class Frame {
    internal var coords: LayoutCoordinates? = null
}

fun Modifier.frame(f: Frame): Modifier = onPlaced { f.coords = it }

/** Tracks bounds in [within] (at rest, whatever the page's motion), or in the root when there is none. */
fun Modifier.trackBounds(ref: BoundsRef, within: Frame? = null): Modifier = onGloballyPositioned { c ->
    val f = within?.coords?.takeIf { it.isAttached }
    ref.rect = if (f != null) f.localBoundingBoxOf(c, clipBounds = false) else c.boundsInRoot()
}

/** The [Frame] of the page being composed, for things on it that travel between pages. */
val LocalPageFrame = staticCompositionLocalOf<Frame?> { null }

/**
 * A page on top of another. There is one sheet of paper and pages never move as rectangles: the page underneath dissolves into the paper
 * and steps aside, and this one's content slides in from the side the swipe pointed to. One [Animatable], [progress] (0 away, 1 here),
 * drives it all; taps, drags and the back gesture all move that same value, so a page can be grabbed mid-flight and sent either way.
 */
@Stable
class SheetState internal constructor(private val scope: CoroutineScope, width: Float) {
    val progress = Animatable(0f)

    /** How far the content travels between away and here, in px. A sideways drag of this much is the whole way. */
    val shiftPx: Float = (width * SHIFT_SHARE).coerceAtLeast(1f)

    /** Where it should go: true while arriving or here. */
    var target by mutableStateOf(false)
        private set

    /** On screen at all: composed while arriving, here, or still going. */
    val visible by derivedStateOf { target || progress.value > 0.0005f }

    private var onGone: (() -> Unit)? = null

    // The speed of the finger that was moving it (progress per second), handed to the spring on release.
    private var lastP = -1f
    private var lastT = 0L
    private var fingerV = 0f

    fun open(velocity: Float = handoff()) {
        target = true
        onGone = null
        scope.launch { progress.animateTo(1f, Motion.Sheet, initialVelocity = velocity) }
    }

    /** Sends it away from wherever it is now, keeping the speed it had. [then] runs once it is gone. */
    fun close(velocity: Float = handoff(), then: (() -> Unit)? = null) {
        target = false
        onGone = then
        scope.launch {
            try {
                progress.animateTo(0f, Motion.Sheet, initialVelocity = velocity)
                onGone?.invoke()
                onGone = null
            } catch (_: CancellationException) {
                // re-grabbed or reopened: the new motion owns it now
            }
        }
    }

    /** Follows a finger or the back gesture: [p] 0..1, applied at once. */
    fun follow(p: Float) {
        val now = System.nanoTime()
        if (lastP >= 0f && now > lastT) fingerV = 0.6f * (p - lastP) / ((now - lastT) / 1e9f) + 0.4f * fingerV
        lastP = p
        lastT = now
        scope.launch { progress.snapTo(p.coerceIn(0f, 1f)) }
    }

    /** Back to here after a drag that did not commit, with the speed it was let go at. */
    fun settleOpen(velocity: Float = handoff()) {
        target = true
        scope.launch { progress.animateTo(1f, Motion.Sheet, initialVelocity = velocity) }
    }

    private fun handoff(): Float {
        val v = if (lastP >= 0f && System.nanoTime() - lastT < 120_000_000L) fingerV else progress.velocity
        lastP = -1f
        fingerV = 0f
        return v
    }
}

@Composable
fun rememberSheetState(): SheetState {
    val scope = rememberCoroutineScope()
    val width = LocalConfiguration.current.screenWidthDp * LocalDensity.current.density
    return remember { SheetState(scope, width) }
}

/** Share of the screen width a page's content travels on its way in or out. */
const val SHIFT_SHARE = 0.4f

/** How much paper has come over what is underneath: it is covered by the time the new content starts to show, so two pages never mix. */
internal fun paperIn(q: Float) = GestureMath.smoothstep(0.2f, 0.6f, q)

/** How much of the arriving content shows. */
internal fun contentIn(q: Float) = GestureMath.smoothstep(0.6f, 0.97f, q)

/**
 * Draws [state] as a page: paper that comes over what is underneath, then [content] sliding in from the right as it fades in. Pages that
 * can be opened over this one go in [under]: while one arrives, this page's content steps left, out of its way, under its paper.
 * All reads of the animated values happen in the draw and layer phases, never in composition.
 * [onBack] handles the system back gesture with a live preview: the page goes back the way it came as the finger moves.
 */
@Composable
fun PageShift(
    state: SheetState,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    under: List<SheetState> = emptyList(),
    content: @Composable BoxScope.() -> Unit,
) {
    if (!state.visible) return
    val reduced = LocalReducedMotion.current
    if (onBack != null) {
        PredictiveBackHandler(enabled = state.target) { events ->
            try {
                events.collect { e -> state.follow(1f - e.progress) }
                onBack()
            } catch (e: CancellationException) {
                state.settleOpen()
                throw e
            }
        }
    }
    val frame = remember { Frame() }
    Box(modifier.fillMaxSize().drawBehind { drawPaper(if (reduced) state.progress.value else paperIn(state.progress.value)) }) {
        CompositionLocalProvider(LocalPageFrame provides frame) {
            Box(
                Modifier.fillMaxSize()
                    .graphicsLayer {
                        val q = state.progress.value
                        if (reduced) {
                            alpha = q
                            return@graphicsLayer
                        }
                        translationX = (1f - q) * state.shiftPx - coverage(under) * state.shiftPx * RECEDE_SHARE
                        alpha = contentIn(q)
                    }
                    // Inside the layer: bounds measured against this frame are where things are at rest.
                    .frame(frame),
                content = content,
            )
        }
    }
}

/**
 * For what lies under pages that may arrive on top (home, the session's card): it steps aside while any of [over] comes in. It does not
 * fade itself; the arriving page's paper covers it, and fading both would leave an empty page half-way.
 */
fun Modifier.recede(over: List<SheetState>, reduced: Boolean): Modifier =
    if (reduced) this else graphicsLayer { translationX = -coverage(over) * (over.firstOrNull()?.shiftPx ?: 0f) * RECEDE_SHARE }

/** How far the page underneath steps aside, as a share of the arriving page's travel. */
private const val RECEDE_SHARE = 0.25f

private fun coverage(over: List<SheetState>): Float {
    var m = 0f
    for (s in over) m = maxOf(m, s.progress.value)
    return m
}
