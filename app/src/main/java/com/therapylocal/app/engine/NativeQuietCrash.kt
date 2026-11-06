package com.therapylocal.app.engine

import com.therapylocal.app.log.AppLog
import com.therapylocal.app.log.LogCtx

/**
 * Installs the native signal handler of an engine host (AD-04): SIGSEGV, SIGBUS, SIGABRT, SIGFPE, SIGILL, SIGSYS and SIGTRAP end the process
 * with `_exit(HostExit.NATIVE_BASE + signal)`. Without it the platform writes a tombstone and reports a native crash, and Android then shows
 * "keeps stopping" for the whole app although only a background host died. Host processes only, never main.
 *
 * The library is `libquietcrash.so` (app/src/main/cpp/quietcrash.c). A missing library or an install the library refuses is logged and the
 * host runs as before (the dialog can then appear), never a crash of its own.
 */
object NativeQuietCrash {
    /** Result codes of the native install. */
    const val INSTALLED = 0
    const val NOT_CHAINED = 1

    @JvmStatic private external fun install(statusBase: Int): Int

    /** Where the library found `sigaction` (the library name), for the log. */
    @JvmStatic private external fun sigactionOwner(): String

    /** Returns the install result, or -1 when the library is not there. */
    fun installNative(): Int = try {
        System.loadLibrary("quietcrash")
        install(HostExit.NATIVE_BASE)
    } catch (_: UnsatisfiedLinkError) {
        -1
    }

    fun installLogged() {
        val r = installNative()
        val owner = if (r >= 0) runCatching { sigactionOwner() }.getOrDefault("?") else null
        AppLog.i("Engine", "quiet_crash_install", LogCtx.NONE, "result" to when (r) {
            INSTALLED -> "installed"
            NOT_CHAINED -> "refused_not_chained"
            -1 -> "library_missing"
            else -> "error_$r"
        }, "sigaction_owner" to owner)
    }
}
