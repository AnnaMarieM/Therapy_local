package com.therapylocal.app.ui.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.therapylocal.app.MainViewModel
import com.therapylocal.app.R
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
import com.therapylocal.app.ui.design.PaneDivider
import com.therapylocal.app.ui.design.PaperTheme
import com.therapylocal.app.ui.design.paper
import com.therapylocal.app.ui.design.rememberChoiceState
import com.therapylocal.app.ui.design.rememberHaptics
import com.therapylocal.app.ui.logic.SessionLogic
import com.therapylocal.app.ui.logic.uiText
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
import com.therapylocal.app.i18n.LocaleStore

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
            if (!unlocked || !passed) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Box(Modifier.fillMaxHeight().widthIn(max = com.therapylocal.app.ui.design.Adaptive.ReadingWidth).fillMaxWidth()) { Gate(onOpen = { passed = true }) }
            }
            else Main(remember { viewModel() }, openSession, onDeveloper)
        }
    }
}

@Composable
private fun Main(viewModel: MainViewModel, openSessionRequest: Int, onDeveloper: (() -> Unit)?) {
    val ctx = LocalContext.current
    val config = LocalConfiguration.current
    val t = remember(ctx, config) { ctx.uiText() }
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
    val winW = com.therapylocal.app.ui.design.windowWidthDp()
    val twoPane = com.therapylocal.app.ui.design.rememberTwoPane()
    val listW = if (twoPane) com.therapylocal.app.ui.design.Adaptive.listPaneDp(winW) else 0
    val pageW = winW - listW
    val profileSheet = rememberSheetState(pageW)
    val sessionSheet = rememberSheetState(pageW)
    val settingsSheet = rememberSheetState(pageW)
    val addSheet = rememberSheetState(pageW)
    var profileId by rememberSaveable { mutableStateOf<String?>(null) }
    var focus by remember { mutableStateOf<String?>(null) }
    var hint by remember { mutableStateOf(!prefs.getBoolean(KNOWS_REEL, false)) }
    var confirmLock by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var confirmShort by remember { mutableStateOf(false) }

    // Sessions that can be read again in the app's language, offered after a language switch or from the settings.
    var reprocess by remember { mutableStateOf<Pair<com.therapylocal.app.jobs.Reprocess.Plan, Long?>?>(null) }
    val offerReprocess = { asked: Boolean ->
        scope.launch {
            val offer = withContext(Dispatchers.IO) { com.therapylocal.app.jobs.Reprocess.plan(ctx).let { it to it.estimateMs(ctx) } }
            if (offer.first.sessions.isNotEmpty()) reprocess = offer else if (asked) notice.show(t.str(R.string.reprocess_up_to_date))
        }
        Unit
    }
    LaunchedEffect(Unit) { if (LocaleStore.takeReprocessOffer(ctx)) offerReprocess(false) }

    // The client's name travels between the reel and the top of their page while that page comes and goes. Only when both ends are known
    // and in sight (the page's title can be scrolled away); otherwise the page just shifts in like any other.
    val reduced = com.therapylocal.app.ui.design.LocalReducedMotion.current
    val titleRef = remember { BoundsRef() }
    val nameTravels = {
        !reduced && !twoPane && profileSheet.visible && profileId != null && refs.nameOf == profileId &&
            refs.name.rect != Rect.Zero && titleRef.rect != Rect.Zero && titleRef.present
    }
    refs.lent = nameTravels
    val nameMoving = { nameTravels() && profileSheet.progress.value < 0.999f }

    val micGranted = { ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED }
    var afterMic by remember { mutableStateOf<(() -> Unit)?>(null) }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) afterMic?.invoke()
        else notice.show(t.str(R.string.mindvault_mic_needed))
        afterMic = null
    }
    val withMic = { run: () -> Unit -> if (micGranted()) run() else { afterMic = run; askMic.launch(Manifest.permission.RECORD_AUDIO) } }

    // Failed sessions are offered again instead of silently disappearing from every list.
    val failedByClient = remember(queue) { queue.sessions.filter { it.failed > 0 }.groupBy { it.clientId }.mapValues { it.value.size } }
    val failedTotal = failedByClient.values.sum()
    val retryFailed = {
        scope.launch(Dispatchers.IO) { Jobs.queue(ctx).retryFailed(); MemoryService.resumePending(ctx) }
        notice.show(t.str(R.string.mindvault_trying_again))
    }
    val finishing = if (session.phase == SessionPhase.Finishing) Triple(session.client, session.sessionId, t.str(R.string.mindvault_today)) else null
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
            // A session whose recording is gone (read again from its transcript) is as long as its transcript.
            runCatching {
                val store = MindStore.get(ctx)
                val recorded = store.recordings().filter { it.sessionId != null }.groupBy { it.sessionId!! }.mapValues { e -> e.value.sumOf { it.durationMs } }
                queue.sessions.associate { s -> s.sessionId to (recorded[s.sessionId] ?: store.transcriptLengthMs(s.sessionId)) }
            }.getOrDefault(emptyMap())
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
    val processing = remember(queue, finishing, evidence, t) { SessionLogic.processingByClient(t, queue, finishing, evidence) }
    LaunchedEffect(queue.jobs.count { it.status == com.therapylocal.app.data.JobStatus.Done }, session.ended) { clientsVm.refresh() }

    val statusOf = { p: Patient ->
        when {
            session.active && session.client == p.id -> ClientStatus(t.str(if (session.phase == SessionPhase.Paused) R.string.mindvault_status_paused else R.string.mindvault_status_recording), Palette.Rec, busy = session.phase == SessionPhase.Recording)
            processing[p.id] != null -> processing.getValue(p.id).let { ClientStatus(t.str(R.string.mindvault_status_processing, it.stage), busy = true, details = it.details) }
            (failedByClient[p.id] ?: 0) > 0 -> ClientStatus(t.str(R.string.mindvault_status_failed), Palette.Rec, onTap = retryFailed)
            p.sessionCount == 0 -> ClientStatus(t.str(R.string.mindvault_status_no_sessions))
            else -> ClientStatus(t.plural(R.plurals.mindvault_status_sessions, p.sessionCount, p.sessionCount, p.lastSession))
        }
    }
    val setupLine = when (setup.state) {
        SetupState.Ready -> null
        SetupState.Failed -> ClientStatus(t.str(R.string.mindvault_setup_paused), Palette.Rec) { ModelSetup.retry(ctx) }
        SetupState.Downloading -> ClientStatus(
            if (setup.totalBytes > 0) t.str(R.string.mindvault_setup_getting_ready_bytes, ModelSetup.formatBytes(ctx, setup.doneBytes), ModelSetup.formatBytes(ctx, setup.totalBytes))
            else setup.message ?: t.str(R.string.mindvault_setup_getting_ready),
            busy = true,
            details = if (setup.totalBytes > 0) listOf(
                t.str(
                    R.string.mindvault_setup_downloading, ModelSetup.formatBytes(ctx, setup.doneBytes), ModelSetup.formatBytes(ctx, setup.totalBytes),
                    downloadMeter.leftMs()?.let { Progress.left(t, it) } ?: t.str(R.string.progress_measuring),
                ),
            ) else emptyList(),
        )
        SetupState.Checking -> ClientStatus(t.str(R.string.mindvault_setup_getting_ready), busy = true)
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
            else notice.show(t.str(R.string.mindvault_still_saving))
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
        if (session.active && session.client == p.id) notice.show(t.str(R.string.mindvault_client_recording, p.name))
        else {
            if (profileId == p.id) profileSheet.close()
            clientsVm.delete(p.id)
            notice.show(t.str(R.string.mindvault_client_deleted, p.name))
        }
    }
    // A sideways drag on home brings the next page in under the finger; let go unarmed and it goes back.
    val peek = { p: Patient?, dx: Float ->
        val s = if (p != null) profileSheet else addSheet
        if (!s.target) {
            if (p != null) showProfile(p)
            // The pull is measured against the list the finger is on, not the (much wider) pane the page opens in.
            val travel = (if (twoPane) listW else winW) * density.density * com.therapylocal.app.ui.motion.SHIFT_SHARE
            s.follow((-dx).coerceAtLeast(0f) / travel)
        }
    }
    val unpeek = { p: Patient? -> (if (p != null) profileSheet else addSheet).let { if (!it.target) it.close() } }
    val lock: () -> Unit = { if (queue.running != null) confirmLock = true else activity?.let(Vault::lock) }
    val showSaved = { e: EndedSession ->
        haptics.confirm()
        notice.show(t.str(R.string.mindvault_saved_to, clientName(e.client)), t.str(R.string.mindvault_discard) to { confirmDiscard = true }, durationMs = 8_000)
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
            SessionLogic.EndOutcome.Discard -> discard(e, t.str(R.string.mindvault_too_short))
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
        // Wide windows: the client list stays on the left and every page opens in the pane on the right.
        val pageMod = if (twoPane) Modifier.padding(start = listW.dp) else Modifier
        if (twoPane) {
            Box(Modifier.padding(start = listW.dp).fillMaxSize().paper())
        }
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
            modifier = if (twoPane) Modifier.width(listW.dp).fillMaxHeight() else Modifier.recede(overHome, reduced),
        )

        PageShift(profileSheet, onBack = closeProfile, under = listOf(sessionSheet), modifier = pageMod) {
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
                        session.active -> t.str(R.string.mindvault_voice_off_recording)
                        vm.phase != Phase.Ready -> t.str(R.string.mindvault_voice_off_setup)
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
                onLanguage = { l -> scope.launch { withContext(Dispatchers.IO) { clientsVm.repo.setLanguage(p.id, l) }; clientsVm.refresh() } },
                onDeleteClient = { deleteClient(p) },
                onDeleteSession = { s ->
                    scope.launch {
                        withContext(Dispatchers.IO) { clientsVm.repo.deleteSession(p.id, s.id) }
                        clientsVm.refresh()
                        notice.show(t.str(R.string.mindvault_session_deleted))
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

        PageShift(addSheet, onBack = { addSheet.close() }, modifier = pageMod) {
            val existing = remember(clients) { clientsVm.repo.disambiguationTargets() }
            AddClientSheet(addSheet, onCancel = { addSheet.close() }, onAdd = { first, last, language ->
                scope.launch {
                    val p = withContext(Dispatchers.IO) { runCatching { clientsVm.repo.createClient(first, last, language) }.getOrNull() }
                    if (p == null) { notice.show(t.str(R.string.mindvault_client_add_failed)); return@launch }
                    haptics.confirm()
                    clientsVm.refresh()
                    focus = p.id
                    addSheet.close()
                    notice.show(t.str(R.string.mindvault_client_added, p.name))
                }
            }, existingClients = existing)
        }

        PageShift(settingsSheet, onBack = { settingsSheet.close() }, modifier = pageMod) {
            SettingsSheet(settingsSheet, failedTotal, retryFailed, lock, onDeveloper, onClose = { settingsSheet.close() }, notice = notice, onReprocess = { offerReprocess(true) })
        }

        // ---- the order of layers, bottom to top ----
        // home · pages (profile and what opens from it, add client, settings) · the running session's card · the session itself ·
        // notices · confirmation dialogs (their own windows, always on top). The card is above every page so a recording is never out of
        // sight anywhere in the app; only opening the session, or a dialog that needs an answer, covers it. It stays while the session's
        // page is still on its way in, dissolving with what is underneath, so it never blinks out.
        if (dockShown || (session.active && sessionSheet.progress.isRunning)) {
            SessionPill(
                session.client.takeIf { it.isNotEmpty() }?.let(clientName), session.phase == SessionPhase.Paused, elapsed, refs.pill, openSession,
                Modifier.align(if (twoPane) Alignment.BottomStart else Alignment.BottomCenter)
                    .let { if (twoPane) it.width(listW.dp) else it.recede(listOf(sessionSheet), reduced).fillMaxWidth() }.navigationBarsPadding().imePadding().padding(horizontal = 14.dp, vertical = 14.dp)
                    .onSizeChanged { if (dockShown) dockPx = it.height },
            )
        }
        if (!dockShown && dockPx != 0) {
            SideEffect { dockPx = 0 }
        }

        PageShift(sessionSheet, onBack = minimise, modifier = pageMod) {
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

        if (twoPane) PaneDivider(Modifier.offset(x = (listW - 1).dp))

        // A notice is a toast, not a bar: on a wide window it keeps the width of a page.
        Box(Modifier.align(Alignment.TopCenter).widthIn(max = com.therapylocal.app.ui.design.Adaptive.ReadingWidth)) { NoticeHost(notice) }
    }
    }

    if (confirmLock) ConfirmDialog(
        stringResource(R.string.mindvault_lock_title, stringResource(R.string.app_name)), stringResource(R.string.mindvault_lock_body), stringResource(R.string.mindvault_lock_confirm),
        onConfirm = { confirmLock = false; activity?.let(Vault::lock) }, onDismiss = { confirmLock = false }, destructive = false,
    )
    reprocess?.let { (plan, estimate) ->
        ReprocessDialog(
            t, plan, estimate,
            onStart = {
                reprocess = null
                scope.launch(Dispatchers.IO) { com.therapylocal.app.jobs.Reprocess.start(ctx, plan) }
                notice.show(t.str(R.string.reprocess_started))
            },
            onDismiss = { reprocess = null },
        )
    }
    if (confirmDiscard) {
        val e = session.ended
        ConfirmDialog(
            stringResource(R.string.mindvault_discard_title), stringResource(R.string.mindvault_discard_body), stringResource(R.string.mindvault_discard),
            onConfirm = {
                confirmDiscard = false
                if (e != null) discard(e, t.str(R.string.mindvault_session_discarded))
            },
            onDismiss = { confirmDiscard = false },
        )
    }
    // Back, or a tap beside it, keeps the session: nothing is thrown away without being asked to.
    if (confirmShort) {
        val e = session.ended
        ConfirmDialog(
            stringResource(R.string.mindvault_quick_title), stringResource(R.string.mindvault_quick_body), stringResource(R.string.mindvault_discard),
            onConfirm = {
                confirmShort = false
                if (e != null) discard(e, t.str(R.string.mindvault_session_discarded))
            },
            onDismiss = { confirmShort = false; e?.let(showSaved) },
            dismiss = stringResource(R.string.mindvault_save),
        )
    }
}

private const val KNOWS_REEL = "knows_reel"
