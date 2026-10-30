package com.therapylocal.app.ui.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.therapylocal.app.MainViewModel
import com.therapylocal.app.Phase
import com.therapylocal.app.jobs.Jobs
import com.therapylocal.app.memory.MemoryService
import com.therapylocal.app.models.ModelSetup
import com.therapylocal.app.models.SetupState
import com.therapylocal.app.session.SessionController
import com.therapylocal.app.session.SessionPhase
import com.therapylocal.app.ui.data.ClientsViewModel
import com.therapylocal.app.ui.design.Palette
import com.therapylocal.app.ui.design.PaperTheme
import com.therapylocal.app.ui.design.paper
import com.therapylocal.app.ui.design.rememberChoiceState
import com.therapylocal.app.ui.design.rememberHaptics
import com.therapylocal.app.ui.logic.SessionLogic
import com.therapylocal.app.ui.models.Patient
import com.therapylocal.app.ui.motion.BoundsRef
import com.therapylocal.app.ui.motion.ContainerTransform
import com.therapylocal.app.ui.motion.Origin
import com.therapylocal.app.ui.motion.rememberSheetState
import com.therapylocal.app.ui.security.findFragmentActivity
import com.therapylocal.app.vault.Vault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The whole app after the gate: the client reel, with every other surface a sheet that grows out of what opened it and folds back into
 * it. There is no navigation stack to get lost in: home is always underneath, at most one sheet (plus a layer inside the profile) on top.
 *
 * [openSession] is bumped when the recording notification is tapped. [viewModel] is only read once the vault is open: creating it opens the
 * encrypted store.
 */
@Composable
fun MindVault(viewModel: () -> MainViewModel, openSession: Int, onDeveloper: (() -> Unit)?) {
    PaperTheme {
        val unlocked by Vault.unlocked.collectAsStateWithLifecycle()
        var passed by rememberSaveable { mutableStateOf(false) }
        Box(Modifier.fillMaxSize().paper()) {
            if (!unlocked || !passed) Gate(onOpen = { passed = true })
            else Main(remember { viewModel() }, openSession, onDeveloper)
        }
    }
}

