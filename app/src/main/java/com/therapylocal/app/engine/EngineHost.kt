package com.therapylocal.app.engine

import com.therapylocal.app.asr.DiarTurn
import com.therapylocal.app.perf.LlmCall

/** Which engine host serves a call. LOCAL = in-process fallback / tests / probes. */
enum class EngineRole(val proc: String) { LIVE("live"), BATCH("batch"), LOCAL("main") }

/** Fakes are selected by model id in debug, never by a kind. */
/** [DIARIZE] is last: the wire carries the name, and a test pins the order. */
enum class EngineKind { LLM, EMBED, ASR, DIARIZE }

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
        /** 16 kHz PCM16 of a turn the model listens to together with [user] (Gemma 4 audio input, docs/AD/AD-22.md); null for a text call. At most 30 s. */
        val pcm: ShortArray? = null,
    ) : EngineOp()

    data class EmbedText(override val tag: CallTag, val handle: Int, val text: String, val query: Boolean) : EngineOp()
    data class EmbedAudio(override val tag: CallTag, val handle: Int, val pcm: ShortArray, val role: String) : EngineOp()
    /**
     * Speech to text for one piece of audio. [language], [translate] and [beam] are for Whisper (docs/AD/AD-22.md): [translate] asks for English text
     * whatever the language spoken (the Whisper translate task); [beam] 0 means the engine decides (Canary), 1 is greedy. Canary ignores all three.
     */
    data class Transcribe(
        override val tag: CallTag, val handle: Int, val pcm: ShortArray,
        val language: String? = null, val translate: Boolean = false, val beam: Int = 0,
    ) : EngineOp()

    /** Speaker turns of a whole recording (16 kHz PCM16, up to an hour). [numSpeakersHint] > 0 fixes the speaker count, 0 clusters by threshold. */
    data class Diarize(override val tag: CallTag, val handle: Int, val pcm: ShortArray, val numSpeakersHint: Int) : EngineOp()
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

    /** The reply to [EngineOp.Diarize]: sorted by start. */
    data class Turns(val turns: List<DiarTurn>) : EngineReply()
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

    /**
     * [closeAsync], then [onClosed] once the close is settled: `true` when the host acknowledged the Close (the caller may now tell the guard
     * the engine was unloaded), `false` when the host ended first or the close failed (the connection's registry settled the handle itself, as
     * a death or a benign end, so the caller must not). The default closes inline, so it is always acknowledged.
     */
    fun closeAsync(op: EngineOp.Close, onClosed: (acked: Boolean) -> Unit) {
        closeAsync(op)
        onClosed(true)
    }

    /** Waits (bounded by `closeTimeoutMs`) until the closes submitted so far have finished, so a load never overlaps the unload before it. */
    fun awaitPendingCloses() {}

    /** The host's status if one is bound now, else null. Never binds a host (the default host has nothing to bind). */
    fun statusIfBound(): EngineReply.HostStatus? =
        runCatching { call(EngineOp.Status(CallTag(java.util.UUID.randomUUID().toString().take(8), null, "status"))) as? EngineReply.HostStatus }.getOrNull()
}

/**
 * How a host process ends itself: `Runtime.getRuntime().halt(code)` (or `_exit` in the native handler), giving EXIT_SELF with this status.
 * 10..12 are on purpose ([benign]). [JAVA_CRASH] is an uncaught exception that was logged (AD-04). [NATIVE_BASE] + signal number is a fatal
 * native signal caught by `quietcrash` (AD-04), so [nativeSignal] reads a status back.
 */
enum class HostExit(val code: Int, val why: String, val benign: Boolean = true) {
    CLIENT_DIED(10, "client_died"), UNBIND(11, "unbind"), SHUTDOWN(12, "shutdown"),
    JAVA_CRASH(13, "java_crash", benign = false);

    companion object {
        const val NATIVE_BASE = 100

        /** Signals 1..31 plus the real-time ones in use stay below this. Never collides with 0..13. */
        private const val NATIVE_LAST = NATIVE_BASE + 64

        /** The signal number a native-crash status stands for, else null. */
        fun nativeSignal(status: Int): Int? = if (status in NATIVE_BASE + 1..NATIVE_LAST) status - NATIVE_BASE else null
    }
}
