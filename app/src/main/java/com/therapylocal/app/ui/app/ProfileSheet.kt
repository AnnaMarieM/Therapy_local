package com.therapylocal.app.ui.app

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.therapylocal.app.SearchState
import com.therapylocal.app.ui.data.ClientRepository
import com.therapylocal.app.ui.design.Choice
import com.therapylocal.app.ui.design.ChoiceBar
import com.therapylocal.app.ui.design.SwipeHints
import com.therapylocal.app.ui.design.hintsClearance
import com.therapylocal.app.ui.design.Way
import com.therapylocal.app.ui.design.backChoice
import com.therapylocal.app.ui.design.Motion
import com.therapylocal.app.ui.design.Palette
import com.therapylocal.app.ui.design.Type
import com.therapylocal.app.ui.design.rememberChoiceState
import com.therapylocal.app.ui.design.rememberHaptics
import com.therapylocal.app.ui.logic.splitName
import com.therapylocal.app.ui.models.FactItem
import com.therapylocal.app.ui.models.FactOccurrence
import com.therapylocal.app.ui.models.Patient
import com.therapylocal.app.ui.models.SearchHit
import com.therapylocal.app.ui.models.SessionItem
import com.therapylocal.app.ui.models.TranscriptLine
import com.therapylocal.app.ui.motion.Axis
import com.therapylocal.app.ui.motion.BoundsRef
import com.therapylocal.app.ui.motion.LocalPageFrame
import com.therapylocal.app.ui.motion.PageShift
import com.therapylocal.app.ui.motion.SheetState
import com.therapylocal.app.ui.motion.Side
import com.therapylocal.app.ui.motion.axisDrag
import com.therapylocal.app.ui.motion.axisDragHandler
import com.therapylocal.app.ui.motion.recede
import com.therapylocal.app.ui.motion.rememberSheetState
import com.therapylocal.app.ui.motion.trackBounds
import com.therapylocal.app.ui.design.LocalReducedMotion
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Search wiring from the view model, so the profile stays a plain composable. */
class SearchActions(
    val state: SearchState,
    val voiceDisabled: String?,
    val text: (String) -> Unit,
    val voiceStart: () -> Boolean,
    val voiceStop: () -> Unit,
    val clear: () -> Unit,
)

/** Something opened from the profile, shown as a layer that grows out of the row that was tapped. */
private sealed interface Layer {
    data class Fact(val fact: FactItem) : Layer
    data class Transcript(val title: String, val sessionId: String, val date: String, val from: Int? = null, val to: Int? = null, val highlight: Int? = null) : Layer
}

/**
 * A client: who they are and what is known. One scrolling page, no tabs: search, what is being processed, the facts, the sessions.
 * Swipe right (or go back) to fold it into the button it came from; swipe left to record.
 */
