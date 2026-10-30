package com.therapylocal.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.therapylocal.app.models.ModelSetup
import com.therapylocal.app.models.SetupState
import com.therapylocal.app.ui.components.SetupBanner
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.therapylocal.app.ui.components.InitialsAvatar
import com.therapylocal.app.ui.logic.SessionLogic
import com.therapylocal.app.ui.models.Patient
import com.therapylocal.app.ui.models.ProcessingSessionInfo
import com.therapylocal.app.ui.theme.BorderSubtle
import com.therapylocal.app.ui.theme.CharcoalText
import com.therapylocal.app.ui.theme.MutedText
import com.therapylocal.app.ui.theme.SageDark
import com.therapylocal.app.ui.theme.SageGreen
import com.therapylocal.app.ui.theme.SageLight
import com.therapylocal.app.ui.theme.SurfaceCard
import com.therapylocal.app.ui.theme.WarmBg

@Composable
fun PatientsScreen(
    patients: List<Patient>,
    processingByClient: Map<String, ProcessingSessionInfo> = emptyMap(),
    onSelectPatient: (Patient) -> Unit,
    onAddPatient: (Patient) -> Patient,
    onDeletePatient: (Patient) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val setup by ModelSetup.progress.collectAsStateWithLifecycle()
    var searchQuery by remember { mutableStateOf("") }
    var showAddDialog by remember { mutableStateOf(false) }
    var patientToDelete by remember { mutableStateOf<Patient?>(null) }

    // Search across patient names and facts
    val filteredPatients by remember(searchQuery, patients) {
        derivedStateOf {
            SessionLogic.filterPatients(patients, searchQuery)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(WarmBg)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Patients",
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Serif,
                    color = CharcoalText
                )
                Text(
                    text = "Your clients",
                    fontSize = 12.sp,
                    color = MutedText
                )
            }

            IconButton(
                onClick = onOpenSettings,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .border(1.dp, BorderSubtle, RoundedCornerShape(12.dp))
                    .background(SurfaceCard)
                    .size(40.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Settings",
                    tint = SageDark,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Search bar
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier.fillMaxWidth(),
            textStyle = androidx.compose.ui.text.TextStyle(color = CharcoalText, fontSize = 14.sp, fontWeight = FontWeight.Medium),
            placeholder = { Text("Search patients or facts (e.g. 'father')...", fontSize = 13.sp, color = CharcoalText) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = SageDark, modifier = Modifier.size(20.dp)) },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = CharcoalText,
                unfocusedTextColor = CharcoalText,
                focusedContainerColor = SurfaceCard,
                unfocusedContainerColor = SurfaceCard,
                focusedBorderColor = SageGreen,
                unfocusedBorderColor = BorderSubtle,
                cursorColor = SageGreen,
                focusedPlaceholderColor = CharcoalText,
                unfocusedPlaceholderColor = CharcoalText
            )
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Patient list
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (setup.state != SetupState.Ready) {
                item(key = "setup-banner") { SetupBanner(progress = setup, onRetry = { ModelSetup.retry(context) }) }
            }
            if (processingByClient.isNotEmpty()) {
                item(key = "sessions-processing-banner") {
                    Card(
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = SageLight.copy(alpha = 0.5f)),
                        border = BorderStroke(1.dp, SageGreen.copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = SageDark
                            )
                            val count = processingByClient.size
                            Text(
                                text = if (count == 1) "1 session is processing in background" else "$count sessions are processing in background",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = SageDark
                            )
                        }
                    }
                }
            }
            items(filteredPatients, key = { it.id }) { patient ->
                PatientCard(
                    patient = patient,
                    processing = processingByClient[patient.id],
                    onClick = { onSelectPatient(patient) },
                    onLongClick = { patientToDelete = patient }
                )
            }

            item {
                // Add patient button
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp, bottom = 16.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .border(1.dp, SageGreen.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                        .background(SurfaceCard.copy(alpha = 0.5f))
                        .clickable { showAddDialog = true }
                        .padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Add",
                            tint = SageDark,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "Add patient",
                            color = SageDark,
                            fontWeight = FontWeight.Medium,
                            fontSize = 14.sp
                        )
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddPatientDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { newPatient ->
                val created = onAddPatient(newPatient)
                showAddDialog = false
                onSelectPatient(created)
            }
        )
    }

    patientToDelete?.let { p ->
        ConfirmDeletePatientDialog(
            patient = p,
            onDismiss = { patientToDelete = null },
            onConfirm = {
                onDeletePatient(p)
                patientToDelete = null
            }
        )
    }
}

