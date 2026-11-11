package com.therapylocal.app.ui

import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.therapylocal.app.ui.app.CreatePassword
import com.therapylocal.app.ui.app.ResetPassword
import com.therapylocal.app.ui.design.PaperTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #73: the keyboard's Next on the new password moves to "write it again", on the first-run password and on the reset after
 * "Forgot password". Nothing is submitted here, so no vault is created or changed.
 */
@RunWith(AndroidJUnit4::class)
class PasswordFieldsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun nextOnTheNewPasswordMovesToTheRepeatField() {
        compose.setContent { PaperTheme { CreatePassword(onBack = {}, onCreated = {}) } }
        nextMovesToTheRepeatField()
    }

    @Test
    fun nextOnTheResetPasswordMovesToTheRepeatField() {
        compose.setContent { PaperTheme { ResetPassword(onBack = {}, onDone = {}) } }
        nextMovesToTheRepeatField()
    }

    private fun nextMovesToTheRepeatField() {
        val fields = compose.onAllNodes(hasSetTextAction())
        fields[0].performTextInput(PASSWORD)
        fields[0].performImeAction()
        fields[1].assertIsFocused()
        fields[0].assertIsNotFocused()
    }

    private companion object {
        const val PASSWORD = "12345678"
    }
}
