package com.therapylocal.app.engine

import android.os.DeadObjectException
import android.os.RemoteException
import android.os.TransactionTooLargeException
import com.therapylocal.app.models.BackendGuard

/**
 * The one place that decides what a failed transact means. Only a real death is a death: a `TransactionTooLargeException` or any other
 * transport error leaves the host alive and must not become a backend strike.
 */
object RemoteFailures {
    sealed class Failure {
        /** The host process is gone: `DeadObjectException`, or the binder is no longer alive. */
        object Death : Failure()

        /** `TransactionTooLargeException`: the host is alive; with the inline limit and `Shm` this is a codec bug. */
        object TooLarge : Failure()

        /** Any other `RemoteException` (`FAILED_TRANSACTION`, a frozen cached process): the host is alive. */
        object Transport : Failure()
    }

    fun classify(e: RemoteException, binderAlive: Boolean): Failure = when {
        e is TransactionTooLargeException -> Failure.TooLarge
        e is DeadObjectException || !binderAlive -> Failure.Death
        else -> Failure.Transport
    }

    /**
     * The exception the proxy layer throws for [e]. A `RemoteException` never escapes. In this commit a death carries no exit lookup and a
     * transport error is not retried (`EngineConnection` adds both).
     */
    fun toException(e: RemoteException, binderAlive: Boolean, role: EngineRole, generation: Int, op: String, bytes: Int): RuntimeException =
        when (classify(e, binderAlive)) {
            Failure.Death -> EngineDied(role, generation, BackendGuard.LastExit(BackendGuard.ExitKind.UNKNOWN, false, "exit not looked up"), null, op)
            Failure.TooLarge -> ReplyTooLarge(op, bytes)
            Failure.Transport -> EngineUnavailable(role, "transport", 0)
        }
}
