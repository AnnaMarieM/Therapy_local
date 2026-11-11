package com.therapylocal.app.ui

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.therapylocal.app.ui.app.AddClientSheet
import com.therapylocal.app.ui.design.PaperTheme
import com.therapylocal.app.ui.motion.rememberSheetState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Adding a client has a button, not only the swipe (#74). On a device the swipe hint shows only after a pause and takes taps only while it
 * shows; the button is always there.
 */
@RunWith(AndroidJUnit4::class)
class AddClientButtonTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theButtonIsThereBeforeANameAndAddsOnceThereIsOne() {
        var added: String? = null
        compose.setContent { PaperTheme { AddClientSheet(rememberSheetState(), onCancel = {}, onAdd = { name, _ -> added = name }) } }
        // There before anything is typed, and off until there is a name to add.
        compose.onNode(hasText("Add client") and hasClickAction()).assertIsNotEnabled()
        compose.onNode(hasContentDescription("First name")).performTextInput("george")
        compose.onNode(hasContentDescription("Surname")).performTextInput("miller")
        compose.onNode(hasText("Add George M.") and hasClickAction()).performScrollTo().performClick()
        compose.runOnIdle { assertEquals("George M.", added) }
    }
}
