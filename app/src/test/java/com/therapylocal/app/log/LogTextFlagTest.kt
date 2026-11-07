package com.therapylocal.app.log

import com.therapylocal.app.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** LOG_TEXT (prompts and replies in the log) is on in the qa build only, which holds test data. Release, feel and debug must never have it. */
class LogTextFlagTest {
    private fun gradle(): String = listOf("build.gradle.kts", "app/build.gradle.kts").map(::File).first { it.exists() }.readText()

    @Test fun debug_build_has_it_off() = assertFalse(BuildConfig.LOG_TEXT)

    @Test fun only_qa_turns_it_on_in_the_gradle_file() {
        val lines = gradle().lines().filter { it.contains("\"LOG_TEXT\"") }
        assertEquals("exactly one LOG_TEXT definition: $lines", 1, lines.size)
        val l = lines.single().replace(" ", "")
        assertTrue(l, l.contains("""if(name=="qa")"true"else"false""""))
        assertFalse("a build type must not set true by name other than qa", Regex("""LOG_TEXT",\s*"true"""").containsMatchIn(gradle()))
    }
}
