package com.therapylocal.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.therapylocal.app.memory.Fact
import com.therapylocal.app.ui.components.PillBadge
import com.therapylocal.app.ui.models.Patient
import com.therapylocal.app.ui.theme.CharcoalText
import com.therapylocal.app.ui.theme.DangerRed
import com.therapylocal.app.ui.theme.MutedText
import com.therapylocal.app.ui.theme.SageDark
import com.therapylocal.app.ui.theme.SageGreen
import com.therapylocal.app.ui.theme.SageLight
import com.therapylocal.app.ui.theme.SurfaceCard
import com.therapylocal.app.ui.theme.WarmBg

/** Factual summary of a finished session: the facts extracted from it (as processing finishes), the therapist's own observations, save or delete. */
@Composable
fun SummaryScreen(
    patient: Patient,
    durationFormatted: String,
    processing: String,
    facts: List<Fact>,
    observations: String,
    onObservationsChange: (String) -> Unit,
    canLeave: Boolean,
    canDelete: Boolean,
    onSaveToVault: () -> Unit,
    onDeleteSession: () -> Unit,
    modifier: Modifier = Modifier
) {
    var confirmDelete by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(WarmBg)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
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

        PillBadge(text = processing, icon = Icons.Default.AutoAwesome)

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
                        Text(text = "FACTS FROM THIS SESSION", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MutedText)
                        Spacer(modifier = Modifier.height(8.dp))
                        if (facts.isEmpty()) {
                            Text(text = "None yet. They appear here as the transcript is processed on this device.", fontSize = 13.sp, color = MutedText)
                        }
                        facts.groupBy { it.category }.forEach { (category, items) ->
                            Spacer(modifier = Modifier.height(6.dp))
                            PillBadge(text = category, color = SageDark, background = SageLight)
                            items.forEach { f ->
                                Text(text = f.fact, fontSize = 13.sp, lineHeight = 18.sp, color = CharcoalText, modifier = Modifier.padding(top = 6.dp))
                                if (f.evidence.isNotBlank()) {
                                    Text(
                                        text = "\"${f.evidence}\"",
                                        fontSize = 12.sp,
                                        fontStyle = FontStyle.Italic,
                                        color = MutedText,
                                        modifier = Modifier.padding(top = 2.dp)
                                    )
                                }
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
                        Text(text = "YOUR OBSERVATIONS", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MutedText)
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = observations,
                            onValueChange = onObservationsChange,
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("Your own notes on this session (stored on this device only)", fontSize = 12.sp) },
                            minLines = 3,
                            shape = RoundedCornerShape(10.dp)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = onSaveToVault,
            enabled = canLeave,
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
                text = if (canLeave) "Save to Encrypted Vault" else "Finishing recording...",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
        }

        Row(modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
            OutlinedButton(
                onClick = { confirmDelete = true },
                enabled = canDelete,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text(text = "Delete this session's data", color = DangerRed, fontSize = 13.sp)
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this session?") },
            text = { Text("Removes its transcript, extracted facts and your observations from this device. This cannot be undone.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDeleteSession() }) { Text("Delete", color = DangerRed) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}
