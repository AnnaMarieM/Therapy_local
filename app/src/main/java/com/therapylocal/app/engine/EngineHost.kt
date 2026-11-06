package com.therapylocal.app.engine

import com.therapylocal.app.perf.LlmCall

/** Which engine host serves a call. LOCAL = in-process fallback / tests / probes. */
enum class EngineRole(val proc: String) { LIVE("live"), BATCH("batch"), LOCAL("main") }

/** Fakes are selected by model id in debug, never by a kind. */
enum class EngineKind { LLM, EMBED, ASR }

/** Fields every log line of one call carries, on both sides of the boundary. */
data class CallTag(val requestId: String, val loadId: String?, val op: String)

sealed class EngineOp {
    abstract val tag: CallTag

    /**
     * One ladder attempt: create, initialize, self-test on [backend]. Must not touch BackendGuard (main only).
     * [guardModel] is main-side bookkeeping for the generation's handle registry; the host ignores it.
     */
    data class Open(
        override val tag: CallTag, val kind: EngineKind, val model: String, val guardModel: String, val backend: String,
        val maxTokens: Int = 0, val textOnly: Boolean = false, val language: String? = null,
    ) : EngineOp()

    data class Ask(
        override val tag: CallTag, val handle: Int, val system: String, val user: String, val schema: String?,
        val maxOutputTokens: Int, val temperature: Double, val topK: Int,
    ) : EngineOp()

    data class EmbedText(override val tag: CallTag, val handle: Int, val text: String, val query: Boolean) : EngineOp()
    data class EmbedAudio(override val tag: CallTag, val handle: Int, val pcm: ShortArray, val role: String) : EngineOp()
    data class Transcribe(override val tag: CallTag, val handle: Int, val pcm: ShortArray) : EngineOp()
    data class Close(override val tag: CallTag, val handle: Int) : EngineOp()
    data class Status(override val tag: CallTag) : EngineOp()

    /** The host deletes its own log files; its writer starts a new file on the next line. */
    data class ClearLogs(override val tag: CallTag) : EngineOp()

    /** Debug fault plan for this generation. The noFaults server answers [EngineReply.Refused] and logs `fault_plan_ignored`. */
    data class ArmFaults(override val tag: CallTag, val planJson: String, val startNo: Int) : EngineOp()
}

sealed class EngineReply {
    data class Opened(val handle: Int, val backend: String, val maxTokens: Int, val audioBackend: String?) : EngineReply()

    /** [perf]: ask only (PerfStats in main). */
    data class Text(val text: String, val perf: LlmCall?) : EngineReply()
    data class Vector(val v: FloatArray) : EngineReply()
    object Done : EngineReply()

    /** ArmFaults on a noFaults host. */
    object Refused : EngineReply()
    data class HostStatus(val pid: Int, val uptimeMs: Long, val pssKb: Long, val handles: List<String>) : EngineReply()
}

/** Phases ("create", "initialize", "test") the host reports synchronously before each native step, so main notes them in the guard first. */
fun interface PhaseSink {
    fun phase(tag: CallTag, model: String, backend: String, phase: String)
}

interface EngineHost {
    val role: EngineRole

    /**
     * Throws what the engine threw (rebuilt across a boundary, see EngineErrors), EngineDied, EngineUnavailable or ReplyTooLarge.
     * Never called on the main thread (checked in EngineProxy).
     */
    fun call(op: EngineOp, phases: PhaseSink = PhaseSink { _, _, _, _ -> }): EngineReply

    /**
     * Close that never blocks the caller: LocalEngineHost runs it inline; RemoteEngineHost hands it to the role's `engine-close`
     * executor bounded by `closeTimeoutMs`. Safe from the main thread.
     */
    fun closeAsync(op: EngineOp.Close)
}

/** How a host process ends on purpose: Runtime.getRuntime().halt(code), giving EXIT_SELF with this status. */
enum class HostExit(val code: Int, val why: String) { CLIENT_DIED(10, "client_died"), UNBIND(11, "unbind"), SHUTDOWN(12, "shutdown") }
