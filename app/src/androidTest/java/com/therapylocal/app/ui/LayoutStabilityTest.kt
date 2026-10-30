package com.therapylocal.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.therapylocal.app.ui.data.ClientRepository
import com.therapylocal.app.data.MindStore
import com.therapylocal.app.data.SegmentRow
import com.therapylocal.app.session.SessionClock
import com.therapylocal.app.session.SessionPhase
import com.therapylocal.app.session.SessionState
import com.therapylocal.app.ui.app.ClientStatus
import com.therapylocal.app.ui.app.ProfileSheet
import com.therapylocal.app.ui.app.SearchActions
import com.therapylocal.app.SearchState
import com.therapylocal.app.ui.app.SessionSheet
import com.therapylocal.app.ui.app.StatusLine
import com.therapylocal.app.ui.design.LocalIdle
import com.therapylocal.app.ui.design.PaperTheme
import com.therapylocal.app.ui.design.rememberIdleState
import com.therapylocal.app.ui.motion.rememberSheetState
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Nothing that comes and goes on its own may move the page: a status turning into its evidence (one line, then three, then two), the
 * swipe hints appearing, lingering and being swiped away, a hint saying "Release to …" under the session's controls. Each test notes
 * where the things around it are, runs the animation through every phase on the test clock, and checks they never moved.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class LayoutStabilityTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var store: MindStore
    private lateinit var repo: ClientRepository

    @Before
    fun seed() {
        context.deleteDatabase(DB)
        store = MindStore(context, DB, "layout-test-password".toByteArray())
        repo = ClientRepository(context) { store }
        store.createClient(CLIENT, "Ana")
        store.createSession(SESSION, CLIENT, 1_767_225_600_000L)
        store.replaceSegments(SESSION, listOf(SegmentRow(0, 0, 5_000, "We talked about the move.")))
    }

    @After
    fun erase() {
        store.close()
        context.deleteDatabase(DB)
    }

    private fun bounds(m: SemanticsMatcher): Rect = compose.onNode(m, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    /** Steps the clock through [checkpoints] (ms from now) and asserts every [watched] node is where it was at the start. */
    private fun holdsStill(watched: List<SemanticsMatcher>, checkpoints: List<Long>, at: (Long) -> Unit = {}) {
        val start = watched.map(::bounds)
        var now = 0L
        for (t in checkpoints) {
            compose.mainClock.advanceTimeBy(t - now)
            now = t
            at(t)
            watched.forEachIndexed { i, m -> assertEquals("moved at $t ms: $m", start[i], bounds(m)) }
        }
    }

    @Test
    fun aStatusTurningIntoLongerEvidenceNeverMovesWhatIsBelowIt() {
        compose.mainClock.autoAdvance = false // the busy dot pulses forever
        compose.setContent {
            PaperTheme {
                Column(Modifier.width(240.dp)) {
                    StatusLine(
                        ClientStatus(
                            "Processing · Queued",
                            busy = true,
                            details = listOf(
                                "Step 1/5 · starts next",
                                "Time left: after 2 sessions are measured (0 so far), so for now there is only the step and the count",
                                "Reading 1,240 tokens/s · writing 14 tokens/s",
                            ),
                        ),
                        Modifier.fillMaxWidth(),
                    )
                    Text("Below", Modifier.testTag("below"))
                }
            }
        }
        compose.mainClock.advanceTimeBy(100)
        // The gist, then each evidence line in turn (after 10 s, then every 4 s), mid-crossfade and settled.
        holdsStill(listOf(hasTestTag("below")), listOf(5_000, 10_300, 10_700, 14_300, 14_700, 18_300, 18_700, 22_700, 26_700))
    }

    @Test
    fun theProfileStaysPutWhileItsHintsComeLingerAndAreSwipedAway() {
        compose.mainClock.autoAdvance = false
        val patient = repo.patient(CLIENT)
        compose.setContent {
            PaperTheme {
                CompositionLocalProvider(LocalIdle provides rememberIdleState()) {
                    ProfileSheet(
                        sheet = rememberSheetState(), patient = patient, repo = repo,
                        status = ClientStatus(
                            "Processing · Transcribing audio", busy = true,
                            details = listOf("Step 1/5 · 3/12 chunks · measuring…", "Time left: after 2 sessions are measured (0 so far)"),
                        ),
                        processingSession = SESSION, search = SearchActions(SearchState(), "Not in this test", {}, { false }, {}, {}),
                        sessionActive = false, requestMic = {}, onClose = {}, onRecord = {}, onDeleteClient = {}, onDeleteSession = {},
                    )
                }
            }
        }
        compose.mainClock.advanceTimeBy(100)
        var appeared = false
        holdsStill(
            listOf(hasText("Ana"), hasText("SESSIONS")),
            // hidden · shown after the pause · lingering · the fingertip lands · swiped away · shown again · the status's evidence turning
            listOf(1_000, 2_800, 4_000, 6_900, 7_200, 7_700, 8_200, 11_000, 13_000, 15_000, 19_000),
        ) { t ->
            if (t in 2_800..6_800) appeared = appeared || compose.onAllNodes(hasContentDescription("Swipe from right to left to record")).fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue("the hint was there to see", appeared)
    }

    @Test
    fun theSessionControlsStayPutWhenItsHintSaysReleaseToGoBack() {
        compose.mainClock.autoAdvance = false // the clock and the level meter run on every frame
        var minimised = false
        compose.setContent {
            PaperTheme {
                SessionSheet(
                    sheet = rememberSheetState(),
                    session = SessionState(SessionPhase.Recording, CLIENT, SESSION, 0, SessionClock.started(0), cuesOn = false),
                    clientName = "Ana", elapsed = { 65_000L }, level = remember { MutableStateFlow(0.4f) }, cues = emptyList(), cuesError = null,
                    repo = repo, onPause = {}, onResume = {}, onMinimise = { minimised = true }, onFinish = {}, onHint = {},
                )
            }
        }
        compose.mainClock.advanceTimeBy(500)
        val square = hasContentDescription("Hold to finish the session")
        val pause = hasText("Pause")
        val before = listOf(bounds(square), bounds(pause))
        // A swipe from left to right, held past the point where it acts: the hint now says "Release to go back".
        compose.onRoot().performTouchInput { down(Offset(width * 0.2f, height * 0.5f)); moveBy(Offset(width * 0.55f, 0f), 200) }
        compose.mainClock.advanceTimeBy(300)
        assertTrue("the hint says what letting go will do", compose.onAllNodes(hasContentDescription("Release to go back", substring = true)).fetchSemanticsNodes().isNotEmpty())
        assertEquals(before, listOf(bounds(square), bounds(pause)))
        compose.onRoot().performTouchInput { up() }
        compose.mainClock.advanceTimeBy(300)
        assertEquals(before, listOf(bounds(square), bounds(pause)))
        assertTrue("letting go went back", minimised)
    }

    private companion object {
        const val DB = "layout-test.db"
        const val CLIENT = "ana"
        const val SESSION = "ana_s1"
    }
}
