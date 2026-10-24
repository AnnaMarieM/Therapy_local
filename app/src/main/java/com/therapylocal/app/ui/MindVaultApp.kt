package com.therapylocal.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import com.therapylocal.app.ui.components.MindVaultBottomNav
import com.therapylocal.app.ui.components.MinimizedSessionBar
import com.therapylocal.app.ui.models.Patient
import com.therapylocal.app.ui.models.SurfacedQuote
import com.therapylocal.app.ui.screens.LiveSessionScreen
import com.therapylocal.app.ui.screens.PatientsScreen
import com.therapylocal.app.ui.screens.ProfileScreen
import com.therapylocal.app.ui.screens.SettingsScreen
import com.therapylocal.app.ui.screens.SummaryScreen
import com.therapylocal.app.ui.screens.UnlockScreen
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
        var isUnlocked by rememberSaveable { mutableStateOf(false) }
        var currentTab by rememberSaveable { mutableStateOf("patients") }
        var currentScreen by rememberSaveable { mutableStateOf("patients") }

        // Clients come from the on-device memory store (mock data only when it is empty and demo mode is off).
        val clientsVm: ClientsViewModel = viewModel()
        val patients by clientsVm.patients.collectAsStateWithLifecycle()
        var selectedPatient by remember { mutableStateOf<Patient?>(null) }

        // Persistent Session State
        var isSessionActive by rememberSaveable { mutableStateOf(false) }
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

        // Elapsed time counts only while audio is really being recorded.
        LaunchedEffect(isSessionActive, vmState.recording) {
            while (isSessionActive && vmState.recording) {
                delay(1000L)
                sessionSecs += 1
            }
        }

        // The recording starts as soon as the models are ready (they may still be loading when the therapist opens the session).
        LaunchedEffect(isSessionActive, sessionEnded, recordingStarted, vmState.phase) {
            if (isSessionActive && !sessionEnded && !recordingStarted && vmState.phase == Phase.Ready) {
                recordingStarted = true
                viewModel.liveCues.start(sessionClient)
                viewModel.startRecording()
            }
        }
        LaunchedEffect(vmState.activeRecordingId) { vmState.activeRecordingId?.let { recordingId = it } }

        // Transcript chunks arrive as the pipeline stores them (the recordings list changes after each chunk).
        LaunchedEffect(recordingId, vmState.recordings) {
            recordingId?.let { rid -> transcript = viewModel.recordingChunks(rid).map { it.text }.filter { it.isNotBlank() } }
        }

        // After End Session: once the recording and every chunk are finished, hand the merged transcript to the memory pipeline.
        LaunchedEffect(sessionEnded, enqueued, recordingId, vmState.recording, vmState.activeRecordingId, vmState.status, vmState.error) {
            if (!sessionEnded || enqueued || processNote != null) return@LaunchedEffect
            val rid = recordingId
            if (rid == null) {
                if (!recordingStarted || (!vmState.recording && vmState.error != null)) processNote = "No audio was recorded."
                return@LaunchedEffect
            }
            if (vmState.recording || vmState.activeRecordingId != null || vmState.status != "Done") return@LaunchedEffect
            val text = viewModel.recordingTranscript(rid)
            val ok = withContext(Dispatchers.IO) { repo.processSession(sessionClient, sessionId, sessionDate, text) }
            if (ok) {
                enqueued = true
                viewModel.deleteRecording(rid)
            } else {
                processNote = "The transcript was empty, nothing to process."
            }
        }
        LaunchedEffect(queue.jobs.firstOrNull { it.id == sessionId }?.status) { clientsVm.refresh() }

        if (!isUnlocked) {
            UnlockScreen(onUnlock = {
                isUnlocked = true
                currentTab = "patients"
                currentScreen = "patients"
            })
            return@MindVaultTheme
        }

        val resetSession = {
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
            val client = repo.ensureClient(p)
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
            sessionEnded = true
            currentScreen = "summary"
        }

        val togglePause = { }

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
                                onSelectPatient = { p ->
                                    selectedPatient = p
                                    currentScreen = "profile"
                                },
                                onAddPatient = { newP -> clientsVm.create(newP.name, newP.age.takeIf { it > 0 }, newP.presenting) },
                                onDeletePatient = { p -> clientsVm.delete(p.id) },
                                onOpenSettings = {
                                    currentTab = "settings"
                                    currentScreen = "settings"
                                }
                            )
                        }

                        "profile" -> {
                            val activeP = selectedPatient ?: patients.first()
                            ProfileScreen(
                                patient = activeP,
                                isSessionActive = isSessionActive && sessionPatient?.id == activeP.id,
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

                        "live" -> {
                            val p = sessionPatient ?: selectedPatient ?: patients.first()
                            LiveSessionScreen(
                                patient = p,
                                sessionSecs = sessionSecs,
                                surfacedQuotes = surfacedQuotes,
                                transcript = transcript,
                                statusLine = when {
                                    vmState.error != null -> vmState.error
                                    vmState.phase == Phase.Failed -> "Models failed to load"
                                    vmState.phase != Phase.Ready -> "Preparing models: ${vmState.prepareMsg}"
                                    vmState.recording -> null
                                    else -> vmState.status.ifEmpty { "Starting" }
                                },
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
                                val job = queue.jobs.firstOrNull { it.id == sessionId }
                                var facts by remember(sessionId) { mutableStateOf(emptyList<Fact>()) }
                                var observations by remember(sessionId) { mutableStateOf("") }
                                LaunchedEffect(sessionId, job?.status, job?.added, job?.stage) {
                                    facts = withContext(Dispatchers.IO) { repo.sessionFacts(sessionClient, sessionId) }
                                }
                                val processing = processNote ?: when {
                                    !enqueued -> "Finishing the recording..."
                                    job == null -> "Queued"
                                    job.status == JobStatus.Failed -> "Processing failed: ${job.error ?: "unknown error"}"
                                    job.status == JobStatus.Done -> "Done: ${job.added} new facts"
                                    job.status == JobStatus.Running -> "Processing: ${job.stage}"
                                    else -> "Queued"
                                }
                                SummaryScreen(
                                    patient = p,
                                    durationFormatted = dur,
                                    processing = processing,
                                    facts = facts,
                                    observations = observations,
                                    onObservationsChange = { observations = it },
                                    canLeave = enqueued || processNote != null,
                                    canDelete = (enqueued || processNote != null) && job?.status != JobStatus.Running,
                                    onSaveToVault = {
                                        if (observations.isNotBlank()) repo.saveObservations(sessionClient, sessionId, observations)
                                        resetSession()
                                    },
                                    onDeleteSession = {
                                        repo.deleteSession(sessionClient, sessionId)
                                        resetSession()
                                    }
                                )
                            }
                        }

                        "settings" -> {
                            SettingsScreen(
                                onLockVault = {
                                    isUnlocked = false
                                },
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
                        currentTab = tab
                        when (tab) {
                            "patients" -> currentScreen = "patients"
                            "session" -> {
                                if (isSessionActive && sessionPatient != null) {
                                    currentScreen = "live"
                                } else if (selectedPatient != null) {
                                    startSession(selectedPatient!!)
                                } else {
                                    patients.firstOrNull()?.let(startSession)
                                }
                            }
                            "settings" -> currentScreen = "settings"
                        }
                    },
                    isSessionActive = isSessionActive
                )
            }
        }
    }
}
