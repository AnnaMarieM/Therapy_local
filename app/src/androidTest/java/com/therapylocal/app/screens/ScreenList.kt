package com.therapylocal.app.screens

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import androidx.compose.ui.platform.testTag
import com.therapylocal.app.ui.design.LocalWindowSizeOverride
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import com.therapylocal.app.R
import com.therapylocal.app.SearchState
import com.therapylocal.app.data.MindStore
import com.therapylocal.app.data.NewFact
import com.therapylocal.app.data.NewMention
import com.therapylocal.app.data.Provenance
import com.therapylocal.app.data.SegmentRow
import com.therapylocal.app.data.WindowRow
import com.therapylocal.app.memory.Cue
import com.therapylocal.app.session.SessionClock
import com.therapylocal.app.session.SessionPhase
import com.therapylocal.app.session.SessionState
import com.therapylocal.app.ui.app.AddClientSheet
import com.therapylocal.app.ui.app.ChangePasswordSheet
import com.therapylocal.app.ui.app.ClientStatus
import com.therapylocal.app.ui.app.ConfirmDialog
import com.therapylocal.app.ui.app.Gate
import com.therapylocal.app.ui.app.Home
import com.therapylocal.app.ui.app.HomeActions
import com.therapylocal.app.ui.app.HomeRefs
import com.therapylocal.app.ui.app.MobileDataDialog
import com.therapylocal.app.ui.app.NoticeHost
import com.therapylocal.app.ui.app.NoticeState
import com.therapylocal.app.ui.app.ProfileSheet
import com.therapylocal.app.ui.app.ResetPassword
import com.therapylocal.app.ui.app.SearchActions
import com.therapylocal.app.ui.app.SessionSheet
import com.therapylocal.app.ui.app.SettingsSheet
import com.therapylocal.app.ui.data.ClientRepository
import com.therapylocal.app.ui.design.Adaptive
import com.therapylocal.app.ui.design.LocalIdle
import com.therapylocal.app.ui.design.PaperTheme
import com.therapylocal.app.ui.design.paper
import com.therapylocal.app.ui.design.rememberChoiceState
import com.therapylocal.app.ui.design.rememberIdleState
import com.therapylocal.app.ui.design.rememberTwoPane
import com.therapylocal.app.ui.design.windowWidthDp
import com.therapylocal.app.ui.motion.PageShift
import com.therapylocal.app.ui.motion.SheetState
import com.therapylocal.app.ui.motion.rememberSheetState
import com.therapylocal.app.vault.Vault
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assume.assumeTrue

/*
 * THE LIST OF SCREENS (AD-15). Every screen of the app is one entry in SCREENS below, in the order the comment on the pull request shows
 * them. A change that adds or changes a screen (a page, a sheet, a dialog, a state worth seeing) adds or updates its entry here, in the same
 * pull request; a screen missing from this list is missing from the review comment.
 *
 * Everything is drawn from FAKE data (seed() below) in the app's own composables, one per test case (ScreenTourTest). Nothing real is read:
 * the tour refuses to run where a vault already exists, and the vault it makes for the unlock screens is erased again.
 */

/** What an entry gets to work with. */
class Tour(val compose: ComposeContentTestRule, val context: Context, val repo: ClientRepository, val size: WindowSize) {
    /** Lets the animations run, then gives the display a moment to draw the last frame. The clock is driven by hand: some screens never go idle. */
    fun settle(ms: Long = 1_500) {
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(ms)
        Thread.sleep(400)
    }

    fun clickLabelled(label: String) {
        compose.onNode(SemanticsMatcher("click label '$label'") { it.config.getOrNull(SemanticsActions.OnClick)?.label == label }).performClick()
        settle(900)
    }

    fun clickText(text: String) {
        compose.onNode(hasText(text) and hasClickAction(), useUnmergedTree = false).performClick()
        settle(900)
    }

    /** Scrolls the layer on top (the last scrolling list on screen) until [m] is built. */
    fun scrollTo(m: SemanticsMatcher, first: Boolean = false) {
        val lists = compose.onAllNodes(hasScrollToNodeAction())
        (if (first) lists.onFirst() else lists.onLast()).performScrollToNode(m)
        settle(600)
    }

    fun str(id: Int, vararg args: Any): String = context.getString(id, *args)
}

/** One step of a flow in the comment: [flow] is its heading, [caption] one factual line. A [dialog] is its own window, so it is shown once at the real window size. */
class Screen(val id: String, val flow: String, val caption: String, val dialog: Boolean = false, val show: Tour.() -> Unit) {
    override fun toString() = id
}

