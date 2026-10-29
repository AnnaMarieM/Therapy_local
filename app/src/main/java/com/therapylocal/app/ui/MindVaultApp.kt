package com.therapylocal.app.ui

import androidx.activity.compose.BackHandler
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
import com.therapylocal.app.data.Ids
import com.therapylocal.app.Phase
import com.therapylocal.app.memory.Fact
import com.therapylocal.app.data.JobStatus
import com.therapylocal.app.jobs.Jobs
import com.therapylocal.app.ui.data.ClientsViewModel
import com.therapylocal.app.ui.components.ChoosePatientSessionDialog
import com.therapylocal.app.ui.components.MindVaultBottomNav
import com.therapylocal.app.ui.components.MinimizedSessionBar
import com.therapylocal.app.ui.logic.SessionLogic
import com.therapylocal.app.ui.models.Patient
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
        var sessionPaused by rememberSaveable { mutableStateOf(true) }
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
        val queue by Jobs.snapshot.collectAsStateWithLifecycle()
        val repo = clientsVm.repo
        val activity = LocalContext.current.findFragmentActivity()
        var confirmLock by remember { mutableStateOf(false) }

        // Elapsed time counts only while audio is really being recorded and not paused.
        LaunchedEffect(isSessionActive, vmState.recording, sessionPaused) {
            while (isSessionActive && vmState.recording && !sessionPaused) {
                delay(1000L)
                sessionSecs += 1
            }
        }
        LaunchedEffect(vmState.activeRecordingId) { vmState.activeRecordingId?.let { recordingId = it } }

        // Transcript chunks arrive as the pipeline stores them (the recordings list changes after each chunk).
        LaunchedEffect(recordingId, vmState.recordings) {
            recordingId?.let { rid -> transcript = viewModel.recordingChunks(rid).mapNotNull { it.text?.takeIf(String::isNotBlank) } }
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
        LaunchedEffect(queue.sessions.firstOrNull { it.sessionId == sessionId }?.done) { clientsVm.refresh() }
        LaunchedEffect(queue.jobs.count { it.status == JobStatus.Done }) { clientsVm.refresh() }

        // Keep selectedPatient up-to-date with the latest data in patients
        LaunchedEffect(patients) {
            selectedPatient?.let { sel ->
                patients.firstOrNull { it.id == sel.id }?.let { selectedPatient = it }
            }
        }

        val processingByClient = remember(queue, pendingEnqueue) {
            SessionLogic.processingByClient(queue, pendingEnqueue)
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
            sessionPaused = true
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
            sessionId = Ids.sessionId(client, now)
            sessionDate = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(now))
            sessionSecs = 0L
            surfacedQuotes.clear()
            transcript = emptyList()
            recordingId = null
            sessionEnded = false
            recordingStarted = false
            sessionPaused = true
            enqueued = false
            processNote = null
            isSessionActive = true
            currentTab = "session"
            currentScreen = "live"
        }

        val endSession = {
            if (recordingStarted) {
                viewModel.liveCues.stop()
                viewModel.stopRecording()
                pendingEnqueue = Triple(sessionClient, sessionId, sessionDate)
                pendingRecordingId = recordingId ?: vmState.activeRecordingId
                sessionEnded = true
                isSessionActive = false
                currentScreen = "summary"
            } else {
                resetSession()
            }
        }

        val togglePause = {
            val t = SessionLogic.togglePause(sessionPaused, recordingStarted)
            when (t.action) {
                SessionLogic.PauseAction.StartRecording -> {
                    if (vmState.phase == Phase.Ready) viewModel.liveCues.start(sessionClient)
                    viewModel.startRecording()
                }
                SessionLogic.PauseAction.ResumeRecording -> viewModel.resumeRecording()
                SessionLogic.PauseAction.PauseRecording -> viewModel.pauseRecording()
            }
            recordingStarted = t.recordingStarted
            sessionPaused = t.paused
        }

        BackHandler(enabled = showChoosePatientDialog || currentScreen != "patients" || currentTab != "patients") {
            when {
                showChoosePatientDialog -> showChoosePatientDialog = false
                currentScreen == "profile" -> currentScreen = "patients"
                currentScreen == "settings" -> {
                    currentTab = "patients"
                    currentScreen = "patients"
                }
                currentScreen == "live" -> {
                    currentTab = "patients"
                    currentScreen = "patients"
                }
                currentScreen == "summary" -> resetSession()
                currentTab != "patients" -> {
                    currentTab = "patients"
                    currentScreen = "patients"
                }
            }
        }

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
                                onAddPatient = { newP -> clientsVm.create(newP.name) },
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
                                    sessionPaused && sessionSecs == 0L -> "Ready · Tap Play to begin"
                                    sessionPaused -> "Paused"
                                    vmState.recording -> "Listening on device"
                                    else -> vmState.status.ifEmpty { "Ready" }
                                },
                                deferredNote = vmState.deferred && vmState.recording,
                                cues = cues,
                                cuesError = cuesError,
                                loadSource = { cue -> withContext(Dispatchers.IO) { repo.sourceChunk(cue.session, cue.chunk) } },
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
                                },
                                isPaused = sessionPaused,
                                onTogglePause = togglePause
                            )
                        }

                        "summary" -> {
                            val p = sessionPatient ?: patients.firstOrNull()
                            if (p != null) {
                                val dur = SessionLogic.formatMinSec(sessionSecs)
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
                                onLockVault = { if (queue.running != null) confirmLock = true else activity?.let(Vault::lock) },
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
                                if (SessionLogic.sessionTabTarget(isSessionActive, sessionPatient != null) == SessionLogic.SessionTabTarget.Live) {
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