@Composable
private fun Main(viewModel: MainViewModel, openSessionRequest: Int, onDeveloper: (() -> Unit)?) {
    val ctx = LocalContext.current
    val activity = remember(ctx) { ctx.findFragmentActivity() }
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    val density = LocalDensity.current
    val prefs = remember { ctx.getSharedPreferences("settings", Context.MODE_PRIVATE) }

    val clientsVm: ClientsViewModel = viewModel()
    val clients by clientsVm.patients.collectAsStateWithLifecycle()
    val vm by viewModel.state.collectAsStateWithLifecycle()
    val search by viewModel.search.collectAsStateWithLifecycle()
    val session by SessionController.state.collectAsStateWithLifecycle()
    val cues by SessionController.cues.collectAsStateWithLifecycle()
    val cuesError by SessionController.liveCues.error.collectAsStateWithLifecycle()
    val queue by Jobs.snapshot.collectAsStateWithLifecycle()
    val setup by ModelSetup.progress.collectAsStateWithLifecycle()

    val notice = remember { NoticeState() }
    val bar = rememberChoiceState()
    val refs = remember { HomeRefs() }
    val profileSheet = rememberSheetState()
    val sessionSheet = rememberSheetState()
    val settingsSheet = rememberSheetState()
    val addSheet = rememberSheetState()
    var profileId by rememberSaveable { mutableStateOf<String?>(null) }
    var focus by remember { mutableStateOf<String?>(null) }
    var hint by remember { mutableStateOf(!prefs.getBoolean(KNOWS_REEL, false)) }
    var sessionFrom by remember { mutableStateOf<BoundsRef?>(null) }
    var confirmLock by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }

    val r30 = with(density) { 30.dp.toPx() }
    val r24 = with(density) { 24.dp.toPx() }
    val origin = { ref: BoundsRef, color: androidx.compose.ui.graphics.Color, radius: Float -> Origin(ref.rect, color, radius) }

    val micGranted = { ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED }
    var afterMic by remember { mutableStateOf<(() -> Unit)?>(null) }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) afterMic?.invoke()
        else notice.show("Recording needs the microphone. Allow it in the phone's settings.")
        afterMic = null
    }
    val withMic = { run: () -> Unit -> if (micGranted()) run() else { afterMic = run; askMic.launch(Manifest.permission.RECORD_AUDIO) } }

    // Failed sessions are offered again instead of silently disappearing from every list.
    val failedByClient = remember(queue) { queue.sessions.filter { it.failed > 0 }.groupBy { it.clientId }.mapValues { it.value.size } }
    val failedTotal = failedByClient.values.sum()
    val retryFailed = {
        scope.launch(Dispatchers.IO) { Jobs.queue(ctx).retryFailed(); MemoryService.resumePending(ctx) }
        notice.show("Trying again")
    }
    val finishing = if (session.phase == SessionPhase.Finishing) Triple(session.client, session.sessionId, "Today") else null
    val processing = remember(queue, finishing) { SessionLogic.processingByClient(queue, finishing) }
    LaunchedEffect(queue.jobs.count { it.status == com.therapylocal.app.data.JobStatus.Done }, session.ended) { clientsVm.refresh() }

    val statusOf = { p: Patient ->
        when {
            session.active && session.client == p.id -> ClientStatus(if (session.phase == SessionPhase.Paused) "Session paused" else "Recording now", Palette.Rec, busy = session.phase == SessionPhase.Recording)
            processing[p.id] != null -> ClientStatus("Processing · ${processing.getValue(p.id).stage}", busy = true)
            (failedByClient[p.id] ?: 0) > 0 -> ClientStatus("A session could not be processed · Retry", Palette.Rec, onTap = retryFailed)
            p.sessionCount == 0 -> ClientStatus("No sessions yet")
            else -> ClientStatus("${p.sessionCount} ${if (p.sessionCount == 1) "session" else "sessions"} · last ${p.lastSession}")
        }
    }
    val setupLine = when (setup.state) {
        SetupState.Ready -> null
        SetupState.Failed -> ClientStatus("Setup paused · Try again", Palette.Rec) { ModelSetup.retry(ctx) }
        SetupState.Downloading -> ClientStatus(
            if (setup.totalBytes > 0) "Getting ready · ${ModelSetup.formatBytes(setup.doneBytes)} of ${ModelSetup.formatBytes(setup.totalBytes)}" else setup.message ?: "Getting ready",
            busy = true,
        )
        SetupState.Checking -> ClientStatus("Getting ready", busy = true)
    }
    val clientName = { id: String -> clients.firstOrNull { it.id == id }?.name ?: id.replaceFirstChar { it.uppercase() } }
    val elapsed = { SessionController.state.value.clock.elapsedMs(SystemClock.elapsedRealtime()) }

    // ---- actions ----
    val openProfile = { p: Patient ->
        viewModel.clearSearch()
        profileId = p.id
        haptics.confirm()
        profileSheet.open(origin(refs.left, Palette.Tonal, r30))
    }
    val closeProfile = { profileSheet.close(origin(refs.left, Palette.Tonal, r30)) }
    val openSessionFrom = { from: BoundsRef, color: androidx.compose.ui.graphics.Color, radius: Float ->
        sessionFrom = from
        haptics.confirm()
        sessionSheet.open(Origin(from.rect, color, radius))
    }
    val record = { p: Patient, from: BoundsRef ->
        withMic {
            if (session.active) openSessionFrom(from, Palette.Rec, r30)
            else if (SessionController.start(p.id, cues = vm.phase == Phase.Ready)) openSessionFrom(from, Palette.Rec, r30)
            else notice.show("The last session is still being saved. One moment.")
        }
    }
    val openSession = {
        if (refs.pill.rect != Rect.Zero && !profileSheet.target) openSessionFrom(refs.pill, Palette.Ink, r24)
        else openSessionFrom(refs.right, Palette.Rec, r30)
    }
    val minimise = {
        haptics.press()
        // Opened from the profile: fold back into its Record button. From home: into the pill, which appears as the sheet lands.
        if (profileSheet.target) sessionSheet.close(Origin(sessionFrom?.rect ?: refs.right.rect, Palette.Rec, r30))
        else sessionSheet.close(Origin(if (refs.pill.rect != Rect.Zero) refs.pill.rect else refs.right.rect, Palette.Ink, r24))
    }
    val finish = {
        SessionController.finish()
        sessionSheet.close(Origin(sessionFrom?.rect ?: refs.right.rect, Palette.Rec, r30))
    }
    val addClient = { haptics.confirm(); addSheet.open(origin(refs.right, Palette.Ink, r30)) }
    val deleteClient = { p: Patient ->
        if (session.active && session.client == p.id) notice.show("${p.name} is being recorded. Finish the session first.")
        else {
            if (profileId == p.id) profileSheet.close(origin(refs.left, Palette.Tonal, r30))
            clientsVm.delete(p.id)
            notice.show("${p.name} deleted")
        }
    }
    val lock: () -> Unit = { if (queue.running != null) confirmLock = true else activity?.let(Vault::lock) }

    // The recording notification was tapped.
    LaunchedEffect(openSessionRequest) { if (openSessionRequest > 0 && session.active && !sessionSheet.target) openSession() }
    // The pill moves (it appears, the list changes): a folding sheet keeps aiming at it.
    LaunchedEffect(refs.pill.rect) { if (!sessionSheet.target && sessionSheet.visible && !profileSheet.target && refs.pill.rect != Rect.Zero) sessionSheet.retarget(Origin(refs.pill.rect, Palette.Ink, r24)) }
    // The session was saved: say so, with a way to take it back.
    LaunchedEffect(session.ended) {
        val e = session.ended ?: return@LaunchedEffect
        haptics.confirm()
        notice.show("Saved to ${clientName(e.client)}'s record", "Discard" to { confirmDiscard = true }, durationMs = 8_000)
    }
    LaunchedEffect(session.error) { session.error?.let { notice.show(it); if (!session.active) SessionController.clearError() } }

    Box(Modifier.fillMaxSize()) {
        Home(
            clients = clients,
            statusOf = statusOf,
            session = session,
            sessionClientName = session.client.takeIf { it.isNotEmpty() }?.let(clientName),
            sessionElapsed = elapsed,
            topCue = cues.firstOrNull(),
            setupLine = setupLine,
            focusClient = focus,
            onFocused = { focus = null },
            hint = hint,
            onLearned = { if (hint) { hint = false; prefs.edit().putBoolean(KNOWS_REEL, true).apply() } },
            bar = bar,
            refs = refs,
            actions = HomeActions(
                openProfile = openProfile,
                record = { p -> record(p, refs.right) },
                openSession = openSession,
                addClient = addClient,
                deleteClient = deleteClient,
                openSettings = { haptics.confirm(); settingsSheet.open(origin(refs.gear, Palette.Paper, with(density) { 26.dp.toPx() })) },
            ),
            pillVisible = !sessionSheet.target,
        )

        ContainerTransform(profileSheet, Palette.Paper, onBack = closeProfile) {
            val p = clients.firstOrNull { it.id == profileId }
            if (p != null) ProfileSheet(
                sheet = profileSheet,
                patient = p,
                repo = clientsVm.repo,
                status = statusOf(p),
                processingSession = processing[p.id]?.sessionId,
                search = SearchActions(
                    state = search,
                    voiceDisabled = when {
                        session.active -> "Voice search is off while a session records"
                        vm.phase != Phase.Ready -> "Voice search works once setup has finished"
                        else -> null
                    },
                    text = { q -> viewModel.searchText(p.id, q) },
                    voiceStart = { viewModel.startVoiceSearch(p.id) },
                    voiceStop = viewModel::stopVoiceSearch,
                    clear = viewModel::clearSearch,
                ),
                sessionActive = session.active,
                requestMic = { askMic.launch(Manifest.permission.RECORD_AUDIO) },
                onClose = closeProfile,
                onRecord = { from -> record(p, from) },
                onDeleteClient = { deleteClient(p) },
                onDeleteSession = { s ->
                    scope.launch {
                        withContext(Dispatchers.IO) { clientsVm.repo.deleteSession(p.id, s.id) }
                        clientsVm.refresh()
                        notice.show("Session deleted")
                    }
                },
            )
        }

        ContainerTransform(addSheet, Palette.Paper, onBack = { addSheet.close() }) {
            AddClientSheet(addSheet, onCancel = { addSheet.close() }, onAdd = { name ->
                scope.launch {
                    val p = withContext(Dispatchers.IO) { runCatching { clientsVm.repo.createClient(name) }.getOrNull() }
                    if (p == null) { notice.show("That client could not be added"); return@launch }
                    haptics.confirm()
                    clientsVm.refresh()
                    focus = p.id
                    addSheet.close()
                    notice.show("${p.name} added")
                }
            })
        }

        ContainerTransform(settingsSheet, Palette.Paper, onBack = { settingsSheet.close() }) {
            SettingsSheet(settingsSheet, failedTotal, retryFailed, lock, onDeveloper, onClose = { settingsSheet.close() }, notice = notice)
        }

        ContainerTransform(sessionSheet, Palette.Paper, onBack = minimise) {
            SessionSheet(
                sheet = sessionSheet,
                session = session,
                clientName = clientName(session.client),
                elapsed = elapsed,
                level = SessionController.level,
                cues = cues,
                cuesError = cuesError,
                repo = clientsVm.repo,
                onPause = { haptics.press(); SessionController.pause() },
                onResume = { haptics.press(); SessionController.resume() },
                onMinimise = minimise,
                onFinish = finish,
                onHint = { notice.show(it) },
            )
        }

        NoticeHost(notice, Modifier.align(Alignment.TopCenter))
    }

    if (confirmLock) ConfirmDialog(
        "Lock MindVault?", "A session is being processed. It pauses now and carries on after you unlock.", "Lock",
        onConfirm = { confirmLock = false; activity?.let(Vault::lock) }, onDismiss = { confirmLock = false }, destructive = false,
    )
    if (confirmDiscard) {
        val e = session.ended
        ConfirmDialog(
            "Discard this session?", "Its recording is deleted from the phone and it will not be processed. This cannot be undone.", "Discard",
            onConfirm = {
                confirmDiscard = false
                if (e != null) scope.launch {
                    withContext(Dispatchers.IO) { clientsVm.repo.deleteSession(e.client, e.sessionId) }
                    SessionController.dismissEnded()
                    clientsVm.refresh()
                    notice.show("Session discarded")
                }
            },
            onDismiss = { confirmDiscard = false },
        )
    }
}

private const val KNOWS_REEL = "knows_reel"
