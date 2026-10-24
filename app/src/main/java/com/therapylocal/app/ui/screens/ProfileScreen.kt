package com.therapylocal.app.ui.screens

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
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.therapylocal.app.ui.components.InitialsAvatar
import com.therapylocal.app.ui.components.PillBadge
import com.therapylocal.app.ui.models.Patient
import com.therapylocal.app.ui.theme.BorderSubtle
import com.therapylocal.app.ui.theme.CharcoalText
import com.therapylocal.app.ui.theme.MutedText
import com.therapylocal.app.ui.theme.SageDark
import com.therapylocal.app.ui.theme.SageGreen
import com.therapylocal.app.ui.theme.SageLight
import com.therapylocal.app.ui.theme.SagePill
import com.therapylocal.app.ui.theme.SurfaceCard
import com.therapylocal.app.ui.theme.WarmBg

@Composable
fun ProfileScreen(
    patient: Patient,
    isSessionActive: Boolean,
    onBack: () -> Unit,
    onDeletePatient: (Patient) -> Unit,
    onStartSession: (Patient) -> Unit,
    onReturnToActiveSession: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var confirmDelete by remember { mutableStateOf(false) }
    val tabs = listOf("Overview", "Sessions", "Facts")

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(WarmBg)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        // Back navigation & Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(SurfaceCard)
                    .border(1.dp, BorderSubtle, CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.ArrowBack,
                    contentDescription = "Back",
                    tint = SageDark,
                    modifier = Modifier.size(18.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = patient.name,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Serif,
                    color = CharcoalText
                )
                Text(
                    text = (if (patient.age > 0) "Age ${patient.age} · " else "") + patient.presenting,
                    fontSize = 12.sp,
                    color = MutedText
                )
            }

            IconButton(onClick = { confirmDelete = true }, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete patient",
                    tint = MutedText,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        if (confirmDelete) {
            ConfirmDeletePatientDialog(
                patient = patient,
                onDismiss = { confirmDelete = false },
                onConfirm = {
                    confirmDelete = false
                    onDeletePatient(patient)
                }
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Tabs
        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = Color.Transparent,
            contentColor = SageDark,
            indicator = { tabPositions ->
                TabRowDefaults.SecondaryIndicator(
                    modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                    color = SageGreen
                )
            },
            divider = { Box(Modifier.fillMaxWidth().height(1.dp).background(BorderSubtle)) }
        ) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = {
                        Text(
                            text = title,
                            fontSize = 13.sp,
                            fontWeight = if (selectedTab == index) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (selectedTab == index) SageDark else MutedText
                        )
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        when (selectedTab) {
            0 -> OverviewTab(
                patient = patient,
                isSessionActive = isSessionActive,
                onStartSession = onStartSession,
                onReturnToActiveSession = onReturnToActiveSession
            )
            1 -> SessionsTab(patient = patient)
            2 -> FactsTab(patient = patient)
        }
    }
}

@Composable
fun OverviewTab(
    patient: Patient,
    isSessionActive: Boolean,
    onStartSession: (Patient) -> Unit,
    onReturnToActiveSession: () -> Unit
) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            // Start / Return to Session CTA
            Button(
                onClick = {
                    if (isSessionActive) onReturnToActiveSession()
                    else onStartSession(patient)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = SageGreen,
                    contentColor = Color.White
                )
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color.White.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Mic,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Column {
                            Text(
                                text = if (isSessionActive) "Return to Active Session" else "Start Session",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp
                            )
                            Text(
                                text = if (isSessionActive) "Session currently recording in background" else "Records locally & surfaces past notes",
                                fontSize = 11.sp,
                                color = Color.White.copy(alpha = 0.8f)
                            )
                        }
                    }

                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        item {
            // Pre-session Briefing
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = SageGreen,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "MindVault Brief",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp,
                            color = SageDark
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = patient.preSessionNote,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        color = CharcoalText
                    )
                }
            }
        }

        item {
            // Recent sessions header
            Text(
                text = "Recent Sessions",
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                color = CharcoalText,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        items(patient.sessions) { session ->
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = session.date,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp,
                            color = CharcoalText
                        )
                        Text(
                            text = session.duration,
                            fontSize = 12.sp,
                            color = MutedText
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = session.summary,
                        fontSize = 12.sp,
                        color = CharcoalText,
                        lineHeight = 17.sp
                    )

                    if (session.topics.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            session.topics.take(3).forEach { topic ->
                                PillBadge(text = topic)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SessionsTab(patient: Patient) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(patient.sessions) { session ->
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = session.date,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Serif,
                            fontSize = 14.sp,
                            color = CharcoalText
                        )
                        PillBadge(text = session.duration)
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = session.summary,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        color = CharcoalText
                    )

                    if (session.verbatimQuotes.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "VERBATIM QUOTES",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = MutedText
                        )
                        session.verbatimQuotes.forEach { quote ->
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(SageLight.copy(alpha = 0.5f))
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = "\"$quote\"",
                                    fontSize = 12.sp,
                                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                                    color = SageDark
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
fun FactsTab(patient: Patient) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            Text(
                text = "Key Facts & Verifiable Profile",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = CharcoalText
            )
            Text(
                text = "Verified from on-device transcripts and encrypted locally.",
                fontSize = 11.sp,
                color = MutedText,
                modifier = Modifier.padding(bottom = 6.dp)
            )
        }

        items(patient.facts) { fact ->
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(SageGreen)
                    )
                    Text(
                        text = fact,
                        fontSize = 13.sp,
                        color = CharcoalText
                    )
                }
            }
        }
    }
}
