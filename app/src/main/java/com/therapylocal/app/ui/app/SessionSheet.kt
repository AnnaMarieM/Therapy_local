package com.therapylocal.app.ui.app

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.therapylocal.app.memory.Cue
import com.therapylocal.app.session.SessionPhase
import com.therapylocal.app.session.SessionState
import com.therapylocal.app.ui.data.ClientRepository
import com.therapylocal.app.ui.design.Choice
import androidx.compose.foundation.layout.heightIn
import com.therapylocal.app.ui.design.ChoiceBar
import com.therapylocal.app.ui.design.SwipeHints
import com.therapylocal.app.ui.design.Way
import com.therapylocal.app.ui.design.backChoice
import com.therapylocal.app.ui.design.HoldToFinish
import com.therapylocal.app.ui.design.LocalReducedMotion
import com.therapylocal.app.ui.design.Motion
import com.therapylocal.app.ui.design.Palette
import com.therapylocal.app.ui.design.Type
import com.therapylocal.app.ui.design.rememberChoiceState
import com.therapylocal.app.ui.design.rememberHaptics
import com.therapylocal.app.ui.logic.CueView
import com.therapylocal.app.ui.logic.SessionLogic
import com.therapylocal.app.ui.logic.sessionDate
import com.therapylocal.app.ui.motion.SheetState
import com.therapylocal.app.ui.motion.axisDrag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The session while it records. One thing on screen: what from earlier sessions may matter now, large. The rest (time, level, controls)
 * is quiet. Swipe right, go back or tap Back to step out; the session keeps recording, docked on home. Hold the square to finish.
 */
@Composable
fun SessionSheet(
    sheet: SheetState,
    session: SessionState,
    clientName: String,
    elapsed: () -> Long,
    level: StateFlow<Float>,
    cues: List<Cue>,
    cuesError: String?,
    repo: ClientRepository,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onMinimise: () -> Unit,
    onFinish: () -> Unit,
    onHint: (String) -> Unit,
) {
    val bar = rememberChoiceState()
    val haptics = rememberHaptics()
    val paused = session.phase == SessionPhase.Paused
    var view by remember { mutableStateOf(CueView.EMPTY) }
    LaunchedEffect(cues) {
        val next = CueView.next(view, cues, System.currentTimeMillis())
        if (next.main?.factId != view.main?.factId && next.main != null && view.main != null) haptics.tick()
        view = next
    }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
            .axisDrag(sheetDrag(sheet, bar, onBack = onMinimise)),
    ) {
        // ---- top: dot, time, who ----
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 26.dp, end = 26.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            PulseDot(Palette.Rec, still = paused)
            Spacer(Modifier.width(10.dp))
            Clock(elapsed, paused)
            Spacer(Modifier.width(10.dp))
            Label(if (paused) "Paused · $clientName" else clientName, Modifier.weight(1f))
        }

        // ---- the cue ----
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 26.dp)) {
            val main = view.main
            when {
                session.error != null -> Quiet(session.error, color = Palette.Rec)
                paused -> Quiet("Paused. Nothing is being recorded.")
                main == null -> Quiet(
                    when {
                        cuesError != null -> "Cues paused: $cuesError"
                        !session.cuesOn -> "Recording. Cues from earlier sessions start once setup has finished."
                        else -> "Listening"
                    },
                    pulse = session.cuesOn && cuesError == null,
                )
                else -> key(main.factId) { CueStage(main, view.also, repo, onPick = { c -> view = CueView(c, System.currentTimeMillis(), cues.filter { it.factId != c.factId }.take(2)) }) }
            }
        }

        // ---- level, controls ----
        LevelMeter(level, paused, Modifier.fillMaxWidth().height(28.dp).padding(horizontal = 26.dp))
        Spacer(Modifier.height(14.dp))
        // Finishing and pausing are always there. Back leaves the session running (docked on home); this is the deepest screen, so
        // there is no way further in.
        SwipeHints(
            bar,
            back = Way("go back · it keeps recording") { onMinimise() },
            forward = null,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            center = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextAction(if (paused) "Resume" else "Pause", { if (paused) onResume() else onPause() }, color = if (paused) Palette.Rec else Palette.InkSoft)
                    Spacer(Modifier.width(22.dp))
                    HoldToFinish(onComplete = onFinish, onTooShort = { onHint("Hold the square to finish") })
                    Spacer(Modifier.width(22.dp))
                    Label("Hold ■ to finish", color = Palette.InkFaint)
                }
            },
        )
    }
}

/** Ticks on the frame clock so the seconds turn exactly when they should, not up to a second late. */
@Composable
private fun Clock(elapsed: () -> Long, paused: Boolean) {
    var ms by remember { mutableLongStateOf(elapsed()) }
    LaunchedEffect(paused) { while (true) { withFrameNanos { }; ms = elapsed(); kotlinx.coroutines.delay(200) } }
    Label(SessionLogic.formatMinSec(ms / 1000), color = Palette.Ink)
}

