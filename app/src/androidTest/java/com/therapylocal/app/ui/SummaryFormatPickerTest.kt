package com.therapylocal.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
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

    private fun show() = compose.setContent { PaperTheme { SummaryFormatPicker(shown, { shown = it }, onSettle = { settled += it }) } }

    @Test
    fun everyStopIsNamedAndTheDefaultExampleShows() {
        show()
        for (name in listOf("SOAP", "DAP", "BIRP", "Narrative")) compose.onNode(hasText(name)).assertIsDisplayed()
        compose.onNode(hasText("Subjective", substring = true)).assertIsDisplayed()
    }

    @Test
    fun movingTheSliderSelectsStopsAndTheExampleChanges() {
        show()
        val slider = compose.onNode(hasContentDescription("Summary format"))
        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(1f) }
        compose.runOnIdle { assertEquals("dap", shown) }
        compose.onNode(hasText("Data", substring = true)).assertIsDisplayed()
        compose.onNode(hasText("Subjective", substring = true)).assertDoesNotExist()

        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(2f) }
        compose.runOnIdle { assertEquals("birp", shown) }
        compose.onNode(hasText("Intervention", substring = true)).assertIsDisplayed()

        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(3f) }
        compose.runOnIdle { assertEquals("narrative", shown) }
        compose.onNode(hasText("Intervention", substring = true)).assertDoesNotExist()
        compose.onNode(hasText("I will be fired", substring = true)).assertIsDisplayed()
    }

    @Test
    fun tappingAStopNameSelectsAndSettlesIt() {
        show()
        compose.onNode(hasText("BIRP")).performClick()
        compose.runOnIdle {
            assertEquals("birp", shown)
            assertEquals(listOf("birp"), settled)
        }
        compose.onNode(hasText("Response", substring = true)).assertIsDisplayed()
    }
}
