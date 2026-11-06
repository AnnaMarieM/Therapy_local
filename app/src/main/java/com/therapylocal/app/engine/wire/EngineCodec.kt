package com.therapylocal.app.engine.wire

import android.os.Bundle
import android.os.Parcel
import android.os.SharedMemory
import com.therapylocal.app.BuildConfig
import com.therapylocal.app.config.EngineConfig
import com.therapylocal.app.engine.CallTag
import com.therapylocal.app.engine.EngineError
import com.therapylocal.app.engine.EngineErrors
import com.therapylocal.app.engine.EngineKind
import com.therapylocal.app.engine.EngineOp
import com.therapylocal.app.engine.EngineReply
import com.therapylocal.app.perf.LlmCall

/** True for ops whose payload is the user's text: a failure of one is captured without its message. */
val EngineOp.isText: Boolean get() = this is EngineOp.Ask || this is EngineOp.EmbedText

/**
 * The only codec between the engine types and a `Bundle`. PCM always travels through [Shm]; a string over
 * `EngineConfig.INLINE_MAX_BYTES` (UTF-8) does too, in both directions. A failure is `Bundle{ok=false, type, cls, msg, causes, frames}`.
 *
 * Whoever encodes a Bundle calls [release] on it in a `finally`, after the call returned; decoding closes the received regions itself.
 */
object EngineCodec {
    private const val OK = "ok"
    private const val T = "t"

    // ---- ops ----

    fun encode(op: EngineOp): Bundle {
        val b = Bundle()
        b.putString("req", op.tag.requestId)
        b.putString("load", op.tag.loadId)
        b.putString("tagop", op.tag.op)
        try {
            when (op) {
                is EngineOp.Open -> {
                    b.putString(T, "open")
                    b.putString("kind", op.kind.name)
                    b.putString("model", op.model)
                    b.putString("gmodel", op.guardModel)
                    b.putString("backend", op.backend)
                    b.putInt("maxTokens", op.maxTokens)
                    b.putBoolean("textOnly", op.textOnly)
                    b.putString("lang", op.language)
                }
                is EngineOp.Ask -> {
                    b.putString(T, "ask")
                    b.putInt("handle", op.handle)
                    putStr(b, "system", op.system)
                    putStr(b, "user", op.user)
                    if (op.schema != null) putStr(b, "schema", op.schema)
                    b.putInt("maxOut", op.maxOutputTokens)
                    b.putDouble("temp", op.temperature)
                    b.putInt("topK", op.topK)
                }
                is EngineOp.EmbedText -> {
                    b.putString(T, "embed_text")
                    b.putInt("handle", op.handle)
                    putStr(b, "text", op.text)
                    b.putBoolean("query", op.query)
                }
                is EngineOp.EmbedAudio -> {
                    b.putString(T, "embed_audio")
                    b.putInt("handle", op.handle)
                    b.putString("role", op.role)
                    putPcm(b, op.pcm)
                }
                is EngineOp.Transcribe -> {
                    b.putString(T, "transcribe")
                    b.putInt("handle", op.handle)
                    putPcm(b, op.pcm)
                }
                is EngineOp.Close -> {
                    b.putString(T, "close")
                    b.putInt("handle", op.handle)
                }
                is EngineOp.Status -> b.putString(T, "status")
                is EngineOp.ClearLogs -> b.putString(T, "clear_logs")
                is EngineOp.ArmFaults -> {
                    b.putString(T, "arm_faults")
                    putStr(b, "plan", op.planJson)
                    b.putInt("startNo", op.startNo)
                }
            }
        } catch (t: Throwable) {
            release(b)
            throw t
        }
        return checkSize(b)
    }

    fun decodeOp(b: Bundle): EngineOp {
        val tag = CallTag(b.getString("req").orEmpty(), b.getString("load"), b.getString("tagop").orEmpty())
        return when (val t = b.getString(T)) {
            "open" -> EngineOp.Open(
                tag, EngineKind.valueOf(b.getString("kind")!!), b.getString("model").orEmpty(), b.getString("gmodel").orEmpty(),
                b.getString("backend").orEmpty(), b.getInt("maxTokens"), b.getBoolean("textOnly"), b.getString("lang"),
            )
            "ask" -> EngineOp.Ask(
                tag, b.getInt("handle"), getStr(b, "system"), getStr(b, "user"), if (has(b, "schema")) getStr(b, "schema") else null,
                b.getInt("maxOut"), b.getDouble("temp"), b.getInt("topK"),
            )
            "embed_text" -> EngineOp.EmbedText(tag, b.getInt("handle"), getStr(b, "text"), b.getBoolean("query"))
            "embed_audio" -> EngineOp.EmbedAudio(tag, b.getInt("handle"), getPcm(b), b.getString("role").orEmpty())
            "transcribe" -> EngineOp.Transcribe(tag, b.getInt("handle"), getPcm(b))
            "close" -> EngineOp.Close(tag, b.getInt("handle"))
            "status" -> EngineOp.Status(tag)
            "clear_logs" -> EngineOp.ClearLogs(tag)
            "arm_faults" -> EngineOp.ArmFaults(tag, getStr(b, "plan"), b.getInt("startNo"))
            else -> throw IllegalArgumentException("unknown op type $t")
        }
    }

    // ---- replies ----

