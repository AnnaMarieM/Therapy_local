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
import com.therapylocal.app.ui.components.ClientSearch
import com.therapylocal.app.ui.data.ClientRepository
import com.therapylocal.app.ui.screens.ProfileScreen
import com.therapylocal.app.ui.screens.SettingsScreen
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The profile on a real encrypted database seeded with one fact said in two sessions: the fact's sheet shows both occurrences with the
 * transcript around each, text search finds the transcript and the fact, and a live-cue slider saves its setting.
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

    @Test
    fun tappingAFactShowsWhereItWasFirstSaidAndWhereItCameUpAgain() {
        val patient = repo.patient(CLIENT)
        compose.setContent {
            ProfileScreen(
                patient = patient, isSessionActive = false, repo = repo, onBack = {}, onDeletePatient = {}, onStartSession = {},
                onReturnToActiveSession = {}, onChanged = {}, search = {}
            )
        }
        compose.onNode(hasText("Facts") and hasClickAction()).performClick()
        compose.onNode(hasText("×2")).assertExists()
        compose.onNode(hasText(HEADING) and hasClickAction()).performClick()

        compose.waitUntilAtLeastOneExists(hasText("Came up again", substring = true), TIMEOUT_MS)
        compose.onNode(hasText("First heard", substring = true)).assertExists()
        compose.onNode(hasText(LINES_1[3])).assertExists()
        compose.onNode(hasText(LINES_2[2])).assertExists()
        // The transcript around the first occurrence: three segments either side of the evidence, no further.
        compose.onNode(hasText(LINES_1[0])).assertExists()
        compose.onNode(hasText(LINES_1[6])).assertExists()
        compose.onNode(hasText(LINES_1[7])).assertDoesNotExist()
    }

    @Test
    fun textSearchFindsTheTranscriptAndTheFact() {
        compose.setContent {
            var state by remember { mutableStateOf(SearchState()) }
            ClientSearch(
                state = state,
                voiceDisabled = "Not in this test",
                // The app embeds the query first (MainViewModel.searchText); a fixed vector stands in for the embedder.
                onSearchText = { state = SearchState(hits = repo.search(CLIENT, QUERY_VEC, it)) },
                onVoiceStart = { false },
                onVoiceStop = {},
                onClear = { state = SearchState() },
                onOpen = {},
                hasMicPermission = { false },
                requestMicPermission = {}
            )
        }
        compose.onNode(hasSetTextAction()).performTextInput("running")
        compose.onNode(hasSetTextAction()).performImeAction()

        compose.waitUntilAtLeastOneExists(hasText("From the transcript"), TIMEOUT_MS)
        compose.onNode(hasText(LINES_1[0], substring = true)).assertExists()
        compose.onNode(hasText(HEADING)).assertExists()
        compose.onNode(hasText("Nothing found.")).assertDoesNotExist()
    }

    @Test
    fun theLookbackSliderSavesItsSetting() {
        prefs().edit().remove(LiveCueSettings.KEY_LOOKBACK_S).commit()
        compose.setContent { SettingsScreen(onLockVault = {}) }

        compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasContentDescription("Lookback"))
        compose.onNode(hasContentDescription("Lookback")).performSemanticsAction(SemanticsActions.SetProgress) { it(LOOKBACK.toFloat()) }

        compose.onNode(hasText("$LOOKBACK s")).assertExists()
        assertEquals(LOOKBACK, prefs().getInt(LiveCueSettings.KEY_LOOKBACK_S, -1))
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
