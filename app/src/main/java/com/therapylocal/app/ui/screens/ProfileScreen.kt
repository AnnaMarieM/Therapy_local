package com.therapylocal.app.ui.screens

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.therapylocal.app.ui.components.FactDetailSheet
import com.therapylocal.app.ui.components.PillBadge
import com.therapylocal.app.ui.components.TranscriptLines
import com.therapylocal.app.ui.components.TranscriptSheet
import com.therapylocal.app.ui.data.ClientRepository
import com.therapylocal.app.ui.models.FactItem
import com.therapylocal.app.ui.models.Patient
import com.therapylocal.app.ui.models.ProcessingSessionInfo
import com.therapylocal.app.ui.models.SearchHit
import com.therapylocal.app.ui.models.SessionNotes
import com.therapylocal.app.ui.models.TranscriptLine
import com.therapylocal.app.ui.theme.BorderSubtle
import com.therapylocal.app.ui.theme.CharcoalText
import com.therapylocal.app.ui.theme.DangerRed
import com.therapylocal.app.ui.theme.MutedText
import com.therapylocal.app.ui.theme.SageDark
import com.therapylocal.app.ui.theme.SageGreen
import com.therapylocal.app.ui.theme.SageLight
import com.therapylocal.app.ui.theme.SagePill
import com.therapylocal.app.ui.theme.SurfaceCard
import com.therapylocal.app.ui.theme.WarmBg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One client: start a session, search its sessions ([search] is the search box, given a way to open a result), its meeting notes and its
 * facts. Facts and transcripts are read through [repo]; [onChanged] reloads the client after a session is deleted or notes are saved.
 */
@Composable
fun ProfileScreen(
    patient: Patient,
    isSessionActive: Boolean,
    repo: ClientRepository,
    processingSession: ProcessingSessionInfo? = null,
    onBack: () -> Unit,
    onDeletePatient: (Patient) -> Unit,
    onStartSession: (Patient) -> Unit,
    onReturnToActiveSession: () -> Unit,
    onChanged: () -> Unit,
    search: @Composable (onOpen: (SearchHit) -> Unit) -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var confirmDelete by remember { mutableStateOf(false) }
    var openFact by remember { mutableStateOf<FactItem?>(null) }
    var openHit by remember { mutableStateOf<SearchHit?>(null) }
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
                processingSession = processingSession,
                onStartSession = onStartSession,
                onReturnToActiveSession = onReturnToActiveSession,
                search = { search { openHit = it } }
            )
            1 -> SessionsTab(patient = patient, repo = repo, processingSession = processingSession, onOpenFact = { openFact = it }, onChanged = onChanged)
            2 -> FactsTab(patient = patient, onOpenFact = { openFact = it })
        }
    }

    openFact?.let { fact ->
        FactDetailSheet(fact = fact, load = { withContext(Dispatchers.IO) { repo.occurrences(fact.id) } }, onDismiss = { openFact = null })
    }
    openHit?.let { hit ->
        TranscriptSheet(
            title = hit.title?.let { "$it · ${hit.date}" } ?: hit.date,
            load = { withContext(Dispatchers.IO) { repo.transcript(hit.sessionId, hit.segFrom, hit.segTo) } },
            highlight = hit.highlight,
            onDismiss = { openHit = null }
        )
    }
}

@Composable
fun OverviewTab(
    patient: Patient,
    isSessionActive: Boolean,
    processingSession: ProcessingSessionInfo? = null,
    onStartSession: (Patient) -> Unit,
    onReturnToActiveSession: () -> Unit,
    search: @Composable () -> Unit
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
                                text = if (isSessionActive) "Session currently recording in background" else "Records the session and brings up past facts",
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

        if (processingSession != null) {
            item(key = "overview_processing_card") {
                ProcessingSessionCard(processingSession = processingSession)
            }
        }

        item(key = "search") { search() }

        if (patient.sessions.isNotEmpty()) {
            item {
                Text(
                    text = "Recent Sessions",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    color = CharcoalText,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            items(patient.sessions.take(3), key = { "recent-" + it.id }) { session ->
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
                            text = if (session.facts.isEmpty()) "No new facts." else session.facts.joinToString(" · ") { it.heading },
                            fontSize = 12.sp,
                            color = CharcoalText,
                            lineHeight = 17.sp
                        )
                    }
                }
            }
        } else {
            item {
                EmptyCard(
                    title = "No sessions recorded yet",
                    text = "Tap 'Start Session' above to begin recording. Facts and the transcript of each session will appear here automatically."
                )
            }
        }
    }
}

/** Meeting notes per session: the facts first heard in it, the full transcript (on demand) and the therapist's own notes. */
@Composable
fun SessionsTab(
    patient: Patient,
    repo: ClientRepository,
    processingSession: ProcessingSessionInfo? = null,
    onOpenFact: (FactItem) -> Unit,
    onChanged: () -> Unit
) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        if (processingSession != null) {
            item(key = "sessions_processing_card") {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = SageLight.copy(alpha = 0.45f)),
                    border = BorderStroke(1.dp, SageGreen.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = processingSession.date.ifEmpty { "Today" },
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Serif,
                                fontSize = 14.sp,
                                color = CharcoalText
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(12.dp),
                                    strokeWidth = 2.dp,
                                    color = SageDark
                                )
                                PillBadge(text = "Processing")
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = if (processingSession.stage.isNotBlank()) "Processing session · ${processingSession.stage}" else "Session is being processed in the background.",
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                            color = CharcoalText
                        )

                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Facts and the transcript will appear here once processing completes.",
                            fontSize = 11.sp,
                            color = MutedText
                        )
                    }
                }
            }
        }

        if (patient.sessions.isEmpty() && processingSession == null) {
            item {
                EmptyCard(title = "No sessions yet", text = "Each session's facts, transcript and your notes will be listed here.")
            }
        }

        items(patient.sessions, key = { it.id }) { session ->
            SessionNotesCard(patient = patient, session = session, repo = repo, onOpenFact = onOpenFact, onChanged = onChanged)
        }
    }
}