@Composable
fun ProfileSheet(
    sheet: SheetState,
    patient: Patient,
    repo: ClientRepository,
    status: ClientStatus?,
    processingSession: String?,
    search: SearchActions,
    sessionActive: Boolean,
    requestMic: () -> Unit,
    onClose: () -> Unit,
    onRecord: (BoundsRef) -> Unit,
    onDeleteClient: () -> Unit,
    onDeleteSession: (SessionItem) -> Unit,
    titleRef: BoundsRef? = null,
    titleLent: () -> Boolean = { false },
) {
    val bar = rememberChoiceState()
    val haptics = rememberHaptics()
    val list = rememberLazyListState()
    val layer = rememberSheetState()
    var open by remember { mutableStateOf<Layer?>(null) }
    var confirmClient by remember { mutableStateOf(false) }
    var confirmSession by remember { mutableStateOf<SessionItem?>(null) }
    val recordRef = remember { BoundsRef() }
    val density = LocalDensity.current
    val openLayer = { l: Layer, _: BoundsRef ->
        open = l
        layer.open()
    }
    val pageFrame = LocalPageFrame.current
    if (titleRef != null) {
        val top by remember { derivedStateOf { list.firstVisibleItemIndex == 0 } }
        SideEffect { titleRef.present = top }
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().recede(listOf(layer), LocalReducedMotion.current).statusBarsPadding().navigationBarsPadding().imePadding()
                .axisDrag(sheetDrag(sheet, bar, onBack = onClose, onForward = { onRecord(recordRef) })),
        ) {
            LazyColumn(Modifier.weight(1f), state = list, contentPadding = PaddingValues(start = 26.dp, end = 26.dp, top = 24.dp, bottom = hintsClearance())) {
                item {
                    Label("Client · ${patient.sessionCount} ${if (patient.sessionCount == 1) "session" else "sessions"}")
                    Spacer(Modifier.height(14.dp))
                    // The name is the one thing that does not fade: it travels here from the reel (drawn by TravellingName meanwhile).
                    NameTitle(
                        patient.name,
                        Modifier.let { m -> titleRef?.let { m.trackBounds(it, pageFrame) } ?: m }.graphicsLayer { alpha = if (titleLent()) 0f else 1f },
                    )
                    if (status != null) {
                        Spacer(Modifier.height(6.dp))
                        StatusLine(status, Modifier)
                    }
                    Spacer(Modifier.height(28.dp))
                    SearchBox(search, requestMic)
                    Spacer(Modifier.height(8.dp))
                }
                val hits = search.state.hits
                if (hits != null) {
                    if (hits.isEmpty()) item { Label("Nothing found", Modifier.padding(vertical = 18.dp)) }
                    items(hits, key = { "${it.sessionId}:${it.segFrom}:${it.title}" }) { hit ->
                        val ref = remember { BoundsRef() }
                        HitRow(hit, ref) { openLayer(Layer.Transcript(hit.title ?: "From the transcript", hit.sessionId, hit.date, hit.segFrom, hit.segTo, hit.highlight), ref) }
                    }
                } else {
                    if (patient.facts.isNotEmpty()) item { Section("What you know") }
                    items(patient.facts, key = { "f${it.id}" }) { f ->
                        val ref = remember { BoundsRef() }
                        FactRow(f, ref) { openLayer(Layer.Fact(f), ref) }
                    }
                    item { Section("Sessions") }
                    if (patient.sessions.isEmpty()) item {
                        Text(if (processingSession != null) "The first session is being processed." else "No sessions yet. Record one and it appears here, with what was said.", style = Type.BodySmall, color = Palette.InkSoft, modifier = Modifier.padding(vertical = 12.dp))
                    }
                    items(patient.sessions, key = { "s${it.id}" }) { s ->
                        val ref = remember { BoundsRef() }
                        SessionRow(s, processing = s.id == processingSession, ref = ref, onOpen = { openLayer(Layer.Transcript("Session", s.id, s.date), ref) }, onDelete = { haptics.arm(); confirmSession = s })
                    }
                    item {
                        Spacer(Modifier.height(30.dp))
                        TextAction("Delete client…", { confirmClient = true }, color = Palette.Rec)
                    }
                }
            }
            SwipeHints(
                bar,
                back = Way("go back") { onClose() },
                forward = Way(if (sessionActive) "open the session" else "record") { onRecord(recordRef) },
                forwardRef = recordRef,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }

        PageShift(layer, onBack = { layer.close() }) {
            when (val l = open) {
                is Layer.Fact -> FactLayer(l.fact, repo, layer)
                is Layer.Transcript -> TranscriptLayer(l, repo, layer)
                null -> {}
            }
        }
    }

    if (confirmClient) ConfirmDialog(
        "Delete ${patient.name}?", "Their profile, every session, transcript and fact is removed from this phone. This cannot be undone.", "Delete",
        onConfirm = { confirmClient = false; onDeleteClient() }, onDismiss = { confirmClient = false },
    )
    confirmSession?.let { s ->
        ConfirmDialog(
            "Delete the session of ${s.date}?",
            "Its recording and transcript are removed. Facts first heard in it move to the next session where they came up; facts heard only there are removed.",
            "Delete",
            onConfirm = { confirmSession = null; onDeleteSession(s) }, onDismiss = { confirmSession = null },
        )
    }
}

/**
 * Drags on a sheet. Only sideways ones: up and down always belong to the page (it scrolls), never to the sheet. Swiping right goes back,
 * the same as the system back gesture: the page goes back the way it came as the finger moves, and letting go armed finishes the way at
 * the finger's speed (unarmed, it springs back). Swiping left goes in, to [onForward], when the screen has somewhere to go and [canForward] allows it.
 */
@Composable
internal fun sheetDrag(
    sheet: SheetState,
    bar: com.therapylocal.app.ui.design.ChoiceState,
    onBack: () -> Unit,
    onForward: (() -> Unit)? = null,
    canForward: () -> Boolean = { true },
): com.therapylocal.app.ui.motion.AxisDragHandler {
    val back by rememberUpdatedState(onBack)
    val forward by rememberUpdatedState(onForward)
    val canGo by rememberUpdatedState(canForward)
    return remember(sheet, bar) {
        axisDragHandler(
            accepts = { a, _, _ -> a == Axis.Horizontal },
            onDrag = { _, total, _ ->
                bar.drag(total) { s -> s == Side.Left || (forward != null && canGo()) }
                // Going back is scrubbed under the finger, the content moving with it, the same as the system back gesture.
                sheet.follow(1f - total.coerceAtLeast(0f) / sheet.shiftPx)
            },
            onEnd = { _, _, _ ->
                when (bar.release()) {
                    Side.Left -> back()
                    Side.Right -> { sheet.settleOpen(); forward?.invoke() }
                    null -> sheet.settleOpen()
                }
            },
        )
    }
}