@Composable
private fun Quiet(text: String, color: androidx.compose.ui.graphics.Color = Palette.InkSoft, pulse: Boolean = false) {
    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        if (pulse) { PulseDot(Palette.Ink, size = 6); Spacer(Modifier.width(12.dp)) }
        Text(text, style = if (pulse) Type.Label.copy(fontSize = 13.sp) else Type.Title, color = color, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    }
}

/**
 * The cue, entering word by word: each word rises a little and comes into focus a beat after the one before, so the eye reads it as it
 * arrives instead of all at once. Below it, what the fact says; on request, the passage it came from. The next ones wait underneath.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CueStage(cue: Cue, also: List<Cue>, repo: ClientRepository, onPick: (Cue) -> Unit) {
    val reduced = LocalReducedMotion.current
    var showSource by remember { mutableStateOf(false) }
    val words = remember(cue.heading) { cue.heading.split(Regex("\\s+")).filter { it.isNotBlank() } }
    val enter = remember(cue.factId) { words.map { Animatable(if (reduced) 1f else 0f) } }
    val rest = remember(cue.factId) { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(cue.factId) {
        enter.forEachIndexed { i, a -> launch { kotlinx.coroutines.delay(i * 48L); a.animateTo(1f, Motion.Fade) } }
        kotlinx.coroutines.delay(words.size * 48L + 250)
        rest.animateTo(1f, Motion.Fade)
    }
    val source by produceState<String?>(null, showSource, cue.factId) {
        if (showSource) value = withContext(Dispatchers.IO) { runCatching { repo.sourceChunk(cue.session, cue.chunk) }.getOrNull() } ?: ""
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.Center) {
        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.graphicsLayer { alpha = rest.value }) {
            PulseDot(Palette.Rev, size = 8, still = true)
            Spacer(Modifier.width(10.dp))
            Label(sessionDate(cue.session)?.let { "From $it" } ?: "From an earlier session")
        }
        Spacer(Modifier.height(18.dp))
        FlowRow(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
            words.forEachIndexed { i, w ->
                Text(
                    "$w ",
                    style = Type.Statement,
                    color = Palette.Ink,
                    modifier = Modifier.graphicsLayer {
                        val p = enter[i].value
                        alpha = p
                        translationY = (1f - p) * 14.dp.toPx()
                    },
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Column(Modifier.graphicsLayer { alpha = rest.value; translationY = (1f - rest.value) * 8.dp.toPx() }) {
            Text(cue.text, style = Type.Body, color = Palette.InkSoft)
            if (!showSource) TextAction("What was said", { showSource = true }, color = Palette.Ink)
            else {
                Spacer(Modifier.height(12.dp))
                Text(
                    source?.let { if (it.isEmpty()) "The passage is not stored." else "“$it”" } ?: "…",
                    style = Type.Lead.copy(fontStyle = FontStyle.Italic), color = Palette.InkSoft,
                )
            }
            if (also.isNotEmpty()) {
                Spacer(Modifier.height(28.dp))
                Label("Also", color = Palette.InkFaint)
                also.forEach { c ->
                    Text(
                        c.heading, style = Type.Title.copy(fontSize = 20.sp, lineHeight = 25.sp), color = Palette.InkSoft,
                        modifier = Modifier.fillMaxWidth().pressable({ onPick(c) }, label = "Show ${c.heading}", depth = 0.015f).padding(vertical = 10.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * The real microphone level, as a strip of thin bars scrolling left: proof it is listening, without numbers. Drawn on the frame clock from
 * a fixed ring of samples, so it costs no recomposition.
 */
@Composable
private fun LevelMeter(level: StateFlow<Float>, paused: Boolean, modifier: Modifier) {
    val isPaused by androidx.compose.runtime.rememberUpdatedState(paused)
    val samples = remember { FloatArray(160) }
    var head by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    var frame by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        var acc = 0L
        var last = 0L
        var smooth = 0f
        while (true) {
            withFrameNanos { t ->
                if (last != 0L) acc += t - last
                last = t
                smooth += (level.value - smooth) * 0.35f
                if (acc >= 60_000_000L) { acc = 0; samples[head % samples.size] = if (isPaused) 0f else smooth; head++ }
                frame = t
            }
        }
    }
    Canvas(modifier) {
        frame // redraw every frame
        val step = 4.dp.toPx()
        val bw = 1.6.dp.toPx()
        val n = minOf(head, samples.size, (size.width / step).toInt())
        val mid = size.height / 2
        for (k in 0 until n) {
            val s = samples[(head - 1 - k).mod(samples.size)]
            val hgt = 2.dp.toPx() + s * (size.height - 2.dp.toPx())
            val x = size.width - (k + 1) * step
            val a = (0.12f + 0.4f * s) * (1f - k.toFloat() / n * 0.65f)
            drawRoundRect(Palette.Ink.copy(alpha = a), Offset(x, mid - hgt / 2), Size(bw, hgt), CornerRadius(bw / 2))
        }
    }
}
