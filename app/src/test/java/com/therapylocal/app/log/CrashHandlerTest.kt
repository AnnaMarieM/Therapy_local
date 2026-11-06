package com.therapylocal.app.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** AD-04: a host's uncaught exception is logged and then ends the process with halt(code), never handed to the platform handler. */
class CrashHandlerTest {
    private class Recorder : Thread.UncaughtExceptionHandler {
        var seen: Throwable? = null
        override fun uncaughtException(t: Thread, e: Throwable) { seen = e }
    }

    @Test fun aHostHaltsWithTheCodeAndNeverCallsThePlatformHandler() {
        var halted: Int? = null
        val platform = Recorder()
        AppLog.crashHandler(redacted = true, haltCode = 13, halt = { halted = it }, previous = platform)
            .uncaughtException(Thread.currentThread(), IllegalStateException("patient text"))
        assertEquals(13, halted)
        assertNull(platform.seen)
    }

    @Test fun haltStillRunsWhenLoggingThrows() {
        var halted: Int? = null
        // an exception whose stack trace cannot be read makes the logging part fail; the process must still end
        val bad = object : RuntimeException("x") { override fun getStackTrace(): Array<StackTraceElement> = throw IllegalStateException("boom") }
        AppLog.crashHandler(redacted = true, haltCode = 13, halt = { halted = it }, previous = null)
            .uncaughtException(Thread.currentThread(), bad)
        assertEquals(13, halted)
    }

    @Test fun mainStillDelegatesToThePlatformHandlerAndDoesNotHalt() {
        var halted: Int? = null
        val platform = Recorder()
        val e = RuntimeException("x")
        AppLog.crashHandler(redacted = false, haltCode = null, halt = { halted = it }, previous = platform).uncaughtException(Thread.currentThread(), e)
        assertNull(halted)
        assertTrue(platform.seen === e)
    }
}
