package com.therapylocal.app.engine.runner

import android.content.Context
import com.google.ai.edge.litertlm.ActivationDataType
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.BenchmarkInfo
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.ResponseFormat
import com.google.ai.edge.litertlm.SamplerConfig
import com.therapylocal.app.engine.CallTag
import com.therapylocal.app.engine.EngineKind
import com.therapylocal.app.engine.EngineOp
import com.therapylocal.app.engine.EngineReply
import com.therapylocal.app.engine.PhaseSink
import com.therapylocal.app.log.AppLog
import com.therapylocal.app.models.Accelerators
import com.therapylocal.app.log.LogCtx
import com.therapylocal.app.models.LiteRtBindings
import com.therapylocal.app.models.ModelRegistry
import com.therapylocal.app.models.NpuRuntime
import com.therapylocal.app.perf.LlmCall

/**
 * The native half of the memory LLM (Gemma 4 on LiteRT-LM, text only, docs/AD/AD-02.md): one engine on one backend. The ladder, the guard,
 * the memory gate, `PerfStats` and the health flag stay in the main process (`MemoryLlm`); a runner never touches them.
 */
@OptIn(ExperimentalApi::class)
internal class LlmRunner(private val context: Context) : AskEngine {
    override val kind = EngineKind.LLM
    override var model: String = ""
        private set
    override var backend: String = "none"
        private set
    private var engine: Engine? = null
    private var maxTokens = 0

    /** One ladder attempt: create, initialise and test-run an engine on [op].backend. One that fails on the way is closed here. */
    fun open(op: EngineOp.Open, phases: PhaseSink): EngineReply.Opened {
        ExperimentalFlags.enableBenchmark = true
        val spec = ModelRegistry.byId(op.model)
        val name = op.backend
        // The NPU runs its own build of the model, made for this SoC (AD-09); another backend its own build if the catalogue has one, else the default file.
        val file = if (name == "NPU") npuFile(spec.id) else ModelRegistry.fileFor(context, spec, name)
        val f = callFields(op.tag)
        val be: Backend = LiteRtBindings.backend(context, name)
        val config = EngineConfig(modelPath = file.path, backend = be, audioBackend = null, visionBackend = null, maxNumTokens = op.maxTokens, cacheDir = context.cacheDir.path,
            // AD-19: the GPU's default precision gives wrong output for Gemma E2B on some chips (fails the second proof); null keeps the default.
            activationDataType = if (GpuPrecision.float32(spec.id, name)) ActivationDataType.FLOAT32 else null)
        notePhase(phases, op.tag, TAG, op.guardModel, name, "create")
        val e = AppLog.span(TAG, "memory_llm_engine_create", LogCtx.NONE, "backend_try" to name, *f) { Engine(config) }
        try {
            notePhase(phases, op.tag, TAG, op.guardModel, name, "initialize")
            AppLog.span(TAG, "memory_llm_engine_initialize", LogCtx.NONE, "backend_try" to name, "max_tokens" to op.maxTokens, *f) { e.initialize() }
            notePhase(phases, op.tag, TAG, op.guardModel, name, "test")
            AppLog.span(TAG, "memory_llm_engine_test_reply", LogCtx.NONE, "backend_try" to name, *f) { span ->
                // The CPU is the reference and the last resort: it is kept without a proof, since a failed proof there would only log.
                // Both proofs cost real load time on the CPU (7 to 22 s per load, measured), so they run on accelerators only.
                if (Accelerators.isFallback(name)) span.put("proof_skipped", "fallback")
                else {
                    proveOutput(e, span)
                    proveFacts(e, span)
                }
            }
        } catch (t: Throwable) {
            runCatching { e.close() }.onFailure { AppLog.w(TAG, "memory_llm_engine_close_after_failure_failed", LogCtx.NONE, "backend_try" to name, "reason" to it.message, *f) }
            throw t
        }
        engine = e
        model = spec.id
        backend = name
        maxTokens = op.maxTokens
        return EngineReply.Opened(0, name, op.maxTokens, null)
    }

    /** The NPU file of [model] for this device, whose own metadata must name this SoC: a file for another chip would fail inside the driver. */
    private fun npuFile(model: String): java.io.File {
        val soc = NpuRuntime.deviceSoc()
        val file = checkNotNull(NpuRuntime.modelFile(context, model, soc)) { "no validated NPU model for $soc ($model)" }
        NpuBundleCheck.verify(file, soc)
        return file
    }

