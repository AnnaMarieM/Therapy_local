package com.therapylocal.app.engine

import com.therapylocal.app.engine.runner.AskEngine
import com.therapylocal.app.engine.runner.AsrEngine
import com.therapylocal.app.engine.runner.EmbedEngine
import com.therapylocal.app.engine.runner.Runner
import com.therapylocal.app.log.AppLog
import com.therapylocal.app.log.LogCtx
import com.therapylocal.app.perf.LlmCall
import java.util.Random
import java.util.zip.CRC32
import kotlin.math.sqrt

/**
 * Deterministic engines for the model ids `fake-llm`, `fake-embed` and `fake-asr` (debug only; one copy, androidTest imports it). They go
 * through the same server, codec, ladder and phases as the real runners: three phases are reported, the self-test is the open itself.
 * Nothing here logs text.
 */
internal object FakeRunners {
    const val LLM = "fake-llm"
    const val EMBED = "fake-embed"
    const val ASR = "fake-asr"
    private const val TAG = "Fake"

    fun open(op: EngineOp.Open, role: EngineRole, phases: PhaseSink): Pair<Runner, EngineReply.Opened>? {
        val spec = Faults.current?.fake ?: FaultPlan.FakeSpec()
        val r: FakeBase = when (op.model) {
            LLM -> FakeLlm(spec)
            EMBED -> FakeEmbed(spec, op.textOnly)
            ASR -> FakeAsr(spec)
            else -> return null
        }
        val kind = when (op.model) { LLM -> EngineKind.LLM; EMBED -> EngineKind.EMBED; else -> EngineKind.ASR }
        require(kind == op.kind) { "model ${op.model} is not a $kind engine" }
        val f = arrayOf<Pair<String, Any?>>("req" to op.tag.requestId, "load_id" to op.tag.loadId, "op" to op.tag.op)
        for (p in listOf("create", "initialize", "test")) {
            AppLog.i(TAG, "backend_phase", LogCtx.NONE, "model" to op.model, "backend_try" to op.backend, "phase" to p, *f)
            runCatching { phases.phase(op.tag, op.model, op.backend, p) }
            AppLog.flush()
        }
        r.model = op.model
        r.backend = op.backend
        r.resident(spec.residentMb)
        val ctx = if (op.maxTokens > 0) op.maxTokens else spec.llmCtx
        val reply = when (kind) {
            EngineKind.LLM -> EngineReply.Opened(0, op.backend, ctx, null)
            EngineKind.EMBED -> EngineReply.Opened(0, op.backend, op.maxTokens, if (op.textOnly) null else "CPU")
            EngineKind.ASR, EngineKind.DIARIZE -> EngineReply.Opened(0, op.backend, 0, null)   // no fake diarizer: the lab's fake ids never reach this
        }
        return r to reply
    }

    /** A unit vector of [dim] numbers, summed from hashed words so texts that share words are close. */
    fun vectorOfText(text: String, dim: Int): FloatArray {
        val v = FloatArray(dim)
        val words = text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
        for (w in words.ifEmpty { listOf("") }) {
            val rnd = Random(w.hashCode().toLong() * 0x9E3779B97F4A7C15uL.toLong())
            for (i in 0 until dim) v[i] += (rnd.nextGaussian()).toFloat()
        }
        return unit(v)
    }

    fun vectorOfPcm(pcm: ShortArray, dim: Int): FloatArray {
        val crc = CRC32()
        val b = ByteArray(pcm.size * 2)
        for (i in pcm.indices) { b[2 * i] = pcm[i].toByte(); b[2 * i + 1] = (pcm[i].toInt() shr 8).toByte() }
        crc.update(b)
        val rnd = Random(crc.value)
        return unit(FloatArray(dim) { rnd.nextGaussian().toFloat() })
    }

    /** The audio fingerprint the `crc` transcript carries, so a test can check the PCM arrived intact (also through shared memory). */
    fun crcOfPcm(pcm: ShortArray): String {
        val crc = CRC32()
        val b = ByteArray(pcm.size * 2)
        for (i in pcm.indices) { b[2 * i] = pcm[i].toByte(); b[2 * i + 1] = (pcm[i].toInt() shr 8).toByte() }
        crc.update(b)
        return "crc:%08x:%d".format(crc.value, pcm.size)
    }

    private fun unit(v: FloatArray): FloatArray {
        var s = 0.0
        for (x in v) s += x * x
        val n = sqrt(s).toFloat().takeIf { it > 0f } ?: 1f
        return FloatArray(v.size) { v[it] / n }
    }

    abstract class FakeBase : Runner {
        override var model: String = ""
        override var backend: String = "none"
        private var resident: List<ByteArray>? = null

        /** Keeps [mb] touched megabytes while loaded, like a resident model: real memory for lmkd and the host PSS. */
        fun resident(mb: Int) {
            if (mb > 0) resident = Memory.touched(mb)
        }

        override fun close(tag: CallTag) {
            resident = null
            AppLog.i(TAG, "fake_unloaded", LogCtx.NONE, "model" to model, "backend" to backend, "req" to tag.requestId, "op" to tag.op)
            backend = "none"
        }
    }

    class FakeLlm(private val spec: FaultPlan.FakeSpec) : FakeBase(), AskEngine {
        override val kind = EngineKind.LLM

        override fun ask(op: EngineOp.Ask): EngineReply.Text {
            check(backend != "none") { "not loaded" }
            val text = when (spec.llmReply) {
                "empty" -> ""
                "json" -> """{"ok": true}"""
                "script" -> spec.script.firstOrNull { op.user.contains(it.first) || op.system.contains(it.first) }?.second ?: """{"duplicate": false}"""
                else -> "echo: " + op.user.take(2_000)
            }
            val promptTokens = (op.system.length + op.user.length) / 4
            val outTokens = maxOf(1, text.length / 4)
            val wall = ((promptTokens / spec.prefillTps + outTokens / spec.decodeTps) * 1000).toLong().coerceAtMost(50)
            Thread.sleep(wall)
            return EngineReply.Text(text, LlmCall(promptTokens, spec.prefillTps, outTokens, spec.decodeTps, wall))
        }
    }

    class FakeEmbed(private val spec: FaultPlan.FakeSpec, private val textOnly: Boolean) : FakeBase(), EmbedEngine {
        override val kind = EngineKind.EMBED

        override fun embedText(op: EngineOp.EmbedText): EngineReply.Vector {
            check(backend != "none") { "Embedder not loaded" }
            return EngineReply.Vector(vectorOfText(op.text, spec.embedDim))
        }

        override fun embedAudio(op: EngineOp.EmbedAudio): EngineReply.Vector {
            check(!textOnly) { "Loaded without the audio encoder" }
            check(backend != "none") { "Embedder not loaded" }
            return EngineReply.Vector(vectorOfPcm(op.pcm, spec.embedDim))
        }
    }

    class FakeAsr(private val spec: FaultPlan.FakeSpec) : FakeBase(), AsrEngine {
        override val kind = EngineKind.ASR

        override fun transcribe(op: EngineOp.Transcribe): EngineReply.Text {
            check(backend != "none") { "Canary not loaded" }
            return EngineReply.Text(if (spec.asrMode == "crc") crcOfPcm(op.pcm) else spec.asrText, null)
        }
    }
}