/** A window size, as AdaptiveLayoutTest has them. Ordered narrow to wide, the order of the columns in the comment. */
class WindowSize(val name: String, val w: Int, val h: Int) {
    val key get() = if (w == 0) "device" else "${w}x$h"
    override fun toString() = key
}

val DEVICE = WindowSize("real window of the emulator", 0, 0)
val SIZES = listOf(
    WindowSize("freeform-narrow", 320, 480),
    WindowSize("phone", 360, 800),
    WindowSize("split-half", 400, 1280),
    WindowSize("tablet7", 600, 960),
    WindowSize("split-half-landscape", 640, 800),
    WindowSize("phone-landscape", 800, 360),
    WindowSize("tablet10", 800, 1280),
    WindowSize("tablet10-landscape", 1280, 800),
)

/** A page as the app opens it: the sheet slides in and settles. */
@Composable
private fun Page(content: @Composable (SheetState) -> Unit) {
    val sheet = rememberSheetState(windowWidthDp())
    LaunchedEffect(Unit) { sheet.open() }
    CompositionLocalProvider(LocalIdle provides rememberIdleState()) {
        Box(Modifier.fillMaxSize().paper()) { PageShift(sheet, onBack = null) { content(sheet) } }
    }
}

/**
 * The window of [Tour.size], the way AdaptiveLayoutTest makes it: the adaptive decisions read the override, and a box of that many dp stands in
 * for the window. A size larger than the emulator's screen is drawn at a lower density (pixels per dp) so it fits; the layout is the same.
 */
const val FRAME = "tour-frame"

private fun Tour.setContent(content: @Composable () -> Unit) {
    if (size === DEVICE) {
        compose.setContent { PaperTheme { content() } }
        return
    }
    val m = context.resources.displayMetrics
    val density = minOf(m.density, m.widthPixels * 0.98f / size.w, m.heightPixels * 0.88f / size.h)
    compose.setContent {
        CompositionLocalProvider(LocalWindowSizeOverride provides (size.w to size.h), LocalDensity provides Density(density, 1f)) {
            PaperTheme { Box(Modifier.size(size.w.dp, size.h.dp).testTag(FRAME)) { content() } }
        }
    }
}

private fun noSearch() = SearchActions(SearchState(), "Not in this tour", {}, { false }, {}, {})

private fun Tour.profile(search: SearchActions = noSearch(), processing: String? = null, status: ClientStatus? = ClientStatus("2 sessions, last Jan 8")) {
    val patient = repo.patient(CLIENT)
    setContent {
        Page { sheet ->
            ProfileSheet(
                sheet = sheet, patient = patient, repo = repo, status = status, processingSession = processing, search = search,
                sessionActive = false, requestMic = {}, onClose = {}, onRecord = {}, onDeleteClient = {}, onDeleteSession = {},
            )
        }
    }
    settle()
}

private fun Tour.home(clients: Boolean = true, session: SessionState = SessionState(), setupLine: ClientStatus? = null) {
    val list = if (clients) repo.patients() else emptyList()
    setContent {
        Box(Modifier.fillMaxSize().paper()) {
            Home(
                clients = list,
                statusOf = { p -> if (session.client == p.id) ClientStatus("Recording", busy = true) else ClientStatus("${p.sessionCount} sessions") },
                session = session, setupLine = setupLine, focusClient = null, onFocused = {}, hint = false, onLearned = {},
                bar = rememberChoiceState(), refs = remember { HomeRefs() },
                actions = HomeActions(openProfile = {}, openSession = {}, addClient = {}, deleteClient = {}, openSettings = {}),
            )
        }
    }
    settle()
}

private fun Tour.session(phase: SessionPhase, withCue: Boolean) {
    val cue = Cue("1", CONTENT, HEADING, S1, 3, 0.6f)
    setContent {
        Page { sheet ->
            SessionSheet(
                sheet = sheet,
                session = SessionState(phase, CLIENT, "${CLIENT}_live", 0, SessionClock.started(0), cuesOn = withCue),
                clientName = "Ana", elapsed = { 1_325_000L }, level = remember { MutableStateFlow(0.45f) }, cues = if (withCue) listOf(cue) else emptyList(),
                cuesError = null, repo = repo, onPause = {}, onResume = {}, onMinimise = {}, onFinish = {}, onHint = {},
            )
        }
    }
    settle(2_500)
}

