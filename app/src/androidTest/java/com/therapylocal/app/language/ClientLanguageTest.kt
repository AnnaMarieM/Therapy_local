package com.therapylocal.app.language

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.therapylocal.app.jobs.PipelineHarness
import com.therapylocal.app.jobs.Said
import com.therapylocal.app.models.SpeechLanguage
import com.therapylocal.app.models.ModelSetup
import com.therapylocal.app.ui.app.AddClientSheet
import com.therapylocal.app.ui.app.LanguagePicker
import com.therapylocal.app.ui.data.ClientRepository
import com.therapylocal.app.ui.design.PaperTheme
import com.therapylocal.app.ui.motion.rememberSheetState
import com.therapylocal.app.data.MindStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Per-client transcription language, on the device: the stored language, the language the transcriber is asked for,
 * the Canary download the settings require, and the pickers. Nothing here downloads a model or loads a native speech library.
 */
@RunWith(AndroidJUnit4::class)
class ClientLanguageTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val harnesses = ArrayList<PipelineHarness>()
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private var savedPrefs: Map<String, Any?> = emptyMap()

    @Before
    fun rememberPrefs() {
        savedPrefs = KEYS.associateWith { prefs.all[it] }
        prefs.edit().apply { KEYS.forEach(::remove) }.commit()
    }

    @After
    fun cleanUp() {
        harnesses.forEach { it.close() }
        prefs.edit().apply {
            KEYS.forEach { k ->
                when (val v = savedPrefs[k]) {
                    null -> remove(k)
                    is String -> putString(k, v)
                    is Set<*> -> putStringSet(k, v.filterIsInstance<String>().toSet())
                }
            }
        }.commit()
    }

    private fun harness(name: String) = PipelineHarness(context, name, emptyList<Said>()).also { harnesses += it }

    @Test
    fun aClientKeepsItsLanguageAndCanChangeIt() {
        val h = harness("lang-store.db")
        h.store.createClient("jana", "Jana", "cs")
        h.store.createClient("tom", "Tom")
        assertEquals("cs", h.store.clientLanguage("jana"))
        assertEquals("en", h.store.clientLanguage("tom"))
        assertEquals("en", h.store.clientLanguage("nobody"))
        assertEquals(mapOf("jana" to "cs", "tom" to "en"), h.store.clients().associate { it.id to it.language })

        h.store.setClientLanguage("tom", "cs")
        assertEquals("cs", h.store.clientLanguage("tom"))
    }

    @Test
    fun eachClientIsTranscribedInItsOwnLanguage() {
        val h = harness("lang-pipeline.db")
        h.record("jana", "s-jana", 1_000, listOf("dobry den", "jak se mate"), language = "cs")
        h.record("tom", "s-tom", 2_000, listOf("hello there", "how are you"), language = "en")
        h.drain()

        assertEquals(setOf("cs", "en"), h.asrLoads.toSet())
        assertEquals("one recogniser per client, not per chunk", 2, h.asrLoads.size)
        assertEquals(listOf("dobry den", "jak se mate"), h.store.segments("s-jana").map { it.text })
        assertEquals(listOf("hello there", "how are you"), h.store.segments("s-tom").map { it.text })
        assertTrue(h.allJobsDone())
    }

    @Test
    fun canaryServesEveryLanguageWithOneDownloadAndIsTheDefault() {
        fun speech() = ModelSetup.required(context).map { it.id }.filter { it.startsWith("canary") }.toSet()

        assertEquals("nothing chosen: Canary", setOf("canary-1b-v2"), speech())

        prefs.edit().putString("default_language", "cs").putStringSet("client_languages", setOf("en")).commit()
        assertEquals("Czech and English clients still need only Canary", setOf("canary-1b-v2"), speech())

        // The old engine settings are gone ; a stale value changes nothing.
        prefs.edit().putString("engine", "whisper").putString("whisper_size", "base").commit()
        assertEquals(setOf("canary-1b-v2"), speech())
    }

    @Test
    fun theAddClientFormStartsWithTheDefaultLanguageAndReturnsTheChosenOne() {
        prefs.edit().putString("default_language", "cs").commit()
        var added: Pair<String, SpeechLanguage>? = null
        compose.setContent { PaperTheme { AddClientSheet(rememberSheetState(), onCancel = {}, onAdd = { n, l -> added = n to l }) } }
        compose.onNode(hasContentDescription("First name")).performTextInput("tom")

        compose.onNode(hasText("English")).performClick()
        compose.onNode(hasContentDescription("Swipe from right to left to add Tom") and hasClickAction()).performClick()
        compose.runOnIdle { assertEquals("Tom" to SpeechLanguage.English, added) }
    }

    @Test
    fun theAddClientFormAddsInTheDefaultLanguageWhenLeftAlone() {
        prefs.edit().putString("default_language", "cs").commit()
        var added: Pair<String, SpeechLanguage>? = null
        compose.setContent { PaperTheme { AddClientSheet(rememberSheetState(), onCancel = {}, onAdd = { n, l -> added = n to l }) } }
        compose.onNode(hasContentDescription("First name")).performTextInput("jana")
        compose.onNode(hasContentDescription("Swipe from right to left to add Jana") and hasClickAction()).performClick()
        compose.runOnIdle { assertEquals("Jana" to SpeechLanguage.Czech, added) }
    }

    @Test
    fun thePickerReportsATapOnAnotherLanguageOnly() {
        val picked = ArrayList<SpeechLanguage>()
        compose.setContent { PaperTheme { LanguagePicker(SpeechLanguage.English, { picked += it }) } }
        compose.onNode(hasText(context.getString(SpeechLanguage.English.labelRes))).performClick()
        compose.onNode(hasText(context.getString(SpeechLanguage.Czech.labelRes))).performClick()
        compose.runOnIdle { assertEquals(listOf(SpeechLanguage.Czech), picked) }
    }

    @Test
    fun theRepositoryStoresAndChangesTheLanguageOfAClient() {
        val h = harness("lang-repo.db")
        val repo = ClientRepository(context) { h.store }
        // Not through SpeechLanguages.markInUse, which would start a model download on the device.
        h.store.createClient("jana", "Jana", "cs")
        assertEquals(SpeechLanguage.Czech, repo.patient("jana").language)
        h.store.setClientLanguage("jana", "en")
        assertEquals(SpeechLanguage.English, repo.patients().single().language)
    }

    private companion object {
        val KEYS = listOf("default_language", "client_languages", "engine", "whisper_size")
    }
}
