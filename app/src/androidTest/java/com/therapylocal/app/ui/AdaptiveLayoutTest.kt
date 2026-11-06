package com.therapylocal.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.therapylocal.app.SearchState
import com.therapylocal.app.data.MindStore
import com.therapylocal.app.data.SegmentRow
import com.therapylocal.app.session.SessionClock
import com.therapylocal.app.session.SessionPhase
import com.therapylocal.app.session.SessionState
import com.therapylocal.app.ui.app.ClientStatus
import com.therapylocal.app.ui.app.ProfileSheet
import com.therapylocal.app.ui.app.SearchActions
import com.therapylocal.app.ui.app.SessionSheet
import com.therapylocal.app.ui.data.ClientRepository
import com.therapylocal.app.ui.design.Adaptive
import com.therapylocal.app.ui.design.LocalIdle
import com.therapylocal.app.ui.design.LocalWindowSizeOverride
import com.therapylocal.app.ui.design.PaperTheme
import com.therapylocal.app.ui.design.rememberIdleState
import com.therapylocal.app.ui.motion.PageShift
import com.therapylocal.app.ui.motion.rememberSheetState
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.junit.runners.Parameterized.Parameters

/**
 * The pages at every window size the app is meant to live in: phones, a 7" and a 10" tablet, both ways round, and the halves a tablet's
 * split-screen gives. Each is a fixed-size box standing in for the window, so it runs the same on a phone, an emulator and the tablet.
 */
@RunWith(Parameterized::class)
class AdaptiveLayoutTest(private val name: String, private val w: Int, private val h: Int) {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var store: MindStore
    private lateinit var repo: ClientRepository

    @Before
    fun seed() {
        context.deleteDatabase(DB)
        store = MindStore(context, DB, "adaptive-test-password".toByteArray())
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

    private fun window(content: @androidx.compose.runtime.Composable () -> Unit) = compose.setContent {
        CompositionLocalProvider(LocalWindowSizeOverride provides (w to h)) {
            PaperTheme { Box(Modifier.size(w.dp, h.dp)) { content() } }
        }
    }

    private fun assertInsideWindow(label: String) {
        val px = Density(context).run { Rect(0f, 0f, w.dp.toPx(), h.dp.toPx()) }
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue("$name $label: root ${root} larger than window $px", root.width <= px.width + 1 && root.height <= px.height + 1)
        compose.onAllNodes(androidx.compose.ui.test.SemanticsMatcher("any node") { true }, useUnmergedTree = true)
            .fetchSemanticsNodes().forEach { n ->
                val b = n.boundsInRoot
                assertTrue("$name $label: a node sticks out of the window: $b", b.right <= px.right + 1 && b.left >= -1)
            }
    }

    @Test
    fun theProfilePageFitsAndKeepsAReadableWidth() {
        val patient = repo.patient(CLIENT)
        compose.mainClock.autoAdvance = false
        window {
            val sheet = rememberSheetState(w)
            androidx.compose.runtime.LaunchedEffect(Unit) { sheet.open() }
            CompositionLocalProvider(LocalIdle provides rememberIdleState()) {
                PageShift(sheet, onBack = null) {
                    ProfileSheet(
                        sheet = sheet, patient = patient, repo = repo, status = ClientStatus("No sessions yet"),
                        processingSession = null, search = SearchActions(SearchState(), "Not in this test", {}, { false }, {}, {}),
                        sessionActive = false, requestMic = {}, onClose = {}, onRecord = {}, onDeleteClient = {}, onDeleteSession = {},
                    )
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_500)
        compose.onNode(hasText("Ana"), useUnmergedTree = true).assertExists()
        assertInsideWindow("profile")
        val title = compose.onNode(hasText("Ana"), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val maxPx = Density(context).run { Adaptive.ReadingWidth.toPx() }
        assertTrue("$name: the title is within the reading column", title.width <= maxPx + 1)
    }

    @Test
    fun theSessionPageFitsAndItsControlsAreReachable() {
        compose.mainClock.autoAdvance = false
        window {
            val sheet = rememberSheetState(w)
            androidx.compose.runtime.LaunchedEffect(Unit) { sheet.open() }
            PageShift(sheet, onBack = null) {
                SessionSheet(
                    sheet = sheet,
                    session = SessionState(SessionPhase.Recording, CLIENT, SESSION, 0, SessionClock.started(0), cuesOn = false),
                    clientName = "Ana", elapsed = { 65_000L }, level = remember { MutableStateFlow(0.4f) }, cues = emptyList(), cuesError = null,
                    repo = repo, onPause = {}, onResume = {}, onMinimise = {}, onFinish = {}, onHint = {},
                )
            }
        }
        compose.mainClock.advanceTimeBy(1_500)
        compose.onNode(hasText("Pause"), useUnmergedTree = true).assertExists()
        assertInsideWindow("session")
        val pause = compose.onNode(hasText("Pause"), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val px = Density(context).run { h.dp.toPx() }
        assertTrue("$name: pause is on screen", pause.bottom <= px && pause.top >= 0f)
    }

    @Test
    fun aPagesTravelDoesNotGrowWithTheScreen() {
        var travel = 0f
        var phoneTravel = 0f
        window {
            travel = rememberSheetState(w).shiftPx
            phoneTravel = rememberSheetState(600).shiftPx
        }
        compose.waitForIdle()
        if (w >= 600) assertTrue("$name: $travel vs $phoneTravel", travel == phoneTravel)
        else assertTrue("$name: a narrow window travels less", travel <= phoneTravel)
    }

    @Test
    fun aSheetKeepsItsStateWhenTheWindowIsResized() {
        var opened = false
        var winW by androidx.compose.runtime.mutableIntStateOf(w)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalWindowSizeOverride provides Pair(winW, h)) {
                PaperTheme {
                    val sheet = rememberSheetState(winW)
                    androidx.compose.runtime.LaunchedEffect(Unit) { sheet.open() }
                    opened = sheet.target
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        winW = if (w > 700) w / 2 else w * 2 // a split-screen drag, or a rotation
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        assertTrue("$name: the page stayed open through the resize", opened)
    }

    companion object {
        const val DB = "adaptive-test.db"
        const val CLIENT = "ana"
        const val SESSION = "ana_s1"

        @JvmStatic
        @Parameters(name = "{0}")
        fun sizes(): List<Array<Any>> = listOf(
            arrayOf("phone-360x800", 360, 800),
            arrayOf("phone-landscape-800x360", 800, 360),
            arrayOf("tablet7-600x960", 600, 960),
            arrayOf("tablet10-800x1280", 800, 1280),
            arrayOf("tablet10-landscape-1280x800", 1280, 800),
            arrayOf("split-half-400x1280", 400, 1280),
            arrayOf("split-half-landscape-640x800", 640, 800),
            arrayOf("freeform-narrow-320x480", 320, 480),
        )
    }
}