@Composable
private fun SessionNotesCard(patient: Patient, session: SessionNotes, repo: ClientRepository, onOpenFact: (FactItem) -> Unit, onChanged: () -> Unit) {
    val scope = rememberCoroutineScope()
    var showTranscript by remember { mutableStateOf(false) }
    var transcript by remember(session.id) { mutableStateOf<List<TranscriptLine>?>(null) }
    var notes by remember(session.id, session.observations) { mutableStateOf(session.observations) }
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(showTranscript) {
        if (showTranscript && transcript == null) transcript = withContext(Dispatchers.IO) { repo.transcript(session.id) }
    }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceCard),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = session.date,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Serif,
                    fontSize = 14.sp,
                    color = CharcoalText,
                    modifier = Modifier.weight(1f)
                )
                PillBadge(text = session.duration)
                IconButton(onClick = { confirmDelete = true }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete session", tint = MutedText, modifier = Modifier.size(16.dp))
                }
            }

            SectionLabel("FACTS")
            if (session.facts.isEmpty()) Text(text = "No new facts in this session.", fontSize = 12.sp, color = MutedText)
            session.facts.forEach { fact -> FactRow(fact, onClick = { onOpenFact(fact) }) }

            SectionLabel("TRANSCRIPT")
            TextButton(onClick = { showTranscript = !showTranscript }) {
                Text(if (showTranscript) "Hide transcript" else "Show transcript", color = SageDark, fontSize = 12.sp)
            }
            if (showTranscript) {
                val lines = transcript
                when {
                    lines == null -> CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = SageGreen)
                    lines.isEmpty() -> Text(text = "Not transcribed yet.", fontSize = 12.sp, color = MutedText)
                    else -> TranscriptLines(lines = lines)
                }
            }

            SectionLabel("YOUR NOTES")
            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                placeholder = { Text("Your own notes on this session", fontSize = 12.sp) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = SageGreen, unfocusedBorderColor = BorderSubtle, cursorColor = SageGreen),
                shape = RoundedCornerShape(10.dp)
            )
            if (notes != session.observations) {
                TextButton(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { repo.saveObservations(session.id, notes) }
                        onChanged()
                    }
                }) { Text("Save notes", color = SageDark, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete the session of ${session.date}?") },
            text = { Text(DELETE_SESSION_TEXT) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        withContext(Dispatchers.IO) { repo.deleteSession(patient.id, session.id) }
                        onChanged()
                    }
                }) { Text("Delete", color = DangerRed) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
fun FactsTab(patient: Patient, onOpenFact: (FactItem) -> Unit) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            Text(
                text = "Facts",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = CharcoalText
            )
            Text(
                text = "Taken from your session transcripts. Tap one to see where it was said.",
                fontSize = 11.sp,
                color = MutedText,
                modifier = Modifier.padding(bottom = 6.dp)
            )
        }

        if (patient.facts.isEmpty()) {
            item { EmptyCard(title = "No facts yet", text = "Facts are taken from each session's transcript after the session.") }
        } else {
            items(patient.facts, key = { it.id }) { fact ->
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) { FactRow(fact, onClick = { onOpenFact(fact) }) }
                }
            }
        }
    }
}

/** The heading in bold, the content below, and "×N" (how many sessions it was said in) once it came up again. */
@Composable
private fun FactRow(fact: FactItem, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = fact.heading, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = CharcoalText)
            Text(text = fact.content, fontSize = 12.sp, lineHeight = 17.sp, color = CharcoalText, modifier = Modifier.padding(top = 2.dp))
        }
        if (fact.mentionCount > 0) PillBadge(text = "×${fact.mentionCount + 1}", color = SageDark, background = SagePill)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        color = MutedText,
        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp)
    )
}

@Composable
private fun EmptyCard(title: String, text: String) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceCard),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = CharcoalText
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = text,
                fontSize = 12.sp,
                color = MutedText,
                textAlign = TextAlign.Center,
                lineHeight = 17.sp
            )
        }
    }
}

@Composable
fun ProcessingSessionCard(
    processingSession: ProcessingSessionInfo,
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = SageLight.copy(alpha = 0.45f)),
        border = BorderStroke(1.dp, SageGreen.copy(alpha = 0.5f)),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = SageDark
                    )
                    Text(
                        text = "Session Processing",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        color = SageDark
                    )
                }
                PillBadge(text = processingSession.date.ifEmpty { "Today" })
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Transcribing and taking notes on this session...",
                fontSize = 12.sp,
                color = CharcoalText,
                lineHeight = 17.sp
            )

            if (processingSession.stage.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "Stage:",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = MutedText
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(SurfaceCard)
                            .border(1.dp, BorderSubtle, RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = processingSession.stage,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = SageDark
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Facts will appear here automatically when done.",
                fontSize = 11.sp,
                color = MutedText
            )
        }
    }
}
