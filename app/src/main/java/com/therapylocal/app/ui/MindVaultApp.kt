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
import com.therapylocal.app.ui.data.ClientsViewModel
import com.therapylocal.app.ui.components.MindVaultBottomNav
import com.therapylocal.app.ui.components.MinimizedSessionBar
import com.therapylocal.app.ui.models.Patient
import com.therapylocal.app.ui.models.SessionRecord
import com.therapylocal.app.ui.models.SurfacedQuote
import com.therapylocal.app.ui.screens.LiveSessionScreen
import com.therapylocal.app.ui.screens.PatientsScreen
import com.therapylocal.app.ui.screens.ProfileScreen
import com.therapylocal.app.ui.screens.SettingsScreen
import com.therapylocal.app.ui.screens.SummaryScreen
import com.therapylocal.app.ui.screens.UnlockScreen
import com.therapylocal.app.ui.theme.MindVaultTheme
import com.therapylocal.app.ui.theme.WarmBg
import kotlinx.coroutines.delay

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
        var sessionPaused by rememberSaveable { mutableStateOf(false) }
        val surfacedQuotes = remember { mutableStateListOf<SurfacedQuote>() }

        // Timer effect: Ticks continuously in background across all tabs
        LaunchedEffect(isSessionActive, sessionPaused) {
            while (isSessionActive && !sessionPaused) {
                delay(1000L)
                sessionSecs += 1
            }
        }

        if (!isUnlocked) {
            UnlockScreen(onUnlock = {
                isUnlocked = true
                currentTab = "patients"
                currentScreen = "patients"
            })
            return@MindVaultTheme
        }

        val startSession = { p: Patient ->
            if (!hasMicPermission()) requestMicPermission()
            sessionPatient = p
            sessionSecs = 0L
            sessionPaused = false
            surfacedQuotes.clear()
            isSessionActive = true
            currentTab = "session"
            currentScreen = "live"
            try {
                viewModel.startRecording()
            } catch (_: Exception) {}
        }

        val endSession = {
            try {
                viewModel.stopRecording()
            } catch (_: Exception) {}
            sessionPaused = true
            currentScreen = "summary"
        }

        val togglePause = {
            sessionPaused = !sessionPaused
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
                                onSelectPatient = { p ->
                                    selectedPatient = p
                                    currentScreen = "profile"
                                },
                                onAddPatient = { newP ->
                                    clientsVm.addLocal(newP)
                                },
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
                                isPaused = sessionPaused,
                                surfacedQuotes = surfacedQuotes,
                                onTogglePause = togglePause,
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
                            val p = sessionPatient ?: patients.first()
                            val fmt = { n: Long -> "%02d".format(n) }
                            val dur = "${fmt(sessionSecs / 60)}:${fmt(sessionSecs % 60)}"

                            SummaryScreen(
                                patient = p,
                                durationFormatted = dur,
                                onSaveToVault = {
                                    // Append session record
                                    val idx = patients.indexOfFirst { it.id == p.id }
                                    if (idx >= 0) {
                                        val updatedSessions = listOf(
                                            SessionRecord(
                                                date = "Oct 24, 2026",
                                                duration = "$dur min",
                                                summary = "Completed on-device session with real-time quote surfacing.",
                                                topics = listOf("Clinical Intake", "Boundary Setting"),
                                                verbatimQuotes = surfacedQuotes.map { it.quote }
                                            )
                                        ) + p.sessions
                                        clientsVm.replaceInMemory(
                                            p.copy(
                                                sessions = updatedSessions,
                                                sessionCount = p.sessionCount + 1,
                                                lastSession = "Oct 24, 2026"
                                            )
                                        )
                                    }
                                    isSessionActive = false
                                    sessionPatient = null
                                    currentTab = "patients"
                                    currentScreen = "patients"
                                }
                            )
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
