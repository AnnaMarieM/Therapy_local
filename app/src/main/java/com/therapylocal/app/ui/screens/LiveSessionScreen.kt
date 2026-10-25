package com.therapylocal.app.ui.screens

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.therapylocal.app.BuildConfig
import com.therapylocal.app.ui.components.PillBadge
import com.therapylocal.app.memory.Cue
import com.therapylocal.app.ui.logic.SessionLogic
import com.therapylocal.app.ui.models.Patient
import com.therapylocal.app.ui.models.SurfacedQuote
import com.therapylocal.app.ui.theme.BorderSubtle
import com.therapylocal.app.ui.theme.CharcoalText
import com.therapylocal.app.ui.theme.DangerBg
import com.therapylocal.app.ui.theme.DangerRed
import com.therapylocal.app.ui.theme.MutedText
import com.therapylocal.app.ui.theme.SageDark
import com.therapylocal.app.ui.theme.SageGreen
import com.therapylocal.app.ui.theme.SageLight
import com.therapylocal.app.ui.theme.SagePill
import com.therapylocal.app.ui.theme.SurfaceCard
import com.therapylocal.app.ui.theme.WarmBg

@Composable
fun LiveSessionScreen(
    patient: Patient,
    sessionSecs: Long,
    surfacedQuotes: List<SurfacedQuote>,
    transcript: List<String>,
    statusLine: String?,
    cues: List<Cue>,
    cuesError: String?,
    loadSource: suspend (Cue) -> String?,
    onEndSession: () -> Unit,
    onSwitchPatient: () -> Unit,
    onTriggerTopic: (String, String, String, String) -> Unit,
    modifier: Modifier = Modifier,
    /** True when the recording is kept for later transcription (nothing to show live yet). */
    deferredNote: Boolean = false,
    isPaused: Boolean = false,
    onTogglePause: () -> Unit = {}
) {
    val timeFormatted = SessionLogic.formatHourMinSec(sessionSecs)

    val simulatedTriggers = listOf(
        "Father" to "Strained relationship; client stated 'he never acknowledges the loss.'",
        "Grief" to "Mother passed Nov 2025; grief wave triggers around holidays.",
        "Sleep" to "Wakes at 3:15 AM; looping thoughts about performance review.",
        "Guilt" to "Client reported chronic difficulty saying no without apology."
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(WarmBg)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        // Top Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "${patient.name} · Session",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Serif,
                    color = CharcoalText
                )
                Text(
                    text = "Ambient clinical memory",
                    fontSize = 12.sp,
                    color = MutedText
                )
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(100.dp))
                    .border(1.dp, BorderSubtle, RoundedCornerShape(100.dp))
                    .background(SurfaceCard)
                    .clickable { onSwitchPatient() }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "Switch patient",
                    fontSize = 11.sp,
                    color = SageDark,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Status badges
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            PillBadge(
                text = "Consent confirmed",
                icon = Icons.Default.Check
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Audio Waveform & Timer Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceCard),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 20.dp, horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Waveform bars
                WaveformDisplay(isPaused = isPaused)

                Spacer(modifier = Modifier.height(16.dp))

                // Timer
                Text(
                    text = timeFormatted,
                    fontSize = 38.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Serif,
                    color = CharcoalText
                )

                Text(
                    text = statusLine ?: "Listening",
                    fontSize = 12.sp,
                    color = MutedText,
                    modifier = Modifier.padding(top = 4.dp)
                )

                Spacer(modifier = Modifier.height(18.dp))

                // Actions: Pause/Resume and End
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onTogglePause,
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = SageDark
                        ),
                        border = androidx.compose.foundation.BorderStroke(1.dp, BorderSubtle)
                    ) {
                        Icon(
                            imageVector = if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                            contentDescription = if (isPaused) "Play" else "Pause",
                            modifier = Modifier.size(16.dp),
                            tint = SageDark
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isPaused) {
                                if (sessionSecs == 0L) "Play" else "Resume"
                            } else "Pause",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = CharcoalText
                        )
                    }

                    Button(
                        onClick = onEndSession,
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = DangerRed,
                            contentColor = Color.White
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Stop,
                            contentDescription = "End Session",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "End Session",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }

        if (deferredNote) {
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "Recording. This session will be transcribed and added to the client's memory automatically once MindVault finishes getting ready.",
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = MutedText,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(SurfaceCard.copy(alpha = 0.6f))
                    .padding(12.dp)
            )
        }

        // Transcript of the chunks processed so far (newest first)
        if (transcript.isNotEmpty() && !deferredNote) {
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "LIVE TRANSCRIPT",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = MutedText
            )
            Spacer(modifier = Modifier.height(4.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 110.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(SurfaceCard.copy(alpha = 0.6f))
                    .verticalScroll(rememberScrollState())
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                transcript.asReversed().forEach { Text(text = it, fontSize = 12.sp, color = CharcoalText, lineHeight = 16.sp) }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Surfaced Notes Section
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "NOTES FROM PAST SESSIONS",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = MutedText
            )
            Text(
                text = "Surfaced live",
                fontSize = 11.sp,
                color = SageGreen,
                fontWeight = FontWeight.Medium
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            cuesError?.let { err ->
                item { Text(text = err, fontSize = 11.sp, color = DangerRed) }
            }
            items(cues, key = { it.factId + "@" + it.session }) { cue -> CueRow(cue, loadSource) }

            if (surfacedQuotes.isEmpty() && cues.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .border(1.dp, BorderSubtle, RoundedCornerShape(16.dp))
                            .background(SurfaceCard.copy(alpha = 0.6f))
                            .padding(20.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(SageLight),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AutoAwesome,
                                    contentDescription = null,
                                    tint = SageGreen,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Listening from 00:00:00",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = CharcoalText
                            )
                            Text(
                                text = "As ${patient.name} speaks, related facts and verbatim quotes from earlier sessions will surface automatically here in real time.",
                                fontSize = 11.sp,
                                color = MutedText,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }
            } else {
                items(surfacedQuotes) { quote ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                PillBadge(
                                    text = "At ${quote.timestamp} · Mentioned \"${quote.topicTrigger}\"",
                                    color = SageDark,
                                    background = SageLight
                                )
                                Text(
                                    text = quote.sessionDate,
                                    fontSize = 11.sp,
                                    color = MutedText
                                )
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            Text(
                                text = "\"${quote.quote}\"",
                                fontSize = 13.sp,
                                fontStyle = FontStyle.Italic,
                                color = CharcoalText,
                                lineHeight = 18.sp
                            )
                        }
                    }
                }
            }

            if (BuildConfig.DEBUG) item {
                // Topic Simulation Chips (debug only: simulated recall, not real memory)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(SurfaceCard.copy(alpha = 0.5f))
                        .border(1.dp, BorderSubtle, RoundedCornerShape(14.dp))
                        .padding(12.dp)
                ) {
                    Text(
                        text = "SIMULATE TOPIC RECALL:",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = MutedText
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(simulatedTriggers) { (topic, quote) ->
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(100.dp))
                                    .border(1.dp, SageGreen.copy(alpha = 0.4f), RoundedCornerShape(100.dp))
                                    .background(SurfaceCard)
                                    .clickable {
                                        onTriggerTopic(
                                            timeFormatted,
                                            topic,
                                            "Oct 22, 2026",
                                            quote
                                        )
                                    }
                                    .padding(horizontal = 10.dp, vertical = 5.dp)
                            ) {
                                Text(
                                    text = "+ \"$topic\"",
                                    fontSize = 11.sp,
                                    color = SageDark,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun WaveformDisplay(isPaused: Boolean) {
    val heights = listOf(8, 14, 22, 12, 30, 18, 26, 10, 20, 34, 16, 24, 12, 28, 18, 8, 22, 30, 14, 20, 10, 26, 16, 12)
    val transition = rememberInfiniteTransition(label = "waveformTransition")

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.height(40.dp)
    ) {
        heights.forEachIndexed { index, baseHeight ->
            val animatedFactor by transition.animateFloat(
                initialValue = 0.4f,
                targetValue = 1.0f,
                animationSpec = infiniteRepeatable(
                    animation = tween(
                        durationMillis = 600 + (index % 5) * 120,
                        easing = FastOutSlowInEasing
                    ),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "bar_$index"
            )

            val currentHeight = if (isPaused) 4.dp else (baseHeight * animatedFactor).coerceAtLeast(6f).dp
            val color = if (isPaused) BorderSubtle else SageGreen

            Box(
                modifier = Modifier
                    .width(3.5.dp)
                    .height(currentHeight)
                    .clip(CircleShape)
                    .background(color)
            )
        }
    }
}

/** One live cue: category and fact text, the match score as a thin bar; tap to show the transcript passage it came from. */
@Composable
private fun CueRow(cue: Cue, loadSource: suspend (Cue) -> String?) {
    var expanded by remember { mutableStateOf(false) }
    var source by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(expanded) {
        if (expanded && source == null) source = loadSource(cue) ?: "Source passage not stored."
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceCard),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillBadge(text = cue.category, color = SageDark, background = SageLight)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(3.dp)
                        .clip(CircleShape)
                        .background(BorderSubtle)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(cue.score.coerceIn(0f, 1f))
                            .height(3.dp)
                            .background(SageGreen.copy(alpha = 0.6f))
                    )
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(text = cue.text, fontSize = 13.sp, color = CharcoalText, lineHeight = 18.sp)
            if (expanded) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = source ?: "...",
                    fontSize = 12.sp,
                    fontStyle = FontStyle.Italic,
                    color = MutedText,
                    lineHeight = 16.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(SageLight.copy(alpha = 0.5f))
                        .padding(8.dp)
                )
            }
        }
    }
}
