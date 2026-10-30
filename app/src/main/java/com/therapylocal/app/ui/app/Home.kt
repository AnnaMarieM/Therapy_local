package com.therapylocal.app.ui.app

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.therapylocal.app.ui.design.SteadyText
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.therapylocal.app.session.SessionPhase
import com.therapylocal.app.session.SessionState
import com.therapylocal.app.ui.design.Choice
import com.therapylocal.app.ui.design.ChoiceBar
import com.therapylocal.app.ui.design.SwipeHints
import com.therapylocal.app.ui.design.Way
import com.therapylocal.app.ui.design.ChoiceState
import com.therapylocal.app.ui.design.LocalReducedMotion
import com.therapylocal.app.ui.design.Motion
import com.therapylocal.app.ui.design.Palette
import com.therapylocal.app.ui.design.Type
import com.therapylocal.app.ui.design.ACTION_MAX_DP
import com.therapylocal.app.ui.design.ACTION_MIN_DP
import com.therapylocal.app.ui.design.ACTION_SHARE
import com.therapylocal.app.ui.design.rememberHaptics
import com.therapylocal.app.ui.logic.ReelIndex
import com.therapylocal.app.ui.logic.SessionLogic
import com.therapylocal.app.ui.logic.greeting
import com.therapylocal.app.ui.logic.railLetterAt
import com.therapylocal.app.ui.logic.railLetterY
import com.therapylocal.app.ui.logic.splitName
import com.therapylocal.app.ui.models.Patient
import com.therapylocal.app.ui.motion.Axis
import com.therapylocal.app.ui.motion.BoundsRef
import com.therapylocal.app.ui.motion.GestureMath
import com.therapylocal.app.ui.motion.ReelItemLook
import com.therapylocal.app.ui.motion.Side
import com.therapylocal.app.ui.motion.axisDrag
import com.therapylocal.app.ui.motion.axisDragHandler
import com.therapylocal.app.ui.motion.trackBounds
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/** One line of status under the selected name. [onTap] makes it actionable (retry). */
/**
 * A status line. [details] are the evidence behind a busy one (how far along, how fast, measured time left); once the line has been looked
 * at for a while they take its place, one at a time, so a glance gets the gist and a longer look gets the numbers.
 */
data class ClientStatus(
    val text: String,
    val color: androidx.compose.ui.graphics.Color = Palette.InkSoft,
    val busy: Boolean = false,
    val details: List<String> = emptyList(),
    val onTap: (() -> Unit)? = null,
)

/** What the home screen needs from the app. Origins are where a sheet should grow from. */
class HomeActions(
    val openProfile: (Patient) -> Unit,
    val openSession: () -> Unit,
    val addClient: () -> Unit,
    val deleteClient: (Patient) -> Unit,
    val openSettings: () -> Unit,
)

/** Places the sheets grow from. Owned by the app so it can fold sheets back into them. */
class HomeRefs {
    val right = BoundsRef()
    val pill = BoundsRef()
    val gear = BoundsRef()
}

/**
 * The client reel. One subject: the selected name, large. Everything else is quiet until touched.
 *
 * - Up/down anywhere scrolls the names, 1:1 with the finger, and a fling settles on the name it would land on.
 * - The letter rail on the right is a pointer, not a scroll: touch or slide on it and the reel jumps letter by letter to whatever is under
 *   the finger, with a large letter beside the thumb. It always runs A to Z top to bottom.
 * - Sideways anywhere pulls the bar: toward Profile or toward Record, released armed acts. Both buttons can be tapped too.
 * - The last item is "New client".
 */