/** Asks before deleting a patient; deletion removes the profile, sessions, transcripts and facts for good. */
@Composable
fun ConfirmDeletePatientDialog(
    patient: Patient,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete ${patient.name}?", fontWeight = FontWeight.Bold, fontFamily = FontFamily.Serif, color = CharcoalText) },
        text = { Text("Removes the profile, all sessions, transcripts and facts stored for them. This cannot be undone.", fontSize = 13.sp, color = CharcoalText) },
        confirmButton = {
            Button(onClick = onConfirm, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB3261E))) { Text("Delete") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = CharcoalText, fontWeight = FontWeight.Medium) } },
        containerColor = SurfaceCard,
        shape = RoundedCornerShape(20.dp)
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PatientCard(
    patient: Patient,
    processing: ProcessingSessionInfo? = null,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceCard),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f)
            ) {
                InitialsAvatar(name = patient.name, size = 42)
                Column {
                    Text(
                        text = patient.name,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
                        color = CharcoalText
                    )
                    if (processing != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                            modifier = Modifier.padding(top = 2.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(10.dp),
                                strokeWidth = 1.5.dp,
                                color = SageGreen
                            )
                            Text(
                                text = if (processing.stage.isNotBlank()) "Processing · ${processing.stage}" else "Processing session...",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = SageDark
                            )
                        }
                    } else {
                        Text(
                            text = "Last session: ${patient.lastSession}",
                            fontSize = 12.sp,
                            color = MutedText,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }
            }

            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MutedText.copy(alpha = 0.6f),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
fun AddPatientDialog(
    onDismiss: () -> Unit,
    onConfirm: (Patient) -> Unit
) {
    var firstName by remember { mutableStateOf("") }
    var lastName by remember { mutableStateOf("") }

    // Preview privacy name: "First Name" + first letter of "Last Name"
    val formattedName = remember(firstName, lastName) {
        val f = firstName.trim().replaceFirstChar { it.uppercase() }
        val l = lastName.trim().firstOrNull()?.uppercase()
        if (f.isNotEmpty() && l != null) "$f $l."
        else if (f.isNotEmpty()) f
        else ""
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Add Patient",
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Serif,
                color = CharcoalText
            )
        },
        text = {
            val patientFieldColors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = CharcoalText,
                unfocusedTextColor = CharcoalText,
                focusedLabelColor = SageDark,
                unfocusedLabelColor = CharcoalText,
                focusedBorderColor = SageGreen,
                unfocusedBorderColor = BorderSubtle,
                cursorColor = SageGreen,
                focusedContainerColor = SurfaceCard,
                unfocusedContainerColor = SurfaceCard,
            )
            val fieldTextStyle = androidx.compose.ui.text.TextStyle(
                color = CharcoalText,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = firstName,
                        onValueChange = { firstName = it },
                        label = { Text("First Name", fontSize = 12.sp, color = CharcoalText, fontWeight = FontWeight.SemiBold) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        textStyle = fieldTextStyle,
                        colors = patientFieldColors,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                        shape = RoundedCornerShape(10.dp)
                    )
                    OutlinedTextField(
                        value = lastName,
                        onValueChange = { lastName = it },
                        label = { Text("Last Name", fontSize = 12.sp, color = CharcoalText, fontWeight = FontWeight.SemiBold) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        textStyle = fieldTextStyle,
                        colors = patientFieldColors,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                        shape = RoundedCornerShape(10.dp)
                    )
                }

                if (formattedName.isNotBlank()) {
                    Text(
                        text = formattedName,
                        fontSize = 11.sp,
                        color = SageDark,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (formattedName.isNotBlank()) {
                        val p = Patient(
                            name = formattedName,
                            lastSession = "Just added",
                            sessionCount = 0
                        )
                        onConfirm(p)
                    }
                },
                enabled = formattedName.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = SageGreen)
            ) {
                Text("Save Patient")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = CharcoalText, fontWeight = FontWeight.Medium)
            }
        },
        containerColor = SurfaceCard,
        shape = RoundedCornerShape(20.dp)
    )
}
