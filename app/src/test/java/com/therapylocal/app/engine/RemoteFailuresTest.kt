package com.therapylocal.app.engine

import android.os.DeadObjectException
import android.os.RemoteException
import android.os.TransactionTooLargeException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** N27: only a real death is a death. */
class RemoteFailuresTest {
    private fun ex(e: RemoteException, alive: Boolean = true) = RemoteFailures.toException(e, alive, EngineRole.BATCH, 3, "ask", 1234)

    @Test fun deadObjectIsDeath() {
        val d = ex(DeadObjectException()) as EngineDied
        assertEquals(EngineRole.BATCH, d.role)
        assertEquals(3, d.generation)
        assertEquals("ask", d.op)
    }

    @Test fun deadBinderIsDeathWhateverTheException() {
        assertTrue(ex(RemoteException("x"), alive = false) is EngineDied)
    }

    @Test fun tooLargeIsNotADeathEvenWithADeadBinderCheckFalse() {
        val r = ex(TransactionTooLargeException()) as ReplyTooLarge
        assertEquals(1234, r.bytes)
    }

    @Test fun otherRemoteExceptionIsTransport() {
        val u = ex(RemoteException("FAILED_TRANSACTION")) as EngineUnavailable
        assertEquals("transport", u.why)
        assertEquals(0L, u.retryInMs)
    }
}