@Composable
fun Home(
    clients: List<Patient>,
    statusOf: (Patient) -> ClientStatus,
    session: SessionState,
    setupLine: ClientStatus?,
    focusClient: String?,
    onFocused: () -> Unit,
    hint: Boolean,
    onLearned: () -> Unit,
    bar: ChoiceState,
    refs: HomeRefs,
    actions: HomeActions,
) {
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val reduced = LocalReducedMotion.current
    val index = remember(clients) { ReelIndex(clients.map { it.name }) }
    val ordered = remember(clients, index) { index.order.map { clients[it] } }
    val count = ordered.size + 1 // + "New client"
    val pos = remember { Animatable(0f) }
    val shift = remember { Animatable(0f) }
    val arrive = remember { Animatable(if (reduced) 1f else 0f) }
    var quiet by remember { mutableStateOf(false) }
    var railActive by remember { mutableStateOf(false) }
    var railLetter by remember { mutableIntStateOf(-1) }
    var railY by remember { mutableFloatStateOf(0f) }
    var confirmDelete by remember { mutableStateOf<Patient?>(null) }
    val selected by remember(count) { derivedStateOf { pos.value.roundToInt().coerceIn(0, count - 1) } }
    val current = ordered.getOrNull(selected)
    val sessionActive = session.active || session.phase == SessionPhase.Finishing

    LaunchedEffect(Unit) { arrive.animateTo(1f, Motion.Arrive) }
    // The list changed under the reel (a client added or removed): keep it in range, and land on a client the app asked to show.
    LaunchedEffect(count) { if (pos.value > count - 1) pos.animateTo((count - 1).toFloat(), Motion.Reel) }
    LaunchedEffect(focusClient, ordered) {
        val i = ordered.indexOfFirst { it.id == focusClient }
        if (focusClient != null && i >= 0) { pos.animateTo(i.toFloat(), Motion.Reel); onFocused() }
    }
    // One tick per name crossing the centre line, during drags and flings alike; the rail ticks per letter instead.
    LaunchedEffect(count) {
        var first = true
        snapshotFlow { pos.value.roundToInt().coerceIn(0, count - 1) }.distinctUntilChanged().collect {
            if (!first && !quiet) haptics.tick()
            first = false
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val itemPx = with(density) { 112.dp.toPx() }
        val near = with(density) { 118.dp.toPx() }
        val far = with(density) { 46.dp.toPx() }
        val centerY = h * 0.36f
        val railTop = h * 0.2f
        val railH = minOf(h * 0.5f, with(density) { (index.letters.size * 30).dp.toPx() })
        val railW = with(density) { 56.dp.toPx() }
        bar.tact = (w * ACTION_SHARE).coerceIn(with(density) { ACTION_MIN_DP.dp.toPx() }, with(density) { ACTION_MAX_DP.dp.toPx() })
        val inRail = { x: Float, y: Float -> index.letters.size > 1 && x > w - railW && y > railTop - railW / 2 && y < railTop + railH + railW / 2 }
        val heroSp = (constraints.maxWidth / density.density * 0.145f).coerceIn(46f, 86f)

        val drag = remember(count, w, h) {
            axisDragHandler(
                accepts = { a, start, _ -> !inRail(start.x, start.y) && (a == Axis.Horizontal || count > 1) },
                onStart = { a, _ ->
                    onLearned()
                    if (a == Axis.Vertical) scope.launch { pos.stop() }
                },
                onDrag = { a, total, delta ->
                    if (a == Axis.Vertical) {
                        scope.launch { pos.snapTo(GestureMath.reelDrag(pos.value, -delta / itemPx, count)) }
                    } else {
                        // Home is the top: there is nowhere back, only in (to the client, or to adding one).
                        bar.drag(total) { s -> s == Side.Right }
                        scope.launch { shift.snapTo(GestureMath.rubberBand(total, bar.tact) * 0.3f) }
                    }
                },
                onEnd = { a, _, velocity ->
                    if (a == Axis.Vertical) {
                        val v = -velocity / itemPx
                        val target = GestureMath.snapIndex(pos.value, v, count)
                        scope.launch { pos.animateTo(target.toFloat(), Motion.Reel, initialVelocity = v) }
                    } else {
                        scope.launch { shift.animateTo(0f, Motion.Throw) }
                        if (bar.release() == Side.Right) goIn(selected, ordered, actions)
                    }
                },
            )
        }

        Box(Modifier.fillMaxSize().axisDrag(drag)) {
            // ---- the reel ----
            val window by remember(count) { derivedStateOf { floor(pos.value).toInt() } }
            Box(
                Modifier.fillMaxSize().graphicsLayer {
                    translationX = shift.value
                    val pull = maxOf(bar.left.value, bar.right.value).coerceIn(0f, 1f)
                    alpha = arrive.value * (1f - 0.45f * pull) * (if (railActive) 0.9f else 1f)
                },
            ) {
                for (i in (window - 4).coerceAtLeast(0)..(window + 5).coerceAtMost(count - 1)) {
                    key(if (i < ordered.size) ordered[i].id else "+new") {
                        ReelItem(
                            client = ordered.getOrNull(i),
                            look = { ReelItemLook.of(i - pos.value, near, far) },
                            centerY = centerY + (1f - arrive.value) * 26f,
                            heroSp = heroSp,
                            onTap = {
                                onLearned()
                                if (i == selected) {
                                    if (i < ordered.size) actions.openProfile(ordered[i]) else actions.addClient()
                                } else scope.launch { pos.animateTo(i.toFloat(), Motion.Reel) }
                            },
                            onLongPress = ordered.getOrNull(i)?.let { c -> { if (i == selected) { haptics.arm(); confirmDelete = c } } },
                        )
                    }
                }
                // Status under the selected name: fades while moving, so it never shows a neighbour's details.
                val status = current?.let(statusOf) ?: if (ordered.isEmpty()) ClientStatus("Add your first client to begin") else ClientStatus("Add someone new")
                StatusLine(
                    status,
                    Modifier.offset { IntOffset(26.dp.roundToPx(), (centerY + with(density) { (heroSp * 1.05f).sp.toPx() } + 18.dp.toPx()).roundToInt()) }
                        .graphicsLayer {
                            val f = 1f - abs(pos.value - pos.value.roundToInt()) * 2f
                            val still = 1f - (abs(pos.velocity) / 6f).coerceIn(0f, 1f)
                            alpha = GestureMath.smoothstep(0.5f, 1f, f) * still * arrive.value
                        },
                )
            }

            // ---- the rail ----
            if (index.letters.size > 1) {
                val lettersFocus = { if (railActive) railLetterAt(railY, railTop, railH, index.letters.size).toFloat() else index.letterOf.getOrElse(selected.coerceAtMost(ordered.size - 1).coerceAtLeast(0)) { 0 }.toFloat() }
                Rail(
                    letters = index.letters,
                    top = railTop, height = railH, width = railW,
                    active = railActive,
                    focus = lettersFocus,
                    arrive = { arrive.value },
                    modifier = Modifier.align(Alignment.TopEnd).pointerInput(index, railTop, railH) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            if (!inRail(down.position.x + (w - size.width), down.position.y)) return@awaitEachGesture
                            down.consume()
                            onLearned()
                            railActive = true
                            quiet = true
                            haptics.mode()
                            var y = down.position.y
                            fun jump() {
                                railY = y
                                val li = railLetterAt(y, railTop, railH, index.letters.size)
                                if (li != railLetter) {
                                    railLetter = li
                                    haptics.tick()
                                    scope.launch { pos.animateTo(index.firstOf[li].toFloat(), Motion.Throw) }
                                }
                            }
                            jump()
                            while (true) {
                                val ev = awaitPointerEvent(PointerEventPass.Initial)
                                val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                if (!ch.pressed) break
                                y = ch.position.y
                                jump()
                                ch.consume()
                            }
                            railActive = false
                            railLetter = -1
                            scope.launch { kotlinx.coroutines.delay(400); quiet = false }
                        }
                    }.semantics { contentDescription = "Letters. Touch a letter to jump to it." },
                )
                // The letter under the thumb, large, beside it: unmistakably a jump, not a scroll.
                if (railActive && railLetter >= 0) {
                    val li = railLetter
                    val first = ordered.getOrNull(index.firstOf[li])?.name.orEmpty()
                    Bubble(
                        letter = index.letters[li], first = first,
                        modifier = Modifier.align(Alignment.TopEnd).offset {
                            IntOffset(-(railW + 16.dp.toPx()).roundToInt(), (railLetterY(li, railTop, railH, index.letters.size) - 44.dp.toPx()).roundToInt())
                        },
                    )
                }
            }

            // ---- top: greeting, setup, settings ----
            Column(Modifier.statusBarsPadding().padding(start = 26.dp, top = 22.dp, end = 80.dp).graphicsLayer { alpha = arrive.value }) {
                Text("${greeting(java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY))}.", style = Type.Lead, color = Palette.InkSoft)
                Spacer(Modifier.height(6.dp))
                when {
                    setupLine != null -> StatusLine(setupLine, Modifier)
                    hint && count > 1 -> Label("Swipe up or down to browse", color = Palette.InkFaint)
                }
            }
            Box(
                Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 10.dp, end = 10.dp).size(52.dp)
                    .trackBounds(refs.gear).clip(CircleShape).pressable(actions.openSettings, label = "Settings"),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Tune, contentDescription = "Settings", tint = Palette.InkSoft) }

            // ---- bottom: the way in (the running session's card floats above this, over every page) ----
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(horizontal = 14.dp, vertical = 14.dp)) {
                val isNew = selected >= ordered.size
                SwipeHints(
                    bar,
                    back = null,
                    forward = if (isNew) Way("add a client") { actions.addClient() }
                    else Way("open ${current?.let { splitName(it.name).first } ?: "the profile"}") { current?.let(actions.openProfile) },
                    forwardRef = refs.right,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    confirmDelete?.let { c ->
        ConfirmDialog(
            title = "Delete ${c.name}?",
            text = "Their profile, every session, transcript and fact is removed from this phone. This cannot be undone.",
            confirm = "Delete",
            onConfirm = { confirmDelete = null; actions.deleteClient(c) },
            onDismiss = { confirmDelete = null },
        )
    }
}

/** The one way in from home: the selected client's profile (recording starts there), or adding a client. */
private fun goIn(selected: Int, ordered: List<Patient>, actions: HomeActions) {
    if (selected < ordered.size) actions.openProfile(ordered[selected]) else actions.addClient()
}

@Composable
private fun ReelItem(
    client: Patient?,
    look: () -> ReelItemLook,
    centerY: Float,
    heroSp: Float,
    onTap: () -> Unit,
    onLongPress: (() -> Unit)?,
) {
    val (first, rest) = client?.let { splitName(it.name) } ?: ("New" to "client")
    val ink = if (client == null) Palette.InkSoft else Palette.Ink
    val heroSize = (heroSp * (if (first.length > 9) 9f / first.length else 1f)).sp
    val tap by rememberUpdatedState(onTap)
    val long by rememberUpdatedState(onLongPress)
    var bigH by remember { mutableFloatStateOf(0f) }
    Box(
        Modifier.fillMaxWidth().padding(start = 26.dp, end = 72.dp)
            .offset { IntOffset(0, (centerY + look().y).roundToInt()) }
            .semantics {
                contentDescription = client?.name ?: "New client"
                if (onLongPress != null) customActions = listOf(CustomAccessibilityAction("Delete client") { long?.invoke(); true })
            },
    ) {
        // The large two-line form for the centre, the small one-line form for the neighbours; the reel crossfades between them.
        Column(
            Modifier.onGloballyPositioned { bigH = it.size.height.toFloat() }
                .graphicsLayer {
                    val l = look()
                    alpha = l.bigAlpha
                    scaleX = l.bigScale; scaleY = l.bigScale
                    transformOrigin = TransformOrigin(0f, 0.5f)
                    translationY = -bigH / 2
                }
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { tap() }, onLongPress = { long?.invoke() })
                },
        ) {
            val style = Type.Hero.copy(fontSize = heroSize, lineHeight = heroSize * 0.95f)
            Text(first, style = style, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (rest != null) Text(rest, style = style.copy(fontStyle = FontStyle.Italic), color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(
            client?.name ?: "New client",
            style = Type.Title,
            color = ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.graphicsLayer { alpha = look().smallAlpha; translationY = -size.height / 2 }
                .pointerInput(Unit) { detectTapGestures(onTap = { tap() }, onLongPress = { long?.invoke() }) },
        )
    }
}

@Composable
internal fun StatusLine(s: ClientStatus, modifier: Modifier) {
    // Looked at for DETAIL_AFTER_MS (on screen that long, whatever it says meanwhile), the gist gives way to the evidence: each line in
    // turn, quietly crossfading, so something on screen is always moving with the work and none of it is invented.
    val has = s.details.isNotEmpty()
    var looked by remember { mutableStateOf(false) }
    var turn by remember { mutableIntStateOf(0) }
    LaunchedEffect(has) {
        if (!has) { looked = false; return@LaunchedEffect }
        kotlinx.coroutines.delay(DETAIL_AFTER_MS)
        looked = true
        while (true) { kotlinx.coroutines.delay(DETAIL_TURN_MS); turn++ }
    }
    val text = if (looked && has) s.details[turn % s.details.size] else s.text
    Row(
        modifier.let { m -> s.onTap?.let { m.pressable(it, label = s.text) } ?: m }.padding(vertical = 4.dp).testTag(STATUS_TAG),
        verticalAlignment = Alignment.Top,
    ) {
        if (s.busy) { PulseDot(s.color, size = 6, modifier = Modifier.padding(top = 5.dp)); Spacer(Modifier.width(8.dp)) }
        // Fades only when the line becomes a different message (the gist, or another of the evidence lines). Numbers ticking within one
        // ("149 MB" to "151 MB", "00:07" to "00:08") just update in place: a line that blinks on every update reads as broken.
        val slot = if (looked && has) "d${turn % s.details.size}" else "g:" + s.text.replace(DIGITS, "#")
        // As tall as its longest message, so turning from one to the next never moves the page; while busy it never shrinks back.
        SteadyText((listOf(s.text) + s.details).map { it.uppercase() }, Type.Label, key = s.busy) {
            androidx.compose.animation.Crossfade(slot, animationSpec = androidx.compose.animation.core.tween(600), label = "status") { k ->
                val t = if (k.startsWith("d")) s.details.getOrNull(k.drop(1).toInt()) ?: text else s.text
                Label(t, color = s.color)
            }
        }
    }
}

/** How long a busy status is shown as its gist before it turns into the numbers behind it, and how long each of those stays. */
private const val DETAIL_AFTER_MS = 10_000L
private const val DETAIL_TURN_MS = 4_000L
private val DIGITS = Regex("[0-9][0-9.,:]*")

/** Test handle on a status line (the layout-stability tests measure what is around it). */
const val STATUS_TAG = "status"

@Composable
private fun Rail(
    letters: List<String>,
    top: Float,
    height: Float,
    width: Float,
    active: Boolean,
    focus: () -> Float,
    arrive: () -> Float,
    modifier: Modifier,
) {
    val density = LocalDensity.current
    val spread = remember { Animatable(0f) }
    LaunchedEffect(active) { spread.animateTo(if (active) 1f else 0f, Motion.Fade) }
    Box(modifier.width(with(density) { width.toDp() }).height(with(density) { (top + height + width).toDp() })) {
        letters.forEachIndexed { i, l ->
            Text(
                l,
                style = Type.Label.copy(fontSize = 12.sp),
                color = Palette.Ink,
                modifier = Modifier.align(Alignment.TopEnd).padding(end = 22.dp)
                    .offset { IntOffset(0, (railLetterY(i, top, height, letters.size) - 8.dp.toPx()).roundToInt()) }
                    .graphicsLayer {
                        val d = i - focus()
                        val ra = spread.value
                        val mag = kotlin.math.exp(-d * d / (1.5f + ra * 1.2f))
                        val s = 1f + (0.9f + ra * 0.9f) * mag
                        scaleX = s; scaleY = s
                        translationX = -mag * (6f + ra * 8f) * density.density
                        alpha = ((0.34f + 0.6f * mag + 0.12f * ra) * arrive()).coerceIn(0f, 1f)
                        transformOrigin = TransformOrigin(1f, 0.5f)
                    },
            )
        }
    }
}

@Composable
private fun Bubble(letter: String, first: String, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(first, style = Type.Label, color = Palette.InkSoft, maxLines = 1, modifier = Modifier.widthIn(max = 160.dp).padding(end = 12.dp))
        Box(Modifier.size(88.dp).clip(CircleShape).background(Palette.Ink), contentAlignment = Alignment.Center) {
            Text(letter, style = Type.Hero.copy(fontSize = 52.sp, lineHeight = 52.sp), color = Palette.Paper)
        }
    }
}

/**
 * The running session, docked at the bottom of whatever page is open: over home, a profile, settings, anything but the session itself and
 * the confirmation dialogs. Tap it to go back into the session.
 */
@Composable
internal fun SessionPill(name: String?, paused: Boolean, elapsed: () -> Long, ref: BoundsRef, open: () -> Unit, modifier: Modifier = Modifier) {
    var now by remember { mutableStateOf(elapsed()) }
    LaunchedEffect(paused) { while (true) { now = elapsed(); kotlinx.coroutines.delay(250) } }
    val time = SessionLogic.formatMinSec(now / 1000)
    // Who it is, large: the one thing to see at a glance. What is happening, small, underneath.
    Row(
        modifier.trackBounds(ref).clip(RoundedCornerShape(24.dp)).background(Palette.Ink)
            .pressable(open, label = "Open the session", depth = 0.015f)
            .padding(start = 20.dp, end = 12.dp, top = 14.dp, bottom = 14.dp)
            .semantics(mergeDescendants = true) { stateDescription = if (paused) "Paused" else "Recording" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(name.orEmpty(), style = Type.Title.copy(fontSize = 30.sp, lineHeight = 34.sp), color = Palette.Paper, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                PulseDot(Palette.Rec, still = paused, size = 6)
                Spacer(Modifier.width(8.dp))
                Label(if (paused) "Session paused · $time" else "Session recording in progress · $time", color = Palette.Paper.copy(alpha = 0.75f))
            }
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = Palette.Paper)
    }
}

/** The rect of a [BoundsRef], or the bottom of the screen when it has not been laid out yet. */
fun BoundsRef.orBottom(w: Float, h: Float): Rect = if (rect == Rect.Zero) Rect(0f, h * 0.9f, w, h) else rect
