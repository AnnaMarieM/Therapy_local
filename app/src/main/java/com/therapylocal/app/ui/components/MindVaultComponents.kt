package com.therapylocal.app.ui.components

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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.therapylocal.app.models.ModelSetup
import com.therapylocal.app.models.SetupProgress
import com.therapylocal.app.models.SetupState
import com.therapylocal.app.ui.models.Patient
import com.therapylocal.app.ui.theme.BorderSubtle
import com.therapylocal.app.ui.theme.CharcoalText
import com.therapylocal.app.ui.theme.MutedText
import com.therapylocal.app.ui.theme.SageDark
import com.therapylocal.app.ui.theme.SageGreen
import com.therapylocal.app.ui.theme.SageLight
import com.therapylocal.app.ui.theme.SagePill
import com.therapylocal.app.ui.theme.SurfaceCard

@Composable
fun InitialsAvatar(
    name: String,
    modifier: Modifier = Modifier,
    size: Int = 40
) {
    val initial = name.firstOrNull()?.uppercase() ?: "P"
    Box(
        modifier = modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(SageLight)
            .border(1.dp, SageGreen.copy(alpha = 0.25f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = initial,
            color = SageDark,
            fontWeight = FontWeight.SemiBold,
            fontSize = (size * 0.42).sp
        )
    }
}

@Composable
fun PillBadge(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    color: Color = SageDark,
    background: Color = SagePill
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(100.dp))
            .background(background)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(13.dp)
            )
        }
        Text(
            text = text,
            color = color,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
fun MinimizedSessionBar(
    patient: Patient,
    sessionSecs: Long,
    isPaused: Boolean,
    onTogglePause: () -> Unit,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier
) {
    val fmt = { n: Long -> "%02d".format(n) }
    val timeFormatted = "${fmt(sessionSecs / 3600)}:${fmt((sessionSecs / 60) % 60)}:${fmt(sessionSecs % 60)}"

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clickable { onExpand() },
        shape = RoundedCornerShape(18.dp),
        color = SageDark,
        shadowElevation = 8.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Audio indicator circle
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    if (isPaused) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.6f))
                        )
                    } else {
                        MiniAudioBars()
                    }
                }

                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = patient.name,
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (isPaused) " · Paused" else " · Running",
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 11.sp
                        )
                    }
                    Text(
                        text = "$timeFormatted · Recording",
                        color = Color.White.copy(alpha = 0.65f),
                        fontSize = 11.sp
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onTogglePause,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                        contentDescription = if (isPaused) "Resume" else "Pause",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                IconButton(
                    onClick = onExpand,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowUp,
                        contentDescription = "Expand session",
                        tint = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun MiniAudioBars() {
    val transition = rememberInfiniteTransition(label = "miniWave")
    val h1 by transition.animateFloat(
        initialValue = 4f,
        targetValue = 14f,
        animationSpec = infiniteRepeatable(
            animation = tween(400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "h1"
    )
    val h2 by transition.animateFloat(
        initialValue = 12f,
        targetValue = 6f,
        animationSpec = infiniteRepeatable(
            animation = tween(500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "h2"
    )
    val h3 by transition.animateFloat(
        initialValue = 6f,
        targetValue = 16f,
        animationSpec = infiniteRepeatable(
            animation = tween(450, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "h3"
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Box(Modifier.width(2.5.dp).height(h1.dp).clip(CircleShape).background(Color.White))
        Box(Modifier.width(2.5.dp).height(h2.dp).clip(CircleShape).background(Color.White))
        Box(Modifier.width(2.5.dp).height(h3.dp).clip(CircleShape).background(Color.White))
    }
}

@Composable
fun MindVaultBottomNav(
    selectedTab: String,
    onTabSelected: (String) -> Unit,
    isSessionActive: Boolean,
    modifier: Modifier = Modifier
) {
    NavigationBar(
        modifier = modifier,
        containerColor = SurfaceCard,
        tonalElevation = 2.dp
    ) {
        NavigationBarItem(
            selected = selectedTab == "patients",
            onClick = { onTabSelected("patients") },
            icon = { Icon(Icons.Default.People, contentDescription = "Patients") },
            label = { Text("Patients", fontSize = 11.sp) },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = SageDark,
                selectedTextColor = SageDark,
                indicatorColor = SageLight,
                unselectedIconColor = MutedText,
                unselectedTextColor = MutedText
            )
        )

        NavigationBarItem(
            selected = selectedTab == "session",
            onClick = { onTabSelected("session") },
            icon = {
                Box {
                    Icon(Icons.Default.Mic, contentDescription = "Session")
                    if (isSessionActive) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF22C55E))
                                .align(Alignment.TopEnd)
                        )
                    }
                }
            },
            label = { Text("Session", fontSize = 11.sp) },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = SageDark,
                selectedTextColor = SageDark,
                indicatorColor = SageLight,
                unselectedIconColor = MutedText,
                unselectedTextColor = MutedText
            )
        )

        NavigationBarItem(
            selected = selectedTab == "settings",
            onClick = { onTabSelected("settings") },
            icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
            label = { Text("Settings", fontSize = 11.sp) },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = SageDark,
                selectedTextColor = SageDark,
                indicatorColor = SageLight,
                unselectedIconColor = MutedText,
                unselectedTextColor = MutedText
            )
        )
    }
}

/** Calm first-run banner while MindVault gets ready; renders nothing once Ready. */
@Composable
fun SetupBanner(progress: SetupProgress, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    if (progress.state == SetupState.Ready) return
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, BorderSubtle, RoundedCornerShape(16.dp))
            .background(SurfaceCard)
            .padding(14.dp)
    ) {
        when (progress.state) {
            SetupState.Downloading -> {
                Text(text = "Getting MindVault ready", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = CharcoalText)
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { progress.fraction },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(100.dp)),
                    color = SageGreen,
                    trackColor = SageLight
                )
                Spacer(modifier = Modifier.height(6.dp))
                val eta = progress.etaSeconds?.let { " · ${ModelSetup.formatEta(it)} left" } ?: ""
                Text(
                    text = "${ModelSetup.formatBytes(progress.doneBytes)} of ${ModelSetup.formatBytes(progress.totalBytes)}$eta",
                    fontSize = 12.sp,
                    color = MutedText
                )
                progress.message?.let { Text(text = it, fontSize = 12.sp, color = MutedText, modifier = Modifier.padding(top = 2.dp)) }
                Text(
                    text = "You can use the app as usual. Sessions you record are processed automatically when this finishes.",
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    color = MutedText,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            SetupState.Failed -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "Setup paused", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = CharcoalText)
                        progress.message?.let { Text(text = it, fontSize = 12.sp, color = MutedText, modifier = Modifier.padding(top = 2.dp)) }
                    }
                    TextButton(onClick = onRetry) { Text("Try again", color = SageDark, fontSize = 13.sp, fontWeight = FontWeight.Medium) }
                }
            }
            else -> {
                Text(text = "Getting MindVault ready…", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = CharcoalText)
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(6.dp).clip(RoundedCornerShape(100.dp)),
                    color = SageGreen,
                    trackColor = SageLight
                )
            }
        }
    }
}
