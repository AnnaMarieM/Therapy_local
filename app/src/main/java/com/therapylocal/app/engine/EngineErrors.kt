package com.therapylocal.app.engine

import com.therapylocal.app.models.BackendGuard
import com.therapylocal.app.models.BackendLadder
import java.util.Collections
import java.util.IdentityHashMap

/** An error as it crosses the process boundary. [type] is the simple name (empty for an anonymous class), [cls] the full class name. */
data class EngineError(val type: String, val cls: String, val message: String?, val causes: List<String>, val frames: String)

/** The ONE marshalling helper. */
object EngineErrors {
    const val MAX_MESSAGE = 2000
    const val MAX_FRAMES_BYTES = 8 * 1024
    const val MAX_CAUSES = 10

    /** [redact] = true for text ops: message null, types and frames only. */
    fun capture(t: Throwable, redact: Boolean): EngineError {
        val message = if (redact) null else try {
            t.message?.take(MAX_MESSAGE)
        } catch (_: Throwable) {
            "(message unreadable)"
        }
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>()).apply { add(t) }
        val causes = ArrayList<String>()
        var c = runCatching { t.cause }.getOrNull()
        while (c != null && causes.size < MAX_CAUSES && seen.add(c)) {
            causes += c.javaClass.name
            c = runCatching { c!!.cause }.getOrNull()
        }
        val frames = cutBytes(runCatching { BackendLadder.frames(t) }.getOrDefault(""), MAX_FRAMES_BYTES)
        return EngineError(t.javaClass.simpleName, t.javaClass.name, message, causes, frames)
    }

    fun rebuild(e: EngineError): Throwable {
        val m = e.message
        val cname = e.cls.ifEmpty { e.type }
        val known = e.cls.isEmpty() || e.cls.startsWith("java.")
        val t: Throwable = if (!known) RemoteEngineException(e.type, m, cname) else when (e.type) {
            "IllegalStateException" -> IllegalStateException(m)
            "IllegalArgumentException" -> IllegalArgumentException(m)
            "UnsupportedOperationException" -> UnsupportedOperationException(m)
            "OutOfMemoryError" -> OutOfMemoryError(m)
            "IndexOutOfBoundsException" -> IndexOutOfBoundsException(m)
            "NullPointerException" -> NullPointerException(m)
            "RuntimeException" -> RuntimeException(m)
            "Exception" -> Exception(m)
            // Must not interrupt the main caller.
            "InterruptedException" -> IllegalStateException("interrupted in host")
            else -> RemoteEngineException(e.type, m, cname)
        }
        var tail = t
        for (c in e.causes) {
            val next = RemoteEngineException(c.substringAfterLast('.').substringAfterLast('$').ifEmpty { c }, null, c)
            runCatching { tail.initCause(next) }.onFailure { return t }
            tail = next
        }
        return t
    }

    private fun cutBytes(s: String, max: Int): String {
        if (s.toByteArray().size <= max) return s
        var end = minOf(s.length, max)
        while (end > 0 && s.substring(0, end).toByteArray().size > max) end--
        return s.substring(0, end)
    }
}

interface NamedError {
    /** The original simple name; empty for an anonymous class. */
    val typeName: String

    /** The original full class name, for logs. */
    val className: String get() = typeName
}

/** Keeps the original simple name, so strike reasons and JobRunner failure codes are unchanged. */
class RemoteEngineException(override val typeName: String, message: String?, override val className: String = typeName) :
    RuntimeException(message), NamedError

/**
 * The host process died during a call, or the call's handle belongs to a generation that already ended (stale handle). A
 * RemoteException is never thrown past the proxy: this, EngineUnavailable or ReplyTooLarge is.
 */
class EngineDied(val role: EngineRole, val generation: Int, val exit: BackendGuard.LastExit, val phase: String?, val op: String) :
    IllegalStateException("engine ${role.proc} died during $op: ${exit.kind.name.lowercase()} ${exit.description}")

/** No host can serve now (restart storm, bind failures, persistent transport error). Never a backend strike. The job is deferred until [retryInMs]. */
class EngineUnavailable(val role: EngineRole, val why: String, val retryInMs: Long) :
    IllegalStateException("engine ${role.proc} unavailable: $why")

/** The binder refused a reply or request (TransactionTooLargeException) while the host is alive. No strike, host kept. */
class ReplyTooLarge(val op: String, val bytes: Int) : IllegalStateException("$op payload too large: $bytes bytes")
