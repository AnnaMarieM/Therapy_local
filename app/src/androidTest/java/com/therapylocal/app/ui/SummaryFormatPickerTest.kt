package com.therapylocal.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.therapylocal.app.ui.app.SummaryFormatPicker
import com.therapylocal.app.ui.design.PaperTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The summary format slider: one stop per format, and the example under it follows the selected stop. */
@RunWith(AndroidJUnit4::class)
class SummaryFormatPickerTest {
    @get:Rule
    val compose = createComposeRule()

    private var shown by mutableStateOf("soap")
    private val settled = ArrayList<String>()

    /** A fixed phone-sized, scrollable host, so the result does not depend on the emulator's window; what must be seen is scrolled to first. */
    private fun show() = compose.setContent {
        PaperTheme {
            Column(Modifier.size(360.dp, 640.dp).verticalScroll(rememberScrollState())) {
                SummaryFormatPicker(shown, { shown = it }, onSettle = { settled += it })
            }
        }
    }

    /** The stop names and section headings are drawn in capitals ([com.therapylocal.app.ui.app.Label]), so text is matched without case. */
    private fun text(t: String, substring: Boolean = false) = hasText(t, substring = substring, ignoreCase = true)

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertShown() = performScrollTo().assertIsDisplayed()

    @Test
    fun everyStopIsNamedAndTheDefaultExampleShows() {
        show()
        for (name in listOf("SOAP", "DAP", "BIRP", "Narrative")) compose.onNode(text(name)).assertShown()
        compose.onNode(text("Subjective", substring = true)).assertShown()
    }

    @Test
    fun movingTheSliderSelectsStopsAndTheExampleChanges() {
        show()
        val slider = compose.onNode(hasContentDescription("Summary format"))
        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(1f) }
        compose.runOnIdle { assertEquals("dap", shown) }
        compose.onNode(text("Data", substring = true)).assertShown()
        compose.onNode(text("Subjective", substring = true)).assertDoesNotExist()

        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(2f) }
        compose.runOnIdle { assertEquals("birp", shown) }
        compose.onNode(text("Intervention", substring = true)).assertShown()

        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(3f) }
        compose.runOnIdle { assertEquals("narrative", shown) }
        compose.onNode(text("Intervention", substring = true)).assertDoesNotExist()
        compose.onNode(text("I will be fired", substring = true)).assertShown()
    }

    @Test
    fun tappingAStopNameSelectsAndSettlesIt() {
        show()
        compose.onNode(text("BIRP")).performClick()
        compose.runOnIdle {
            assertEquals("birp", shown)
            assertEquals(listOf("birp"), settled)
        }
        compose.onNode(text("Response", substring = true)).assertShown()
    }
}
