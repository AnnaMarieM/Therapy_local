package com.therapylocal.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.therapylocal.app.ui.models.Patient
import com.therapylocal.app.ui.theme.BorderSubtle
import com.therapylocal.app.ui.theme.CharcoalText
import com.therapylocal.app.ui.theme.MutedText
import com.therapylocal.app.ui.theme.SageDark
import com.therapylocal.app.ui.theme.SageGreen
import com.therapylocal.app.ui.theme.SageLight
import com.therapylocal.app.ui.theme.SurfaceCard
import com.therapylocal.app.ui.theme.WarmBg

@Composable
fun SummaryScreen(
    patient: Patient,
    durationFormatted: String,
    onSaveToVault: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(WarmBg)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        // Header
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            Text(
                text = "Session Summary",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Serif,
                color = CharcoalText
            )
            Text(
                text = "${patient.name} · $durationFormatted session",
                fontSize = 13.sp,
                color = MutedText
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        PillBadge(
            text = "Generated on-device via LiteRT-LM (Gemma 4)",
            icon = Icons.Default.AutoAwesome
        )

        Spacer(modifier = Modifier.height(14.dp))

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "CLINICAL OVERVIEW",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MutedText
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Client explored recent somatic responses tied to upcoming milestones. Maintained strong therapeutic rapport while identifying recurring patterns of guilt and interpersonal conflict avoidance.",
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                            color = CharcoalText
                        )
                    }
                }
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "KEY THEMES",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MutedText
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            PillBadge(text = "Boundary Setting")
                            PillBadge(text = "Somatic Awareness")
                            PillBadge(text = "Grief Container")
                        }
                    }
                }
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "ACTION ITEMS FOR CLIENT",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MutedText
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        listOf(
                            "Implement daily 15-minute somatic decompression before evening commute.",
                            "Journal without self-censoring when resentment arises around task delegation.",
                            "Practice setting one minor interpersonal boundary without apologizing."
                        ).forEach { item ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(16.dp)
                                        .clip(CircleShape)
                                        .background(SageLight),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        tint = SageDark,
                                        modifier = Modifier.size(10.dp)
                                    )
                                }
                                Text(
                                    text = item,
                                    fontSize = 13.sp,
                                    color = CharcoalText,
                                    lineHeight = 18.sp
                                )
                            }
                        }
                    }
                }
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "VERBATIM QUOTES (AUTHENTICATED)",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MutedText
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "\"I felt like if I just paused for one second, everything would crumble.\"",
                            fontSize = 13.sp,
                            fontStyle = FontStyle.Italic,
                            color = SageDark,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(SageLight.copy(alpha = 0.5f))
                                .padding(10.dp)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = onSaveToVault,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = SageGreen,
                contentColor = Color.White
            )
        ) {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.size(8.dp))
            Text(
                text = "Save to Encrypted Vault",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}
