package com.therapylocal.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.therapylocal.app.MainViewModel
import com.therapylocal.app.Phase
import com.therapylocal.app.memory.Fact
import com.therapylocal.app.memory.JobStatus
import com.therapylocal.app.memory.MemoryProcessor
import com.therapylocal.app.memory.MemoryQueue
import com.therapylocal.app.ui.data.ClientsViewModel
import com.therapylocal.app.ui.components.ChoosePatientSessionDialog
import com.therapylocal.app.ui.components.MindVaultBottomNav
import com.therapylocal.app.ui.components.MinimizedSessionBar
import com.therapylocal.app.ui.models.Patient
import com.therapylocal.app.ui.models.ProcessingSessionInfo
import com.therapylocal.app.ui.security.findFragmentActivity
import com.therapylocal.app.vault.Vault
import com.therapylocal.app.ui.models.SurfacedQuote
import com.therapylocal.app.ui.screens.LiveSessionScreen
import com.therapylocal.app.ui.screens.PatientsScreen
import com.therapylocal.app.ui.screens.ProfileScreen
import com.therapylocal.app.ui.screens.SettingsScreen
import com.therapylocal.app.ui.screens.SummaryScreen
import com.therapylocal.app.ui.theme.MindVaultTheme
import com.therapylocal.app.ui.theme.WarmBg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun MindVaultApp(
    viewModel: MainViewModel,
    hasMicPermission: () -> Boolean,
    requestMicPermission: () -> Unit,
    onShowLegacyDebugView: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    MindVaultTheme {
        var currentTab by rememberSaveable { mutableStateOf("patients") }
        var currentScreen by rememberSaveable { mutableStateOf("patients") }

        // Clients come from the on-device memory store.
        val clientsVm: ClientsViewModel = viewModel()
        val patients by clientsVm.patients.collectAsStateWithLifecycle()
        var selectedPatient by remember { mutableStateOf<Patient?>(null) }

        // Persistent Session State
        var isSessionActive by rememberSaveable { mutableStateOf(false) }
        var showChoosePatientDialog by rememberSaveable { mutableStateOf(false) }
        var sessionPatient by remember { mutableStateOf<Patient?>(null) }
        var sessionSecs by rememberSaveable { mutableLongStateOf(0L) }
        val sessionPaused = false // TODO: pausing needs ChunkRecorder support
        val surfacedQuotes = remember { mutableStateListOf<SurfacedQuote>() }

        // The real session: the memory client it belongs to, its id, the recording behind it, and where end-of-session processing stands.
        var sessionClient by rememberSaveable { mutableStateOf("") }
        var sessionId by rememberSaveable { mutableStateOf("") }
        var sessionDate by rememberSaveable { mutableStateOf("") }
        var recordingId by remember { mutableStateOf<Long?>(null) }
        var sessionEnded by rememberSaveable { mutableStateOf(false) }
        var recordingStarted by rememberSaveable { mutableStateOf(false) }
        var enqueued by rememberSaveable { mutableStateOf(false) }
        var processNote by rememberSaveable { mutableStateOf<String?>(null) }
        var transcript by remember { mutableStateOf(emptyList<String>()) }

        val vmState by viewModel.state.collectAsStateWithLifecycle()
        val cues by viewModel.liveCues.cues.collectAsStateWithLifecycle()
        val cuesError by viewModel.liveCues.error.collectAsStateWithLifecycle()
        val queue by MemoryQueue.state.collectAsStateWithLifecycle()
        val repo = clientsVm.repo
        val activity = LocalContext.current.findFragmentActivity()
        var confirmLock by remember { mutableStateOf(false) }

        // Elapsed time counts only while audio is really being recorded.
        LaunchedEffect(isSessionActive, vmState.recording) {
            while (isSessionActive && vmState.recording) {
                delay(1000L)
                sessionSecs += 1
            }
        }

        // The recording starts immediately; without loaded engines it is stored and transcribed later (no live cues).
        LaunchedEffect(isSessionActive, sessionEnded, recordingStarted) {
            if (isSessionActive && !sessionEnded && !recordingStarted) {
                recordingStarted = true
                if (vmState.phase == Phase.Ready) viewModel.liveCues.start(sessionClient)
                viewModel.startRecording()
            }
        }
        LaunchedEffect(vmState.activeRecordingId) { vmState.activeRecordingId?.let { recordingId = it } }

        // Transcript chunks arrive as the pipeline stores them (the recordings list changes after each chunk).
        LaunchedEffect(recordingId, vmState.recordings) {
            recordingId?.let { rid -> transcript = viewModel.recordingChunks(rid).map { it.text }.filter { it.isNotBlank() } }
        }

        var pendingEnqueue by remember { mutableStateOf<Triple<String, String, String>?>(null) }
        var pendingRecordingId by remember { mutableStateOf<Long?>(null) }

        // Background session enqueue: once audio recording and every chunk finish transcribing, hand the merged transcript to MemoryProcessor.
        LaunchedEffect(pendingEnqueue, pendingRecordingId, vmState.recording, vmState.activeRecordingId, vmState.status, vmState.error) {
            val pending = pendingEnqueue ?: return@LaunchedEffect
            val rid = pendingRecordingId ?: vmState.activeRecordingId
            if (rid == null) {
                if (!vmState.recording && vmState.error != null) pendingEnqueue = null
                return@LaunchedEffect
            }
            if (vmState.recording || vmState.activeRecordingId != null || vmState.status != "Done") return@LaunchedEffect
            if (viewModel.isDeferred(rid)) {
                // The queue transcribes the stored audio once setup is done, then removes the recording itself.
                withContext(Dispatchers.IO) { repo.processRecording(pending.first, pending.second, pending.third, rid) }
                enqueued = true
                pendingEnqueue = null
                pendingRecordingId = null
                return@LaunchedEffect
            }
            val text = viewModel.recordingTranscript(rid)
            withContext(Dispatchers.IO) { repo.processSession(pending.first, pending.second, pending.third, text) }
            viewModel.deleteRecording(rid)
            pendingEnqueue = null
            pendingRecordingId = null
        }
        LaunchedEffect(queue.jobs.firstOrNull { it.id == sessionId }?.status) { clientsVm.refresh() }
        LaunchedEffect(queue.jobs.count { it.status == JobStatus.Done }) { clientsVm.refresh() }

        // Keep selectedPatient up-to-date with the latest data in patients
        LaunchedEffect(patients) {
            selectedPatient?.let { sel ->
                patients.firstOrNull { it.id == sel.id }?.let { selectedPatient = it }
            }
        }

        val processingByClient = remember(queue.jobs, pendingEnqueue) {
            val map = mutableMapOf<String, ProcessingSessionInfo>()
            queue.jobs.filter { it.status == JobStatus.Queued || it.status == JobStatus.Running }.forEach { job ->
                val stageText = when {
                    job.stage.isNotBlank() -> job.stage
                    job.status == JobStatus.Queued -> "Queued"
                    job.recordingId != 0L -> "Transcribing audio"
                    else -> "Extracting facts"
                }
                map[job.client] = ProcessingSessionInfo(
                    sessionId = job.id,
                    date = job.date.ifEmpty { "Today" },
                    stage = stageText,
                    isPendingEnqueue = false
                )
            }
            pendingEnqueue?.let { (client, sid, date) ->
                if (!map.containsKey(client)) {
                    map[client] = ProcessingSessionInfo(
                        sessionId = sid,
                        date = date.ifEmpty { "Today" },
                        stage = "Finalizing recording...",
                        isPendingEnqueue = true
                    )
                }
            }
            map
        }

        val resetSession = {
            if (sessionPatient != null) selectedPatient = sessionPatient
            isSessionActive = false
            sessionPatient = null
            sessionId = ""
            sessionClient = ""
            recordingId = null
            sessionEnded = false
            recordingStarted = false
            enqueued = false
            processNote = null
            transcript = emptyList()
            currentTab = "patients"
            currentScreen = "patients"
            clientsVm.refresh()
        }

        val startSession = { p: Patient ->
            if (!hasMicPermission()) requestMicPermission()
            selectedPatient = p
            val client = p.id
            val now = System.currentTimeMillis()
            sessionPatient = p
            sessionClient = client
            sessionId = MemoryProcessor.sessionId(client, now)
            sessionDate = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(now))
            sessionSecs = 0L
            surfacedQuotes.clear()
            transcript = emptyList()
            recordingId = null
            sessionEnded = false
            recordingStarted = false
            enqueued = false
            processNote = null
            isSessionActive = true
            currentTab = "session"
            currentScreen = "live"
        }

        val endSession = {
            viewModel.liveCues.stop()
            viewModel.stopRecording()
            pendingEnqueue = Triple(sessionClient, sessionId, sessionDate)
            pendingRecordingId = recordingId ?: vmState.activeRecordingId
            sessionEnded = true
            isSessionActive = false
            currentScreen = "summary"
        }

        val togglePause = { }

        if (confirmLock) {
            AlertDialog(
                onDismissRequest = { confirmLock = false },
                title = { Text("Lock MindVault?") },
                text = { Text("Session processing will pause and continue after you unlock.") },
                confirmButton = { TextButton(onClick = { confirmLock = false; activity?.let(Vault::lock) }) { Text("Lock") } },
                dismissButton = { TextButton(onClick = { confirmLock = false }) { Text("Cancel") } },
            )
        }

        if (showChoosePatientDialog) {
            ChoosePatientSessionDialog(
                patients = patients,
                onSelectPatient = { chosen ->
                    showChoosePatientDialog = false
                    startSession(chosen)
                },
                onDismiss = {
                    showChoosePatientDialog = false
                }
            )
        }

        Box(
            modifier = modifier
                .fillMaxSize()
                .background(WarmBg)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.weight(1f)) {
                    when (currentScreen) {
                        "patients" -> {
                            PatientsScreen(
                                patients = patients,
                                processingByClient = processingByClient,
                                onSelectPatient = { p ->
                                    selectedPatient = p
                                    currentScreen = "profile"
                                },
                                onAddPatient = { newP -> clientsVm.create(newP.name, null, "") },
                                onDeletePatient = { p -> clientsVm.delete(p.id) },
                                onOpenSettings = {
                                    currentTab = "settings"
                                    currentScreen = "settings"
                                }
                            )
                        }

                        "profile" -> {
                            val activeP = (selectedPatient?.id?.let { id -> patients.firstOrNull { it.id == id } }
                                ?: selectedPatient
                                ?: patients.firstOrNull())
                            if (activeP != null) {
                                ProfileScreen(
                                    patient = activeP,
                                    isSessionActive = isSessionActive && sessionPatient?.id == activeP.id,
                                    processingSession = processingByClient[activeP.id],
                                    onBack = {
                                        currentScreen = "patients"
                                    },
                                    onDeletePatient = { p ->
                                        clientsVm.delete(p.id)
                                        selectedPatient = null
                                        currentScreen = "patients"
                                    },
                                    onStartSession = { p -> startSession(p) },
                                    onReturnToActiveSession = {
                                        currentTab = "session"
                                        currentScreen = "live"
                                    }
                                )
                            }
                        }

                        "live" -> {
                            val p = sessionPatient ?: selectedPatient ?: patients.first()
                            LiveSessionScreen(
                                patient = p,
                                sessionSecs = sessionSecs,
                                surfacedQuotes = surfacedQuotes,
                                transcript = transcript,
                                statusLine = when {
                                    vmState.error != null -> "Recording problem: ${vmState.error}"
                                    vmState.recording -> null
                                    else -> vmState.status.ifEmpty { "Starting" }
                                },
                                deferredNote = vmState.deferred && vmState.recording,
                                cues = cues,
                                cuesError = cuesError,
                                loadSource = { cue -> withContext(Dispatchers.IO) { repo.sourceChunk(sessionClient, cue.session, cue.chunk) } },
                                onEndSession = endSession,
                                onSwitchPatient = {
                                    currentTab = "patients"
                                    currentScreen = "patients"
                                },
                                onTriggerTopic = { time, topic, date, quote ->
                                    surfacedQuotes.add(
                                        0,
                                        SurfacedQuote(
                                            timestamp = time,
                                            topicTrigger = topic,
                                            sessionDate = date,
                                            sessionTheme = topic,
                                            quote = quote
                                        )
                                    )
                                }
                            )
                        }

                        "summary" -> {
                            val p = sessionPatient ?: patients.firstOrNull()
                            if (p != null) {
                                val fmt = { n: Long -> "%02d".format(n) }
                                val dur = "${fmt(sessionSecs / 60)}:${fmt(sessionSecs % 60)}"
                                SummaryScreen(
                                    patient = p,
                                    durationFormatted = dur,
                                    canLeave = true,
                                    canDelete = true,
                                    onSaveToVault = {
                                        resetSession()
                                    },
                                    onDeleteSession = {
                                        pendingEnqueue = null
                                        val rid = pendingRecordingId ?: recordingId
                                        if (rid != null) viewModel.deleteRecording(rid)
                                        pendingRecordingId = null
                                        repo.deleteSession(sessionClient, sessionId)
                                        resetSession()
                                    }
                                )
                            }
                        }

                        "settings" -> {
                            SettingsScreen(
                                onLockVault = { if (queue.running) confirmLock = true else activity?.let(Vault::lock) },
                                onOpenDebugMlView = onShowLegacyDebugView
                            )
                        }
                    }
                }

                // Minimized Session Bar (floating docked above navigation bar)
                if (isSessionActive && sessionPatient != null && currentScreen != "live") {
                    MinimizedSessionBar(
                        patient = sessionPatient!!,
                        sessionSecs = sessionSecs,
                        isPaused = sessionPaused,
                        onTogglePause = togglePause,
                        onExpand = {
                            currentTab = "session"
                            currentScreen = "live"
                        }
                    )
                }

                // Bottom Navigation Bar
                MindVaultBottomNav(
                    selectedTab = currentTab,
                    onTabSelected = { tab ->
                        when (tab) {
                            "patients" -> {
                                currentTab = tab
                                currentScreen = "patients"
                            }
                            "session" -> {
                                if (isSessionActive && sessionPatient != null) {
                                    currentTab = tab
                                    currentScreen = "live"
                                } else {
                                    showChoosePatientDialog = true
                                }
                            }
                            "settings" -> {
                                currentTab = tab
                                currentScreen = "settings"
                            }
                        }
                    },
                    isSessionActive = isSessionActive
                )
            }
        }
    }
}
