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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.therapylocal.app.ui.components.PillBadge
import com.therapylocal.app.memory.Cue
import com.therapylocal.app.ui.logic.SessionLogic
import com.therapylocal.app.ui.models.Patient
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
import java.util.Locale

@Composable
fun LiveSessionScreen(
    patient: Patient,
    sessionSecs: Long,
    statusLine: String?,
    cues: List<Cue>,
    cuesError: String?,
    loadSource: suspend (Cue) -> String?,
    onEndSession: () -> Unit,
    onSwitchPatient: () -> Unit,
    modifier: Modifier = Modifier,
    isPaused: Boolean = false,
    onTogglePause: () -> Unit = {}
) {
    val timeFormatted = SessionLogic.formatHourMinSec(sessionSecs)

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
                    if (isPaused) {
                        // When paused/ready, Play is the prominent primary green button
                        Button(
                            onClick = onTogglePause,
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = SageGreen,
                                contentColor = Color.White
                            ),
                            elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = if (sessionSecs == 0L) "Play" else "Resume",
                                modifier = Modifier.size(18.dp),
                                tint = Color.White
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (sessionSecs == 0L) "Play" else "Resume",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White
                            )
                        }

                        OutlinedButton(
                            onClick = onEndSession,
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = DangerRed
                            ),
                            border = androidx.compose.foundation.BorderStroke(1.dp, DangerRed.copy(alpha = 0.35f))
                        ) {
                            Icon(
                                imageVector = Icons.Default.Stop,
                                contentDescription = "End Session",
                                modifier = Modifier.size(16.dp),
                                tint = DangerRed
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "End Session",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = DangerRed
                            )
                        }
                    } else {
                        // When actively recording, Pause is secondary and End Session is prominent
                        OutlinedButton(
                            onClick = onTogglePause,
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = SageDark
                            ),
                            border = androidx.compose.foundation.BorderStroke(1.dp, BorderSubtle)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Pause,
                                contentDescription = "Pause",
                                modifier = Modifier.size(18.dp),
                                tint = SageDark
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Pause",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = CharcoalText
                            )
                        }

                        Button(
                            onClick = onEndSession,
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = DangerRed,
                                contentColor = Color.White
                            ),
                            elevation = ButtonDefaults.buttonElevation(defaultElevation = 1.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Stop,
                                contentDescription = "End Session",
                                modifier = Modifier.size(16.dp),
                                tint = Color.White
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "End Session",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Surfaced facts section
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "FROM PAST SESSIONS",
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

            if (cues.isEmpty()) {
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
                                text = "As ${patient.name} speaks, related facts from earlier sessions will surface here in real time.",
                                fontSize = 11.sp,
                                color = MutedText,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                modifier = Modifier.padding(top = 4.dp)
                            )
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

/** One live cue: the fact heading and its match score; tap to show the fact and the transcript passage it came from. */
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
                Text(
                    text = cue.heading,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = CharcoalText,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = String.format(Locale.US, "%.2f", cue.score),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = SageDark
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
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
            if (expanded) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = cue.text, fontSize = 13.sp, color = CharcoalText, lineHeight = 18.sp)
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