private fun Tour.settings(then: Tour.() -> Unit = {}) {
    setContent { Page { sheet -> SettingsSheet(sheet, 0, {}, {}, null, {}, remember { NoticeState() }, {}) } }
    settle()
    then()
}

/** Walks the first-run Gate to the page whose [label] is the next button to press, [steps] times. */
private fun Tour.gate(vararg continueLabels: String) {
    setContent { Box(Modifier.fillMaxSize().paper()) { Gate(onOpen = {}) } }
    settle()
    continueLabels.forEach { clickLabelled(it) }
}

/** The unlock page needs a vault; this makes one on the empty emulator and always erases it again. */
private fun Tour.withVault(show: Tour.() -> Unit) {
    check(!Vault.isSetUp(context)) { "a vault exists: the tour never touches one" }
    try {
        Vault.create(context, PASSWORD.toCharArray())
        Vault.relock()
        show()
    } finally {
        Vault.erase(context)
    }
}

val SCREENS: List<Screen> = listOf(
    Screen("gate-language", "First launch", "The first screen: the app language, the phone's preselected") { gate() },
    Screen("gate-welcome-1", "First launch", "Welcome, page 1 of 4") { gate(str(R.string.gate_continue)) },
    Screen("gate-welcome-2", "First launch", "Welcome, page 2 of 4") { gate(str(R.string.gate_continue), str(R.string.gate_continue)) },
    Screen("gate-welcome-3", "First launch", "Welcome, page 3 of 4") { gate(*Array(3) { str(R.string.gate_continue) }) },
    Screen("gate-welcome-4", "First launch", "Welcome, page 4 of 4") { gate(*Array(4) { str(R.string.gate_continue) }) },
    Screen("gate-permissions", "First launch", "The microphone and notification permissions are explained") { gate(*Array(5) { str(R.string.gate_continue) }) },
    Screen("gate-speech-language", "First launch", "Pick the language clients are assumed to speak") { gate(*Array(6) { str(R.string.gate_continue) }) },
    Screen("gate-create-password", "First launch", "Create the vault password") { gate(*Array(6) { str(R.string.gate_continue) }, str(R.string.gate_set_password)) },
    Screen("gate-create-password-filled", "First launch", "Create the vault password, both fields filled in") {
        gate(*Array(6) { str(R.string.gate_continue) }, str(R.string.gate_set_password))
        val fields = compose.onAllNodes(hasSetTextAction())
        fields[0].performTextInput(PASSWORD)
        fields[1].performTextInput(PASSWORD)
        settle()
    },
    Screen("gate-unlock", "Unlock", "Later launches: enter the password") { withVault { setContent { Box(Modifier.fillMaxSize().paper()) { Gate(onOpen = {}) } }; settle() } },
    Screen("gate-reset-password", "Unlock", "Forgot password: erase and set a new one") {
        setContent { Box(Modifier.fillMaxSize().paper()) { ResetPassword(onBack = {}, onDone = {}) } }
        settle()
    },
    Screen("home-empty", "Add a client", "Home with no clients yet") { home(clients = false) },
    Screen("add-client", "Add a client", "Open \"New client\": first name and surname") {
        setContent { Page { sheet -> AddClientSheet(sheet, onCancel = {}, onAdd = { _, _, _ -> }) } }
        settle()
    },
    Screen("add-client-filled", "Add a client", "Name typed: the shortened name is previewed") {
        setContent { Page { sheet -> AddClientSheet(sheet, onCancel = {}, onAdd = { _, _, _ -> }) } }
        settle()
        compose.onNode(hasContentDescription("First name")).performTextInput("Dana")
        compose.onNode(hasContentDescription("Surname")).performTextInput("Lewis")
        settle()
    },
    Screen("notice", "Add a client", "A notice appears over the page") {
        val notice = NoticeState()
        setContent { Box(Modifier.fillMaxSize().paper()) { NoticeHost(notice) } }
        notice.show("Ana added")
        settle(800)
    },
    Screen("home", "Home", "Home: the client reel, names in order with their status") { home() },
    Screen("home-model-download", "Home", "Home while the models download, with progress") {
        home(setupLine = ClientStatus("Getting ready: 1.4 GB of 3.2 GB", busy = true, details = listOf("Downloading models: 1.4 GB of 3.2 GB, about 6 min left")))
    },
    Screen("profile", "A client", "Open a client: facts by category") { profile() },
    Screen("profile-sessions", "A client", "Scroll down: the client's sessions, newest first") {
        profile()
        scrollTo(hasContentDescription("Session of", substring = true), first = true)
    },
    Screen("profile-processing", "A client", "A session is being processed: status on the page") {
        profile(processing = S2, status = ClientStatus("Processing: reading the transcript", busy = true))
    },
    Screen("home-two-pane", "A client", "Wide windows: the client list stays beside the page") {
        assumeTrue("two panes only on a wide window", Adaptive.twoPane(size.w, size.h))
        val patient = repo.patient(CLIENT)
        val list = Adaptive.listPaneDp(size.w)
        setContent {
            Box(Modifier.fillMaxSize().paper()) {
                Home(
                    clients = repo.patients(), statusOf = { ClientStatus("${it.sessionCount} sessions") }, session = SessionState(), setupLine = null, focusClient = null,
                    onFocused = {}, hint = false, onLearned = {}, bar = rememberChoiceState(), refs = remember { HomeRefs() },
                    actions = HomeActions(openProfile = {}, openSession = {}, addClient = {}, deleteClient = {}, openSettings = {}),
                    modifier = Modifier.width(list.dp).fillMaxHeight(),
                )
                Box(Modifier.padding(start = list.dp)) {
                    Page { sheet ->
                        ProfileSheet(
                            sheet = sheet, patient = patient, repo = repo, status = ClientStatus("3 sessions, last Jan 15"), processingSession = null, search = noSearch(),
                            sessionActive = false, requestMic = {}, onClose = {}, onRecord = {}, onDeleteClient = {}, onDeleteSession = {},
                        )
                    }
                }
            }
        }
        settle()
    },
    Screen("profile-fact", "Facts and transcripts", "Tap a fact: where it was first said and where it came up again") {
        profile()
        clickText(HEADING)
        scrollTo(hasText("CAME UP AGAIN", substring = true))
    },
    Screen("profile-transcript", "Facts and transcripts", "Tap a session: its transcript") {
        profile()
        scrollTo(hasContentDescription("Session of", substring = true), first = true)
        compose.onAllNodes(hasContentDescription("Session of", substring = true)).onFirst().performClick()
        settle()
    },
    Screen("profile-search", "Facts and transcripts", "Search a client's memory: text search hits") {
        val patient = repo.patient(CLIENT)
        setContent {
            var state by remember { mutableStateOf(SearchState()) }
            Page { sheet ->
                ProfileSheet(
                    sheet = sheet, patient = patient, repo = repo, status = null, processingSession = null,
                    search = SearchActions(state, "Not in this tour", { q -> state = SearchState(hits = repo.search(CLIENT, QUERY_VEC, q)) }, { false }, {}, { state = SearchState() }),
                    sessionActive = false, requestMic = {}, onClose = {}, onRecord = {}, onDeleteClient = {}, onDeleteSession = {},
                )
            }
        }
        settle()
        compose.onNode(hasSetTextAction()).performTextInput("running")
        compose.onNode(hasSetTextAction()).performImeAction()
        settle(2_000)
    },
    Screen("home-recording", "Record a session", "Recording started: the session card stays at the bottom of home") {
        home(session = SessionState(SessionPhase.Recording, CLIENT, "${CLIENT}_live", 0, SessionClock.started(0), cuesOn = true, clientName = "Ana"))
    },
    Screen("session-recording", "Record a session", "The session page: clock, level meter and a cue from memory") { session(SessionPhase.Recording, withCue = true) },
    Screen("session-recording-quiet", "Record a session", "The session page when there is nothing to cue") { session(SessionPhase.Recording, withCue = false) },
    Screen("session-paused", "Record a session", "Paused session") { session(SessionPhase.Paused, withCue = false) },
    Screen("settings", "Settings", "Open settings: language, display, privacy") { settings() },
    Screen("settings-bottom", "Settings", "Scroll down: notifications, technical settings, about, sign out") {
        settings { scrollTo(hasText(str(R.string.settings_sign_out)), first = true) }
    },
    Screen("settings-technical", "Settings", "Technical settings: models and hardware") {
        settings { scrollTo(hasText("Technical settings"), first = true); clickText("Technical settings") }
    },
    Screen("settings-technical-cues", "Settings", "Technical settings: the cue sliders") {
        settings {
            scrollTo(hasText("Technical settings"), first = true); clickText("Technical settings")
            scrollTo(hasContentDescription("Listens back"))
        }
    },
    Screen("settings-change-password", "Settings", "Change the password") {
        setContent { Page { sheet -> ChangePasswordSheet(sheet, onClose = {}, notice = remember { NoticeState() }) } }
        settle()
    },
    Screen("dialog-sign-out", "Dialogs", "Sign out while work is still running asks first", dialog = true) {
        setContent {
            Box(Modifier.fillMaxSize().paper()) {
                ConfirmDialog(
                    stringResource(R.string.mindvault_lock_title, stringResource(R.string.app_name)), stringResource(R.string.mindvault_lock_body),
                    stringResource(R.string.mindvault_lock_confirm), onConfirm = {}, onDismiss = {}, destructive = false,
                )
            }
        }
        settle()
    },
    Screen("dialog-mobile-data", "Dialogs", "Model download over mobile data asks first", dialog = true) {
        setContent { Box(Modifier.fillMaxSize().paper()) { MobileDataDialog("3.2 GB", onDownload = {}, onWait = {}, onNotNow = {}) } }
        settle()
    },

)

