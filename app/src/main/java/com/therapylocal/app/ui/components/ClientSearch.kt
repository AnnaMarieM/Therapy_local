package com.therapylocal.app.ui.components

import android.Manifest
import android.media.AudioManager
import android.media.ToneGenerator
import android.view.HapticFeedbackConstants
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import com.therapylocal.app.SearchState
import com.therapylocal.app.config.ChunkConfig
import com.therapylocal.app.ui.logic.SessionLogic
import com.therapylocal.app.ui.models.SearchHit
import com.therapylocal.app.ui.security.findFragmentActivity
import com.therapylocal.app.ui.theme.BorderSubtle
import com.therapylocal.app.ui.theme.CharcoalText
import com.therapylocal.app.ui.theme.DangerRed
import com.therapylocal.app.ui.theme.MutedText
import com.therapylocal.app.ui.theme.SageDark
import com.therapylocal.app.ui.theme.SageGreen
import com.therapylocal.app.ui.theme.SurfaceCard
import kotlinx.coroutines.delay

/**
 * Search one client's sessions: type, or hold the mic and speak (the clip itself is searched, it is never transcribed).
 * [voiceDisabled] is the reason voice search cannot run now, or null. Tapping a result opens the transcript there ([onOpen]).
 */
@Composable
fun ClientSearch(
    state: SearchState,
    voiceDisabled: String?,
    onSearchText: (String) -> Unit,
    onVoiceStart: () -> Boolean,
    onVoiceStop: () -> Unit,
    onClear: () -> Unit,
    onOpen: (SearchHit) -> Unit,
    hasMicPermission: () -> Boolean,
    requestMicPermission: () -> Unit
) {
    val activity = LocalContext.current.findFragmentActivity()
    var query by rememberSaveable { mutableStateOf("") }
    var micRefused by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search this client's sessions", fontSize = 13.sp) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = MutedText) },
                trailingIcon = {
                    if (query.isNotEmpty() || state.hits != null) IconButton(onClick = { query = ""; onClear() }) {
                        Icon(Icons.Default.Close, contentDescription = "Clear search", tint = MutedText)
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearchText(query) }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = SageGreen,
                    unfocusedBorderColor = BorderSubtle,
                    focusedContainerColor = SurfaceCard,
                    unfocusedContainerColor = SurfaceCard,
                    cursorColor = SageGreen
                ),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.weight(1f)
            )
            HoldToSearchButton(
                listening = state.listening,
                enabled = voiceDisabled == null,
                onPress = {
                    if (hasMicPermission()) {
                        micRefused = false
                        onVoiceStart()
                    } else {
                        // The app asks at startup, so without a rationale to show Android will not ask again: say where to allow it.
                        micRefused = activity != null && !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.RECORD_AUDIO)
                        requestMicPermission()
                        false
                    }
                },
                onRelease = onVoiceStop
            )
        }

        val note = when {
            micRefused -> "Voice search needs the microphone. Allow it for MindVault in the phone's settings."
            state.listening -> "Listening… release to search"
            voiceDisabled != null -> voiceDisabled
            else -> state.info
        }
        if (note != null) Text(text = note, fontSize = 11.sp, color = if (micRefused) DangerRed else MutedText)
        if (state.searching) LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = SageGreen)

        state.hits?.let { hits ->
            if (hits.isEmpty()) Text(text = "Nothing found.", fontSize = 12.sp, color = MutedText)
            hits.forEach { hit -> SearchHitRow(hit, onClick = { onOpen(hit) }) }
        }
    }
}

@Composable
private fun SearchHitRow(hit: SearchHit, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceCard),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    text = hit.title ?: "From the transcript",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (hit.title != null) CharcoalText else SageDark,
                    modifier = Modifier.weight(1f)
                )
                Text(text = hit.date, fontSize = 11.sp, color = MutedText)
            }
            Text(text = hit.snippet, fontSize = 12.sp, lineHeight = 17.sp, color = CharcoalText, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

/** Hold to record, release to search. Pulses red while listening; when recording stops (release or the length cap) it pops, ticks and beeps. */
@Composable
private fun HoldToSearchButton(listening: Boolean, enabled: Boolean, onPress: () -> Boolean, onRelease: () -> Unit) {
    val view = LocalView.current
    val pop = remember { Animatable(1f) }
    val tone = remember { runCatching { ToneGenerator(AudioManager.STREAM_SYSTEM, POP_VOLUME) }.getOrNull() }
    DisposableEffect(Unit) { onDispose { tone?.release() } }
    // pointerInput keeps its first lambdas; read the callbacks through these.
    val currentOnPress by rememberUpdatedState(onPress)
    val currentOnRelease by rememberUpdatedState(onRelease)

    var wasListening by remember { mutableStateOf(false) }
    LaunchedEffect(listening) {
        val stopped = wasListening && !listening
        wasListening = listening
        if (!stopped) return@LaunchedEffect
        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        tone?.startTone(ToneGenerator.TONE_PROP_BEEP, POP_MS)
        pop.snapTo(1.25f)
        pop.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium))
    }

    var seconds by remember { mutableIntStateOf(0) }
    LaunchedEffect(listening) {
        seconds = 0
        while (listening) {
            delay(1000)
            seconds++
        }
    }

    val ring = rememberInfiniteTransition(label = "listenRing")
    val ringScale by ring.animateFloat(1f, 1.7f, infiniteRepeatable(tween(900), RepeatMode.Restart), label = "ringScale")
    val ringAlpha by ring.animateFloat(0.45f, 0f, infiniteRepeatable(tween(900), RepeatMode.Restart), label = "ringAlpha")
    val tint by animateColorAsState(
        targetValue = when {
            !enabled -> BorderSubtle
            listening -> DangerRed
            else -> SageGreen
        },
        label = "micTint"
    )

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(56.dp)) {
            if (listening) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .graphicsLayer { scaleX = ringScale; scaleY = ringScale; alpha = ringAlpha }
                        .clip(CircleShape)
                        .background(DangerRed)
                )
            }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(44.dp)
                    .graphicsLayer { scaleX = pop.value; scaleY = pop.value }
                    .clip(CircleShape)
                    .background(tint)
                    .semantics { contentDescription = "Hold to search by voice" }
                    .pointerInput(enabled) {
                        if (!enabled) return@pointerInput
                        detectTapGestures(onPress = {
                            if (!currentOnPress()) return@detectTapGestures
                            tryAwaitRelease()
                            currentOnRelease()
                        })
                    }
            ) {
                Icon(Icons.Default.Mic, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
            }
        }
        if (listening) {
            Text(
                text = "${SessionLogic.formatMinSec(seconds.toLong())} / ${SessionLogic.formatMinSec(ChunkConfig.QUERY_MAX_SECONDS.toLong())}",
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = DangerRed
            )
        }
    }
}

private const val POP_VOLUME = 60
private const val POP_MS = 60
