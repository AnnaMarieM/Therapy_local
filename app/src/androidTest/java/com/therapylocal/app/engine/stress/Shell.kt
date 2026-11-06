package com.therapylocal.app.engine.stress

import androidx.test.platform.app.InstrumentationRegistry
import java.io.FileInputStream
import java.io.InputStream

/** Shell commands through `UiAutomation` (shell uid). Output is read to the end; never use for commands that wait forever. */
object Shell {
    class Result(val out: String, val err: String, val exit: Int?)

    private fun InputStream.text() = bufferedReader().use { it.readText() }

    /** stdout and stderr; the exit code is not available through UiAutomation, so a trailing `echo` carries it. */
    fun run(cmd: String): Result {
        val ua = InstrumentationRegistry.getInstrumentation().uiAutomation
        val fds = ua.executeShellCommandRwe("sh -c '$cmd; echo __EXIT__=\$?'")
        val out = FileInputStream(fds[0].fileDescriptor).text()
        val err = FileInputStream(fds[2].fileDescriptor).text()
        fds.forEach { runCatching { it.close() } }
        val m = Regex("__EXIT__=(\\d+)\\s*$").find(out)
        return Result(out.removeSuffix(m?.value.orEmpty()).trimEnd(), err.trim(), m?.groupValues?.get(1)?.toInt())
    }

    fun out(cmd: String): String = run(cmd).out
}
