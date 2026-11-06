package com.therapylocal.app.engine.stress

import androidx.test.platform.app.InstrumentationRegistry
import android.os.ParcelFileDescriptor

/**
 * Shell commands through `UiAutomation` (shell uid). The script travels on the stdin of one `sh`, so it is never tokenised or re-quoted by
 * `executeShellCommand` and may hold any quotes (a fault plan is JSON full of double quotes). Output is read to the end; never use for
 * commands that wait forever.
 */
object Shell {
    class Result(val out: String, val err: String, val exit: Int?)

    /**
     * Runs [script] (one command or several lines) and returns stdout, stderr and the exit status of its last command. The exit code is not
     * available through UiAutomation, so a trailing `echo` carries it.
     *
     *     Shell.script("am start -W -n ${LAB} --es plan ${Shell.quote(planJson)}")
     */
    fun script(script: String): Result {
        val ua = InstrumentationRegistry.getInstrumentation().uiAutomation
        val fds = ua.executeShellCommandRwe("sh")
        var out = ""
        var err = ""
        // The Auto* streams own and close their descriptor; nothing closes it twice (fdsan would abort the process).
        val stdout = ParcelFileDescriptor.AutoCloseInputStream(fds[0])
        val stderr = ParcelFileDescriptor.AutoCloseInputStream(fds[2])
        val stdin = ParcelFileDescriptor.AutoCloseOutputStream(fds[1])
        // stdout and stderr are drained on their own threads, so a chatty command cannot fill a pipe and block.
        val o = Thread { out = stdout.bufferedReader().use { it.readText() } }.apply { start() }
        val e = Thread { err = stderr.bufferedReader().use { it.readText() } }.apply { start() }
        // The script runs in a subshell with stdin on /dev/null, so no command in it can swallow the lines of the script that follow.
        stdin.use { it.write("(\n$script\n) </dev/null\necho __EXIT__=\$?\n".toByteArray()) }
        o.join(); e.join()
        val m = Regex("__EXIT__=(\\d+)\\s*$").find(out)
        return Result(out.removeSuffix(m?.value.orEmpty()).trimEnd(), err.trim(), m?.groupValues?.get(1)?.toInt())
    }

    /** Same as [script]; kept for the probes. */
    fun run(cmd: String): Result = script(cmd)

    fun out(cmd: String): String = script(cmd).out

    /** [s] as one single-quoted shell word. */
    fun quote(s: String): String = "'" + s.replace("'", "'\\''") + "'"
}