    fun encode(reply: EngineReply): Bundle {
        val b = Bundle()
        b.putBoolean(OK, true)
        try {
            when (reply) {
                is EngineReply.Opened -> {
                    b.putString(T, "opened")
                    b.putInt("handle", reply.handle)
                    b.putString("backend", reply.backend)
                    b.putInt("maxTokens", reply.maxTokens)
                    b.putString("audioBackend", reply.audioBackend)
                }
                is EngineReply.Text -> {
                    b.putString(T, "text")
                    putStr(b, "text", reply.text)
                    reply.perf?.let {
                        b.putBoolean("hasPerf", true)
                        b.putInt("pTok", it.promptTokens)
                        b.putDouble("pTokS", it.prefillTokS)
                        b.putInt("dTok", it.decodeTokens)
                        b.putDouble("dTokS", it.decodeTokS)
                        b.putLong("wall", it.wallMs)
                    }
                }
                is EngineReply.Vector -> {
                    b.putString(T, "vector")
                    b.putFloatArray("v", reply.v)
                }
                EngineReply.Done -> b.putString(T, "done")
                EngineReply.Refused -> b.putString(T, "refused")
                is EngineReply.HostStatus -> {
                    b.putString(T, "status")
                    b.putInt("pid", reply.pid)
                    b.putLong("uptime", reply.uptimeMs)
                    b.putLong("pss", reply.pssKb)
                    b.putStringArrayList("handles", ArrayList(reply.handles))
                }
            }
        } catch (t: Throwable) {
            release(b)
            throw t
        }
        return checkSize(b)
    }

    /** Decodes a reply; a failure reply throws the rebuilt exception (see [EngineErrors.rebuild]). */
    fun decodeReply(b: Bundle): EngineReply {
        if (!b.getBoolean(OK, false)) throw EngineErrors.rebuild(decodeError(b))
        return when (val t = b.getString(T)) {
            "opened" -> EngineReply.Opened(b.getInt("handle"), b.getString("backend").orEmpty(), b.getInt("maxTokens"), b.getString("audioBackend"))
            "text" -> EngineReply.Text(
                getStr(b, "text"),
                if (b.getBoolean("hasPerf")) LlmCall(b.getInt("pTok"), b.getDouble("pTokS"), b.getInt("dTok"), b.getDouble("dTokS"), b.getLong("wall")) else null,
            )
            "vector" -> EngineReply.Vector(b.getFloatArray("v") ?: FloatArray(0))
            "done" -> EngineReply.Done
            "refused" -> EngineReply.Refused
            "status" -> EngineReply.HostStatus(b.getInt("pid"), b.getLong("uptime"), b.getLong("pss"), b.getStringArrayList("handles") ?: emptyList())
            else -> throw IllegalArgumentException("unknown reply type $t")
        }
    }

    // ---- errors ----

    fun error(e: EngineError): Bundle = Bundle().apply {
        putBoolean(OK, false)
        putString("type", e.type)
        putString("cls", e.cls)
        putString("msg", e.message)
        putStringArrayList("causes", ArrayList(e.causes))
        putString("frames", e.frames)
    }

    fun decodeError(b: Bundle) = EngineError(
        b.getString("type").orEmpty(), b.getString("cls").orEmpty(), b.getString("msg"),
        b.getStringArrayList("causes") ?: emptyList(), b.getString("frames").orEmpty(),
    )

    /** Closes every `SharedMemory` in [b]: the sender's copies, once the call is over. Never throws. */
    fun release(b: Bundle) {
        for (k in b.keySet().toList()) {
            @Suppress("DEPRECATION")
            val v = try { b.get(k) } catch (_: Throwable) { null }
            if (v is SharedMemory) runCatching { v.close() }
        }
    }

    // ---- fields ----

    private fun putPcm(b: Bundle, pcm: ShortArray) {
        b.putInt("pcm.n", pcm.size)
        b.putParcelable("pcm", Shm.shorts(pcm))
    }

    @Suppress("DEPRECATION")
    private fun getPcm(b: Bundle): ShortArray {
        val shm = b.getParcelable<SharedMemory>("pcm") ?: throw IllegalArgumentException("pcm region missing")
        return Shm.readShorts(shm, b.getInt("pcm.n"))
    }

    private fun has(b: Bundle, key: String) = b.containsKey(key) || b.containsKey("$key.shm")

    private fun putStr(b: Bundle, key: String, s: String) {
        val bytes = s.toByteArray(Charsets.UTF_8)
        if (ShmMath.goesInline(bytes.size)) {
            b.putString(key, s)
        } else {
            b.putInt("$key.n", bytes.size)
            b.putParcelable("$key.shm", Shm.bytes(bytes))
        }
    }

    @Suppress("DEPRECATION")
    private fun getStr(b: Bundle, key: String): String {
        val shm = b.getParcelable<SharedMemory>("$key.shm") ?: return b.getString(key).orEmpty()
        return String(Shm.readBytes(shm, b.getInt("$key.n")), Charsets.UTF_8)
    }

    /** Debug only: a regression shows up here, before a `TransactionTooLargeException` on a device. */
    private fun checkSize(b: Bundle): Bundle {
        if (!BuildConfig.DEBUG) return b
        val p = Parcel.obtain()
        try {
            p.writeBundle(b)
            val size = p.dataSize()
            if (size > EngineConfig.BUNDLE_DEBUG_MAX_BYTES) {
                release(b)
                throw IllegalStateException("engine bundle is $size bytes, over the debug limit ${EngineConfig.BUNDLE_DEBUG_MAX_BYTES}")
            }
        } finally {
            p.recycle()
        }
        return b
    }
}