    /** The backend proof (LlmProof.kt): a fixed reformat-as-JSON task, checked strictly. A wrong answer throws, so the ladder strikes the backend. */
    private fun proveOutput(e: Engine, span: com.therapylocal.app.log.Span) {
        val t0 = System.nanoTime()
        val cfg = ConversationConfig(samplerConfig = SamplerConfig(topK = LlmProof.TOP_K, topP = 1.0, temperature = LlmProof.TEMPERATURE, seed = 1), maxOutputToken = LlmProof.MAX_OUTPUT_TOKENS, systemInstruction = Contents.of(LlmProof.SYSTEM))
        val (reply, b) = e.createConversation(cfg).use { c -> c.sendMessage(LlmProof.USER).toString() to c.getBenchmarkInfo() }
        val verdict = LlmProof.check(reply)
        // Text only when LOG_TEXT is on (the QA build); AppLog.text is a no-op otherwise.
        AppLog.text(TAG, "llm_proof_prompt", LogCtx.NONE, LlmProof.SYSTEM + "\n---\n" + LlmProof.USER)
        AppLog.text(TAG, "llm_proof_reply", LogCtx.NONE, reply)
        span.put("proof_ms", (System.nanoTime() - t0) / 1_000_000)
        span.put("reply_chars", reply.length)
        span.put("prefill_tok_s", b.lastPrefillTokensPerSecond)
        span.put("decode_tok_s", b.lastDecodeTokensPerSecond)
        span.put("decode_tokens", b.lastDecodeTokenCount)
        span.put("proof_ok", verdict is LlmProof.Verdict.Ok)
        if (verdict is LlmProof.Verdict.Fail) {
            span.put("proof_reason", verdict.reason)
            error("llm_output_proof_failed:${verdict.reason}")
        }
    }

    /** The second proof (LlmProof.checkFacts): the app's own extraction instruction on three made-up sentences. A wrong answer throws, so the ladder strikes the backend. */
    private fun proveFacts(e: Engine, span: com.therapylocal.app.log.Span) {
        val t0 = System.nanoTime()
        val cfg = ConversationConfig(samplerConfig = SamplerConfig(topK = LlmProof.TOP_K, topP = 1.0, temperature = LlmProof.TEMPERATURE, seed = 1), maxOutputToken = LlmProof.MAX_OUTPUT_TOKENS2, systemInstruction = Contents.of(LlmProof.SYSTEM2))
        val reply = e.createConversation(cfg).use { c -> c.sendMessage(LlmProof.USER2).toString() }
        val verdict = LlmProof.checkFacts(reply)
        AppLog.text(TAG, "llm_proof2_prompt", LogCtx.NONE, LlmProof.SYSTEM2 + "\n---\n" + LlmProof.USER2)
        AppLog.text(TAG, "llm_proof2_reply", LogCtx.NONE, reply)
        span.put("proof2_ms", (System.nanoTime() - t0) / 1_000_000)
        span.put("proof2_ok", verdict is LlmProof.Verdict.Ok)
        if (verdict is LlmProof.Verdict.Fail) {
            span.put("proof2_reason", verdict.reason)
            error("llm_output_proof2_failed:${verdict.reason}")
        }
    }

    /** One fresh conversation. [EngineOp.Ask.schema] switches on constrained decoding for this call only. */
    override fun ask(op: EngineOp.Ask): EngineReply.Text {
        val e = checkNotNull(engine) { "not loaded" }
        val t0 = System.nanoTime()
        // A global flag: switched off again afterwards, or every later conversation on the engine pays its ~4 s setup.
        ExperimentalFlags.enableConversationConstrainedDecoding = op.schema != null
        try {
            val cfg = ConversationConfig(
                systemInstruction = Contents.of(op.system),
                samplerConfig = SamplerConfig(topK = op.topK, topP = 0.9, temperature = op.temperature, seed = 1),
                enableResponseFormat = op.schema != null,
                maxOutputToken = op.maxOutputTokens,
            )
            e.createConversation(cfg).use { c ->
                val msg = if (op.schema != null) c.sendMessage(op.user, responseFormat = ResponseFormat.json(op.schema)) else c.sendMessage(op.user)
                val b: BenchmarkInfo = c.getBenchmarkInfo()
                val wall = (System.nanoTime() - t0) / 1_000_000
                // Numbers only, never text.
                AppLog.i(
                    TAG, "llm_call", LogCtx.NONE, "backend" to backend, "constrained" to (op.schema != null), "prompt_tokens" to b.lastPrefillTokenCount,
                    "prefill_tok_s" to b.lastPrefillTokensPerSecond, "ttft_s" to b.timeToFirstTokenInSecond, "decode_tokens" to b.lastDecodeTokenCount,
                    "decode_tok_s" to b.lastDecodeTokensPerSecond, "wall_ms" to wall, *callFields(op.tag, op.handle),
                )
                val perf = LlmCall(
                    b.lastPrefillTokenCount.toInt(), b.lastPrefillTokensPerSecond.toDouble(), b.lastDecodeTokenCount.toInt(),
                    b.lastDecodeTokensPerSecond.toDouble(), wall,
                )
                return EngineReply.Text(msg.toString(), perf)
            }
        } finally {
            ExperimentalFlags.enableConversationConstrainedDecoding = false
        }
    }

    override fun close(tag: CallTag) {
        val e = engine ?: return
        engine = null
        val f = callFields(tag)
        val t0 = System.nanoTime()
        runCatching { e.close() }.onFailure { AppLog.e(TAG, "memory_llm_close_failed", LogCtx.NONE, it, "backend" to backend, *f) }
        AppLog.i(TAG, "memory_llm_unloaded", LogCtx.NONE, "model" to model, "backend" to backend, "close_ms" to (System.nanoTime() - t0) / 1_000_000, *f)
        runCatching { AppLog.logMemory(context, TAG, "memory_llm_after_unload", extra = arrayOf("backend" to backend, "model" to model, *f)) }
        backend = "none"
    }

    companion object {
        const val TAG = "Memory"
    }
}