/** A client's name as the title of their page. Also drawn on its own while it travels between the reel and the page. */
@Composable
internal fun NameTitle(name: String, modifier: Modifier) {
    val (first, rest) = splitName(name)
    Column(modifier) {
        Text(first, style = Type.Hero, color = Palette.Ink)
        if (rest != null) Text(rest, style = Type.Hero.copy(fontStyle = FontStyle.Italic), color = Palette.Ink)
    }
}

@Composable
private fun Section(title: String) {
    Column(Modifier.padding(top = 30.dp, bottom = 6.dp)) {
        Label(title)
        Spacer(Modifier.height(10.dp))
        Hairline()
    }
}

@Composable
private fun FactRow(f: FactItem, ref: BoundsRef, onOpen: () -> Unit) {
    Column(Modifier.fillMaxWidth().trackBounds(ref).pressable(onOpen, label = "Open ${f.heading}", depth = 0.015f).padding(vertical = 16.dp)) {
        Text(f.heading, style = Type.Title.copy(fontSize = 22.sp, lineHeight = 27.sp), color = Palette.Ink)
        Spacer(Modifier.height(4.dp))
        Text(f.content, style = Type.BodySmall, color = Palette.InkSoft, maxLines = 3)
        if (f.mentionCount > 0) {
            Spacer(Modifier.height(8.dp))
            Label("Came up ${f.mentionCount + 1} times", color = Palette.InkFaint)
        }
    }
    Hairline()
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionRow(s: SessionItem, processing: Boolean, ref: BoundsRef, onOpen: () -> Unit, onDelete: () -> Unit) {
    val haptics = rememberHaptics()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press = remember { Animatable(0f) }
    LaunchedEffect(pressed) { press.animateTo(if (pressed) 1f else 0f, Motion.Pull) }
    Row(
        Modifier.fillMaxWidth().trackBounds(ref)
            .graphicsLayer { val k = 1f - 0.015f * press.value; scaleX = k; scaleY = k }
            // Tap opens the transcript, long press offers to delete; TalkBack gets both as actions.
            .combinedClickable(
                interactionSource = interaction, indication = null,
                onClickLabel = "Open the transcript", onLongClickLabel = "Delete the session",
                onLongClick = onDelete, onClick = { haptics.press(); onOpen() },
            )
            .semantics { contentDescription = "Session of ${s.date}" }
            .padding(vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(s.date, style = Type.Title.copy(fontSize = 22.sp), color = Palette.Ink, modifier = Modifier.weight(1f))
        if (processing) { PulseDot(Palette.Ink, size = 6); Spacer(Modifier.width(8.dp)); Label("Processing") }
        else Label(if (s.duration == "-") "Not written down yet" else s.duration)
    }
    Hairline()
}

@Composable
private fun HitRow(hit: SearchHit, ref: BoundsRef, onOpen: () -> Unit) {
    Column(Modifier.fillMaxWidth().trackBounds(ref).pressable(onOpen, depth = 0.015f).padding(vertical = 16.dp)) {
        Row {
            Label(hit.title ?: "From the transcript", color = if (hit.title != null) Palette.Ink else Palette.Rev, modifier = Modifier.weight(1f))
            Label(hit.date)
        }
        Spacer(Modifier.height(6.dp))
        Text(hit.snippet, style = Type.BodySmall, color = Palette.InkSoft)
    }
    Hairline()
}

/** Type and press Search, or hold the microphone and say it: both search what was said in this client's sessions. */
@Composable
private fun SearchBox(s: SearchActions, requestMic: () -> Unit) {
    val ctx = LocalContext.current
    val haptics = rememberHaptics()
    var query by rememberSaveable { mutableStateOf("") }
    val hold = remember { Animatable(0f) }
    LaunchedEffect(s.state.listening) { hold.animateTo(if (s.state.listening) 1f else 0f, Motion.Pull) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Search, contentDescription = null, tint = Palette.InkSoft, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Box(Modifier.weight(1f).padding(vertical = 12.dp)) {
                if (query.isEmpty()) Text(if (s.state.listening) "Listening…" else "Search what was said", style = Type.Body, color = Palette.InkFaint)
                BasicTextField(
                    query, { query = it }, singleLine = true, textStyle = Type.Body.copy(color = Palette.Ink), cursorBrush = SolidColor(Palette.Ink),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { s.text(query) }),
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Search what was said" },
                )
            }
            if (query.isNotEmpty() || s.state.hits != null) {
                Box(Modifier.size(44.dp).clip(CircleShape).pressable({ query = ""; s.clear() }, label = "Clear search"), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Close, contentDescription = "Clear search", tint = Palette.InkSoft)
                }
            }
            // Hold to speak a question; letting go searches.
            val disabled = s.voiceDisabled != null
            Box(
                Modifier.size(48.dp)
                    .graphicsLayer { val k = 1f + 0.18f * hold.value; scaleX = k; scaleY = k; alpha = if (disabled) 0.4f else 1f }
                    .clip(CircleShape).background(if (s.state.listening) Palette.Rec else Palette.Tonal)
                    .semantics { contentDescription = "Hold to search by voice" }
                    .pointerInput(disabled) {
                        detectTapGestures(onPress = {
                            if (disabled) return@detectTapGestures
                            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { requestMic(); return@detectTapGestures }
                            haptics.press()
                            val started = s.voiceStart()
                            tryAwaitRelease()
                            if (started) { haptics.confirm(); s.voiceStop() }
                        })
                    },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Mic, contentDescription = null, tint = if (s.state.listening) Palette.OnColor else Palette.Ink) }
        }
        Hairline()
        val note = when {
            s.state.listening -> "Listening · let go to search"
            s.state.searching -> "Searching…"
            s.voiceDisabled != null && s.state.hits == null -> s.voiceDisabled
            else -> s.state.info
        }
        if (note != null) Label(note, Modifier.padding(top = 10.dp), color = Palette.InkFaint)
    }
}