const val PASSWORD = "12345678"
const val CLIENT = "ana"
private const val S1 = "ana_s1"
private const val S2 = "ana_s2"
private const val S3 = "ana_s3"
private const val HEADING = "Runs again"
private const val CONTENT = "Ana took up running again in May."
private val QUERY_VEC = floatArrayOf(1f, 0f, 0f, 0f)
private const val DAY_1 = 1_767_225_600_000L // 2026-01-01
private const val WEEK = 7 * 24 * 3_600_000L

private val LINES_1 = listOf(
    "Good to see you again.", "How was the month?", "Busy, but better.", "I took up running again in May.",
    "That sounds like a change.", "It clears my head before work.", "And the evenings?", "Quieter now, mostly.",
)
private val LINES_2 = listOf("Where would you like to start?", "With the mornings, I think.", "I am still running three mornings a week.", "What keeps you going?")
private val LINES_3 = listOf("How has the week been?", "My sister visited, which was nice.", "The new job is a lot, but I am learning.", "Sleep has been better.")

/** Fake data only: invented first names with an initial, invented sentences. The database is a throw-away file with a test password. */
fun seed(context: Context, db: String): Pair<MindStore, ClientRepository> {
    context.deleteDatabase(db)
    val store = MindStore(context, db, "screen-tour-password".toByteArray())
    val repo = ClientRepository(context) { store }
    val prov = Provenance("", Provenance.NO_MAP, "tour", "fake")
    fun transcript(session: String, lines: List<String>) = store.appendTranscript(session, lines.mapIndexed { i, t -> SegmentRow(i, i * 5_000L, i * 5_000L + 5_000, t) })

    store.createClient(CLIENT, "Ana")
    store.createSession(S1, CLIENT, DAY_1)
    store.createSession(S2, CLIENT, DAY_1 + WEEK)
    store.createSession(S3, CLIENT, DAY_1 + 2 * WEEK)
    transcript(S1, LINES_1); transcript(S2, LINES_2); transcript(S3, LINES_3)
    store.replaceWindows(S1, listOf(WindowRow(0, 0, LINES_1.lastIndex, LINES_1.joinToString(" "), QUERY_VEC, "fake")), prov)
    val running = store.insertFact(NewFact(CLIENT, S1, HEADING, CONTENT, LINES_1[3], 3, null, prov, category = "Health"))
    store.addMention(NewMention(running, S2, LINES_2[2], 2))
    store.insertFact(NewFact(CLIENT, S3, "Sister visited", "Ana's sister stayed for a week and it went well.", LINES_3[1], 1, null, prov, category = "Family"))
    store.insertFact(NewFact(CLIENT, S3, "New job", "Ana started a new job and is still finding her feet.", LINES_3[2], 2, null, prov, category = "Work"))
    store.insertFact(NewFact(CLIENT, S3, "Sleeping better", "Sleep has improved over the last weeks.", LINES_3[3], 3, null, prov, category = "Health"))

    store.createClient("ben", "Ben C.")
    store.createSession("ben_s1", "ben", DAY_1 + 3 * WEEK)
    transcript("ben_s1", LINES_2)
    store.createClient("clara", "Clara D.")
    store.createSession("clara_s1", "clara", DAY_1 + 4 * WEEK)
    transcript("clara_s1", LINES_3)
    store.createClient("zoe", "Zoe K.")
    return store to repo
}
