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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import com.therapylocal.app.ui.design.LocalDockInset
import androidx.compose.ui.Modifier
import com.therapylocal.app.ui.design.LocalIdle
import com.therapylocal.app.ui.design.rememberIdleState
import com.therapylocal.app.ui.design.watchIdle
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
import com.therapylocal.app.session.EndedSession
import com.therapylocal.app.session.SessionController
import com.therapylocal.app.session.SessionPhase
import com.therapylocal.app.ui.data.ClientsViewModel
import com.therapylocal.app.ui.design.Palette
import com.therapylocal.app.ui.design.PaperTheme
import com.therapylocal.app.ui.design.paper
import com.therapylocal.app.ui.design.rememberChoiceState
import com.therapylocal.app.ui.design.rememberHaptics
import com.therapylocal.app.ui.logic.SessionLogic
import com.therapylocal.app.data.MindStore
import androidx.compose.runtime.produceState
import com.therapylocal.app.perf.PerfStats
import com.therapylocal.app.ui.logic.Evidence
import com.therapylocal.app.ui.logic.Progress
import com.therapylocal.app.ui.logic.RateMeter
import com.therapylocal.app.ui.models.Patient
import com.therapylocal.app.ui.motion.BoundsRef
import com.therapylocal.app.ui.motion.PageShift
import com.therapylocal.app.ui.motion.recede
import com.therapylocal.app.ui.motion.rememberSheetState
import com.therapylocal.app.ui.security.findFragmentActivity
import com.therapylocal.app.vault.Vault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The whole app after the gate: the client reel, with every other surface a page shifted in over it on the same paper (see [PageShift]).
 * There is no navigation stack to get lost in: home is always underneath, at most one sheet (plus a layer inside the profile) on top.
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
    var confirmLock by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var confirmShort by remember { mutableStateOf(false) }

    // The client's name travels between the reel and the top of their page while that page comes and goes. Only when both ends are known
    // and in sight (the page's title can be scrolled away); otherwise the page just shifts in like any other.
    val reduced = com.therapylocal.app.ui.design.LocalReducedMotion.current
    val titleRef = remember { BoundsRef() }
    val nameTravels = {
        !reduced && profileSheet.visible && profileId != null && refs.nameOf == profileId &&
            refs.name.rect != Rect.Zero && titleRef.rect != Rect.Zero && titleRef.present
    }
    refs.lent = nameTravels
    val nameMoving = { nameTravels() && profileSheet.progress.value < 0.999f }

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
    // Time left is measured, never guessed: how fast the running step has gone so far (and the download, from its bytes).
    val stepMeter = remember { RateMeter() }
    val downloadMeter = remember { RateMeter() }
    val now = System.currentTimeMillis()
    queue.progress?.let { p -> queue.running?.let { j -> stepMeter.see(j.id to p.phase, p.step.toLong(), p.steps.toLong(), now) } }
    if (setup.state == SetupState.Downloading && setup.totalBytes > 0) downloadMeter.see("download", setup.doneBytes, setup.totalBytes, now)
    // Measured on this phone: the latest model speed, what a passage takes, what an interview minute takes; and how long each waiting
    // session's interview is (from its recording), so its time can be worked out from that history.
    val perfVersion by PerfStats.version.collectAsStateWithLifecycle()
    val live by PerfStats.live.collectAsStateWithLifecycle()
    val llmModel = remember(perfVersion) { ModelSetup.gemma(ctx).id }
    val audioMs by produceState(emptyMap<String, Long>(), queue.sessions.map { it.sessionId }) {
        value = withContext(Dispatchers.IO) {
            runCatching { MindStore.get(ctx).recordings().filter { it.sessionId != null }.groupBy { it.sessionId!! }.mapValues { e -> e.value.sumOf { it.durationMs } } }.getOrDefault(emptyMap())
        }
    }
    val evidence = Evidence(
        stepLeftMs = stepMeter.leftMs(),
        live = live?.takeIf { now - it.atMs < 120_000 },
        perPassage = remember(perfVersion, llmModel) { PerfStats.perCall(llmModel, com.therapylocal.app.jobs.JobGraph.EXTRACT) },
        perMinute = remember(perfVersion, llmModel) { PerfStats.perMinute(llmModel) },
        measured = remember(perfVersion, llmModel) { PerfStats.sessionsMeasured(llmModel) },
        audioMs = audioMs,
    )
    val processing = remember(queue, finishing, evidence) { SessionLogic.processingByClient(queue, finishing, evidence) }
    LaunchedEffect(queue.jobs.count { it.status == com.therapylocal.app.data.JobStatus.Done }, session.ended) { clientsVm.refresh() }

    val statusOf = { p: Patient ->
        when {
            session.active && session.client == p.id -> ClientStatus(if (session.phase == SessionPhase.Paused) "Session paused" else "Recording now", Palette.Rec, busy = session.phase == SessionPhase.Recording)
            processing[p.id] != null -> processing.getValue(p.id).let { ClientStatus("Processing · ${it.stage}", busy = true, details = it.details) }
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
            details = if (setup.totalBytes > 0) listOf(
                "Downloading · ${ModelSetup.formatBytes(setup.doneBytes)} of ${ModelSetup.formatBytes(setup.totalBytes)} · " +
                    (downloadMeter.leftMs()?.let(Progress::left) ?: "measuring…"),
            ) else emptyList(),
        )
        SetupState.Checking -> ClientStatus("Getting ready", busy = true)
    }
    val clientName = { id: String -> clients.firstOrNull { it.id == id }?.name ?: id.replaceFirstChar { it.uppercase() } }
    val elapsed = { SessionController.state.value.clock.elapsedMs(SystemClock.elapsedRealtime()) }

    // ---- actions ----
    // Every page comes in from the right and goes back out to it, whatever opened it: the direction of a swipe is the direction of travel.
    val showProfile = { p: Patient -> if (profileId != p.id) { viewModel.clearSearch(); profileId = p.id } }
    val openProfile = { p: Patient ->
        showProfile(p)
        haptics.confirm()
        profileSheet.open()
    }
    val closeProfile = { profileSheet.close() }
    val openSessionPage = {
        haptics.confirm()
        sessionSheet.open()
    }
    val record = { p: Patient, _: BoundsRef ->
        withMic {
            if (session.active) openSessionPage()
            else if (SessionController.start(p.id, cues = vm.phase == Phase.Ready, name = p.name)) openSessionPage()
            else notice.show("The last session is still being saved. One moment.")
        }
    }
    val openSession = { openSessionPage() }
    val minimise = {
        haptics.press()
        // The session keeps recording: its card appears at the bottom of the page underneath as this one goes.
        sessionSheet.close()
    }
    val finish = {
        SessionController.finish()
        sessionSheet.close()
    }
    val addClient = { haptics.confirm(); addSheet.open() }
    val deleteClient = { p: Patient ->
        if (session.active && session.client == p.id) notice.show("${p.name} is being recorded. Finish the session first.")
        else {
            if (profileId == p.id) profileSheet.close()
            clientsVm.delete(p.id)
            notice.show("${p.name} deleted")
        }
    }
    // A sideways drag on home brings the next page in under the finger; let go unarmed and it goes back.
    val peek = { p: Patient?, dx: Float ->
        val s = if (p != null) profileSheet else addSheet
        if (!s.target) {
            if (p != null) showProfile(p)
            s.follow((-dx).coerceAtLeast(0f) / s.shiftPx)
        }
    }
    val unpeek = { p: Patient? -> (if (p != null) profileSheet else addSheet).let { if (!it.target) it.close() } }
    val lock: () -> Unit = { if (queue.running != null) confirmLock = true else activity?.let(Vault::lock) }
    val showSaved = { e: EndedSession ->
        haptics.confirm()
        notice.show("Saved to ${clientName(e.client)}'s record", "Discard" to { confirmDiscard = true }, durationMs = 8_000)
    }
    val discard = { e: EndedSession, message: String ->
        scope.launch {
            withContext(Dispatchers.IO) { clientsVm.repo.deleteSession(e.client, e.sessionId) }
            SessionController.dismissEnded()
            clientsVm.refresh()
            notice.show(message)
        }
        Unit
    }

    // The recording notification was tapped.
    LaunchedEffect(openSessionRequest) { if (openSessionRequest > 0 && session.active && !sessionSheet.target) openSession() }
    // The session was saved: say so, with a way to take it back. One too short to be a session goes at once; a short one is asked about.
    LaunchedEffect(session.ended) {
        val e = session.ended ?: return@LaunchedEffect
        when (SessionLogic.endOutcome(e.durationMs)) {
            SessionLogic.EndOutcome.Discard -> discard(e, "Under 10 seconds, so not saved")
            SessionLogic.EndOutcome.Ask -> confirmShort = true
            SessionLogic.EndOutcome.Save -> showSaved(e)
        }
    }
    LaunchedEffect(session.error) { session.error?.let { notice.show(it); if (!session.active) SessionController.clearError() } }

    // Every touch resets the idle timer the swipe hints wait on.
    val idle = rememberIdleState()
    val dockShown = session.active && !sessionSheet.target
    var dockPx by remember { mutableIntStateOf(0) }
    val dockInset = if (dockShown && dockPx > 0) with(density) { dockPx.toDp() } + 12.dp else 0.dp
    CompositionLocalProvider(LocalIdle provides idle, LocalDockInset provides dockInset) {
    Box(Modifier.fillMaxSize().watchIdle(idle)) {
        val overHome = listOf(profileSheet, addSheet, settingsSheet, sessionSheet)
        Home(
            clients = clients,
            statusOf = statusOf,
            session = session,
            setupLine = setupLine,
            focusClient = focus,
            onFocused = { focus = null },
            hint = hint,
            onLearned = { if (hint) { hint = false; prefs.edit().putBoolean(KNOWS_REEL, true).apply() } },
            bar = bar,
            refs = refs,
            actions = HomeActions(
                openProfile = openProfile,
                openSession = openSession,
                addClient = addClient,
                deleteClient = deleteClient,
                openSettings = { haptics.confirm(); settingsSheet.open() },
                peek = peek,
                unpeek = unpeek,
            ),
            modifier = Modifier.recede(overHome, reduced),
        )

        PageShift(profileSheet, onBack = closeProfile, under = listOf(sessionSheet)) {
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
                titleRef = titleRef,
                titleLent = nameMoving,
            )
        }
        // Over the page (its content fades), under everything opened from it.
        profileId?.let { id -> clients.firstOrNull { it.id == id } }?.let { p ->
            TravellingName(p.name, profileSheet, refs.name, titleRef, nameMoving)
        }

        PageShift(addSheet, onBack = { addSheet.close() }) {
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

        PageShift(settingsSheet, onBack = { settingsSheet.close() }) {
            SettingsSheet(settingsSheet, failedTotal, retryFailed, lock, onDeveloper, onClose = { settingsSheet.close() }, notice = notice)
        }

        // ---- the order of layers, bottom to top ----
        // home · pages (profile and what opens from it, add client, settings) · the running session's card · the session itself ·
        // notices · confirmation dialogs (their own windows, always on top). The card is above every page so a recording is never out of
        // sight anywhere in the app; only opening the session, or a dialog that needs an answer, covers it. It stays while the session's
        // page is still on its way in, dissolving with what is underneath, so it never blinks out.
        if (dockShown || (session.active && sessionSheet.progress.isRunning)) {
            SessionPill(
                session.client.takeIf { it.isNotEmpty() }?.let(clientName), session.phase == SessionPhase.Paused, elapsed, refs.pill, openSession,
                Modifier.align(Alignment.BottomCenter).recede(listOf(sessionSheet), reduced)
                    .fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 14.dp, vertical = 14.dp)
                    .onSizeChanged { if (dockShown) dockPx = it.height },
            )
        }
        if (!dockShown && dockPx != 0) {
            SideEffect { dockPx = 0 }
        }

        PageShift(sessionSheet, onBack = minimise) {
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
                if (e != null) discard(e, "Session discarded")
            },
            onDismiss = { confirmDiscard = false },
        )
    }
    // Back, or a tap beside it, keeps the session: nothing is thrown away without being asked to.
    if (confirmShort) {
        val e = session.ended
        ConfirmDialog(
            "That was quick.", "Do you want to save this session?", "Discard",
            onConfirm = {
                confirmShort = false
                if (e != null) discard(e, "Session discarded")
            },
            onDismiss = { confirmShort = false; e?.let(showSaved) },
            dismiss = "Save",
        )
    }
}

private const val KNOWS_REEL = "knows_reel"