@Composable
private fun LayerFrame(sheet: SheetState, kicker: String, title: String, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    val bar = rememberChoiceState()
    val haptics = rememberHaptics()
    val list = rememberLazyListState()
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().axisDrag(sheetDrag(sheet, bar, onBack = { sheet.close() }))) {
        LazyColumn(Modifier.weight(1f), state = list, contentPadding = PaddingValues(start = 26.dp, end = 26.dp, top = 24.dp, bottom = hintsClearance())) {
            item {
                Label(kicker)
                Spacer(Modifier.height(14.dp))
                Text(title, style = Type.Statement, color = Palette.Ink)
                Spacer(Modifier.height(18.dp))
            }
            content()
        }
        SwipeHints(bar, back = Way("go back") { haptics.confirm(); sheet.close() }, forward = null, modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp))
    }
}

@Composable
private fun FactLayer(f: FactItem, repo: ClientRepository, sheet: SheetState) {
    val occ by produceState<List<FactOccurrence>?>(null, f.id) { value = withContext(Dispatchers.IO) { runCatching { repo.occurrences(f.id) }.getOrDefault(emptyList()) } }
    LayerFrame(sheet, "What you know", f.heading) {
        item { Text(f.content, style = Type.Body, color = Palette.Ink); Spacer(Modifier.height(10.dp)) }
        val list = occ
        if (list == null) item { Label("Finding where it was said…", Modifier.padding(vertical = 16.dp)) }
        else if (list.isEmpty()) item { Label("The passage it came from is not stored", Modifier.padding(vertical = 16.dp)) }
        else list.forEach { o ->
            item { Section("${o.date} · ${if (o.first) "First heard" else "Came up again"}") }
            items(o.lines, key = { "${o.sessionId}:${it.idx}" }) { l -> Line(l, l.idx == o.segIdx) }
        }
    }
}

@Composable
private fun TranscriptLayer(t: Layer.Transcript, repo: ClientRepository, sheet: SheetState) {
    val lines by produceState<List<TranscriptLine>?>(null, t) {
        value = withContext(Dispatchers.IO) {
            runCatching { if (t.from != null && t.to != null) repo.transcript(t.sessionId, t.from, t.to) else repo.transcript(t.sessionId) }.getOrDefault(emptyList())
        }
    }
    LayerFrame(sheet, t.date, t.title) {
        val l = lines
        when {
            l == null -> item { Label("Opening…") }
            l.isEmpty() -> item { Text("This session has not been written down yet. It appears here once it is processed.", style = Type.BodySmall, color = Palette.InkSoft) }
            else -> items(l, key = { it.idx }) { line -> Line(line, line.idx == t.highlight) }
        }
    }
}

@Composable
private fun Line(l: TranscriptLine, highlight: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
            .background(if (highlight) Palette.Tonal else androidx.compose.ui.graphics.Color.Transparent).padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        Label(l.time, Modifier.width(54.dp).padding(top = 3.dp), color = Palette.InkFaint)
        Text(l.text, style = Type.BodySmall, color = if (highlight) Palette.Ink else Palette.InkSoft)
    }
}
