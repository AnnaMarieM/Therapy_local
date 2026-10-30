package com.therapylocal.app.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.therapylocal.app.SearchState
import com.therapylocal.app.config.LiveCueSettings
import com.therapylocal.app.data.MindStore
import com.therapylocal.app.data.NewFact
import com.therapylocal.app.data.NewMention
import com.therapylocal.app.data.SegmentRow
import com.therapylocal.app.data.WindowRow
import com.therapylocal.app.memory.Cue
import com.therapylocal.app.session.SessionClock
import com.therapylocal.app.session.SessionPhase
import com.therapylocal.app.session.SessionState
import com.therapylocal.app.ui.app.AddClientSheet
import com.therapylocal.app.ui.app.ClientStatus
import com.therapylocal.app.ui.app.Home
import com.therapylocal.app.ui.app.HomeActions
import com.therapylocal.app.ui.app.HomeRefs
import com.therapylocal.app.ui.app.NoticeState
import com.therapylocal.app.ui.app.ProfileSheet
import com.therapylocal.app.ui.app.SearchActions
import com.therapylocal.app.ui.app.SessionSheet
import com.therapylocal.app.ui.app.SettingsSheet
import com.therapylocal.app.ui.data.ClientRepository
import com.therapylocal.app.ui.design.PaperTheme
import com.therapylocal.app.ui.design.rememberChoiceState
import com.therapylocal.app.ui.motion.rememberSheetState
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The screens on a real encrypted database seeded with one fact said in two sessions. Every swipe has a tap or accessibility equivalent,
 * and these tests go through those: a fact opens where it was said, a session opens its transcript, search finds both, a slider saves, the
 * reel offers its names in order, a client can be added, and a session can be finished without holding (TalkBack's action).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class ProfileUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var store: MindStore
    private lateinit var repo: ClientRepository

    @Before
    fun seed() {
        context.deleteDatabase(DB)
        store = MindStore(context, DB, "ui-test-password".toByteArray())
        repo = ClientRepository(context) { store }
        store.createClient(CLIENT, "Ana")
        store.createSession(S1, CLIENT, DAY_1)
        store.createSession(S2, CLIENT, DAY_1 + WEEK)
        store.replaceSegments(S1, LINES_1.mapIndexed { i, t -> SegmentRow(i, i * 5_000L, i * 5_000L + 5_000, t) })
        store.replaceSegments(S2, LINES_2.mapIndexed { i, t -> SegmentRow(i, i * 5_000L, i * 5_000L + 5_000, t) })
        store.replaceWindows(S1, listOf(WindowRow(0, 0, LINES_1.lastIndex, LINES_1.joinToString(" "), QUERY_VEC, "fake")))
        val fact = store.insertFact(NewFact(CLIENT, S1, HEADING, CONTENT, LINES_1[3], 3, embedding = null, llmModel = "fake", promptVersion = "test"))
        store.addMention(NewMention(fact, S2, LINES_2[2], 2))
    }

    @After
    fun erase() {
        store.close()
        context.deleteDatabase(DB)
        prefs().edit().remove(LiveCueSettings.KEY_LOOKBACK_S).commit()
    }

    private fun profile(search: SearchActions = noSearch()) {
        val patient = repo.patient(CLIENT)
        compose.setContent {
            PaperTheme {
                ProfileSheet(
                    sheet = rememberSheetState(), patient = patient, repo = repo, status = null, processingSession = null, search = search,
                    sessionActive = false, requestMic = {}, onClose = {}, onRecord = {}, onDeleteClient = {}, onDeleteSession = {},
                )
            }
        }
    }

    private fun noSearch() = SearchActions(SearchState(), "Not in this test", {}, { false }, {}, {})

    @Test
    fun tappingAFactShowsWhereItWasFirstSaidAndWhereItCameUpAgain() {
        profile()
        compose.onNode(hasText("CAME UP 2 TIMES")).assertExists()
        compose.onNode(hasText(HEADING) and hasClickAction()).performClick()

        compose.waitUntilAtLeastOneExists(hasText("FIRST HEARD", substring = true), TIMEOUT_MS)
        // The page is a lazy list: on a short screen its lower parts are only built once scrolled to, as a reader would.
        seeInLayer(hasText(LINES_1[3]))
        // The transcript around the first occurrence: three segments either side of the evidence, no further.
        seeInLayer(hasText(LINES_1[0]))
        seeInLayer(hasText(LINES_1[6]))
        compose.onNode(hasText(LINES_1[7])).assertDoesNotExist()
        seeInLayer(hasText("CAME UP AGAIN", substring = true))
        seeInLayer(hasText(LINES_2[2]))
    }

    /** Scrolls the layer on top (the last scrolling list on screen) until [m] is built, then checks it is there. */
    private fun seeInLayer(m: SemanticsMatcher) {
        compose.onAllNodes(hasScrollToNodeAction()).onLast().performScrollToNode(m)
        compose.onNode(m).assertExists()
    }

    @Test
    fun aSessionOpensItsTranscript() {
        profile()
        // Newest first: the second session is on top.
        compose.onAllNodes(hasContentDescription("Session of", substring = true)).onFirst().performClick()

        compose.waitUntilAtLeastOneExists(hasText(LINES_2[0]), TIMEOUT_MS)
        compose.onNode(hasText(LINES_2.last())).assertExists()
        compose.onNode(hasText(LINES_1[0])).assertDoesNotExist()
    }

    @Test
    fun textSearchFindsTheTranscriptAndTheFact() {
        val patient = repo.patient(CLIENT)
        compose.setContent {
            var state by remember { mutableStateOf(SearchState()) }
            PaperTheme {
                ProfileSheet(
                    sheet = rememberSheetState(), patient = patient, repo = repo, status = null, processingSession = null,
                    // The app embeds the query first (MainViewModel.searchText); a fixed vector stands in for the embedder.
                    search = SearchActions(state, "Not in this test", { q -> state = SearchState(hits = repo.search(CLIENT, QUERY_VEC, q)) }, { false }, {}, { state = SearchState() }),
                    sessionActive = false, requestMic = {}, onClose = {}, onRecord = {}, onDeleteClient = {}, onDeleteSession = {},
                )
            }
        }
        compose.onNode(hasSetTextAction()).performTextInput("running")
        compose.onNode(hasSetTextAction()).performImeAction()

        compose.waitUntilAtLeastOneExists(hasText("FROM THE TRANSCRIPT"), TIMEOUT_MS)
        compose.onNode(hasText(LINES_1[0], substring = true)).assertExists()
        compose.onNode(hasText(HEADING.uppercase())).assertExists()
        compose.onNode(hasText("NOTHING FOUND")).assertDoesNotExist()
    }

    @Test
    fun theLookbackSliderSavesItsSetting() {
        prefs().edit().remove(LiveCueSettings.KEY_LOOKBACK_S).commit()
        compose.setContent { PaperTheme { SettingsSheet(rememberSheetState(), 0, {}, {}, null, {}, remember { NoticeState() }) } }

        compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasContentDescription("Listens back"))
        compose.onNode(hasContentDescription("Listens back")).performSemanticsAction(SemanticsActions.SetProgress) { it(LOOKBACK.toFloat()) }

        compose.onNode(hasText("$LOOKBACK S")).assertExists()
        assertEquals(LOOKBACK, prefs().getInt(LiveCueSettings.KEY_LOOKBACK_S, -1))
    }

    @Test
    fun theReelOffersNamesAlphabeticallyAndOpensTheSelectedOne() {
        store.createClient("zoe", "Zoe K.")
        store.createClient("ben", "Ben C.")
        val clients = repo.patients() // Ana, Zoe K., Ben C. in creation order
        var opened: String? = null
        compose.setContent {
            PaperTheme {
                Home(
                    clients = clients, statusOf = { ClientStatus("${it.sessionCount} sessions") }, session = SessionState(),
                    setupLine = null, focusClient = null, onFocused = {}, hint = false, onLearned = {},
                    bar = rememberChoiceState(), refs = remember { HomeRefs() },
                    actions = HomeActions(openProfile = { opened = it.name }, openSession = {}, addClient = {}, deleteClient = {}, openSettings = {}),
                )
            }
        }
        compose.onNode(hasContentDescription("New client")).assertExists()
        // The one way in from home, said in full; it can be tapped as well as swiped.
        compose.onNode(hasContentDescription("Swipe from right to left to open Ana") and hasClickAction()).performClick()
        compose.runOnIdle { assertEquals("Ana", opened) } // first alphabetically, whatever the input order
    }

    @Test
    fun addingAClientKeepsOnlyTheInitialOfTheSurname() {
        var added: String? = null
        compose.setContent { PaperTheme { AddClientSheet(rememberSheetState(), onCancel = {}, onAdd = { added = it }) } }
        compose.onNode(hasContentDescription("First name")).performTextInput("george")
        compose.onNode(hasContentDescription("Surname · only the initial is kept")).performTextInput("miller")
        compose.onNode(hasText("George M.")).assertExists()
        compose.onNode(hasContentDescription("Swipe from right to left to add George M.") and hasClickAction()).performClick()
        compose.runOnIdle { assertEquals("George M.", added) }
    }

    @Test
    fun aSessionCanBeFinishedWithoutHoldingAndShowsTheCue() {
        var finished = false
        val cue = Cue("1", CONTENT, HEADING, S1, 3, 0.6f)
        // The level meter and the clock run on every frame while recording, so the screen is never idle: step the clock by hand.
        compose.mainClock.autoAdvance = false
        compose.setContent {
            PaperTheme {
                SessionSheet(
                    sheet = rememberSheetState(),
                    session = SessionState(SessionPhase.Recording, CLIENT, "ana_x", 0, SessionClock.started(0), cuesOn = true),
                    clientName = "Ana", elapsed = { 65_000L }, level = remember { MutableStateFlow(0.4f) }, cues = listOf(cue), cuesError = null, repo = repo,
                    onPause = {}, onResume = {}, onMinimise = {}, onFinish = { finished = true }, onHint = {},
                )
            }
        }
        compose.mainClock.advanceTimeBy(2_000)
        compose.onNode(hasText(CONTENT)).assertExists()
        compose.onNode(hasText("Runs ")).assertExists() // the heading, word by word
        compose.onNode(hasText("01:05")).assertExists()
        val node = compose.onNode(hasContentDescription("Hold to finish the session")).fetchSemanticsNode()
        compose.runOnUiThread { node.config[SemanticsActions.CustomActions].first { it.label == "Finish session" }.action() }
        compose.mainClock.advanceTimeBy(100)
        assertEquals(true, finished)
    }

    private fun prefs() = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private companion object {
        const val DB = "ui-test.db"
        const val CLIENT = "ana"
        const val S1 = "ana_s1"
        const val S2 = "ana_s2"
        const val DAY_1 = 1_767_225_600_000L // 2026-01-01
        const val WEEK = 7 * 24 * 3_600_000L
        const val TIMEOUT_MS = 5_000L
        const val LOOKBACK = 30 // not the default, so only a save can put it there
        const val HEADING = "Runs again"
        const val CONTENT = "Ana took up running again in May."
        val QUERY_VEC = floatArrayOf(1f, 0f, 0f, 0f)
        val LINES_1 = listOf(
            "Good to see you again.",
            "How was the month?",
            "Busy, but better.",
            "I took up running again in May.",
            "That sounds like a change.",
            "It clears my head before work.",
            "And the evenings?",
            "Quieter now, mostly.",
        )
        val LINES_2 = listOf(
            "Where would you like to start?",
            "With the mornings, I think.",
            "I am still running three mornings a week.",
            "What keeps you going?",
        )
    }
}
