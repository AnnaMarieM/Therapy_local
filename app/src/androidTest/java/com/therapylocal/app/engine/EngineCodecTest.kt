package com.therapylocal.app.engine

import android.os.Bundle
import android.os.Parcel
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.therapylocal.app.config.EngineConfig
import com.therapylocal.app.engine.wire.EngineCodec
import com.therapylocal.app.perf.LlmCall
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/** Round trips of every op, reply and error through the Bundle codec, parcelled the way Binder would. */
@RunWith(AndroidJUnit4::class)
class EngineCodecTest {
    private val tag = CallTag("req-1", "load-1", "op")

    /** What Binder does to a Bundle: write it to a Parcel and read it back. */
    private fun wire(b: Bundle): Bundle {
        val p = Parcel.obtain()
        try {
            p.writeBundle(b)
            p.setDataPosition(0)
            return p.readBundle(javaClass.classLoader)!!
        } finally {
            p.recycle()
        }
    }

    private fun roundOp(op: EngineOp): EngineOp {
        val b = EngineCodec.encode(op)
        try {
            return EngineCodec.decodeOp(wire(b))
        } finally {
            EngineCodec.release(b)
        }
    }

    private fun roundReply(r: EngineReply): EngineReply {
        val b = EngineCodec.encode(r)
        try {
            return EngineCodec.decodeReply(wire(b))
        } finally {
            EngineCodec.release(b)
        }
    }

    private fun text(bytes: Int) = buildString { while (length < bytes) append('a') }

    @Test fun openRoundTrips() {
        val op = EngineOp.Open(tag, EngineKind.EMBED, "m", "gm", "GPU", 4096, true, "cs")
        assertEquals(op, roundOp(op))
        val bare = EngineOp.Open(CallTag("r", null, "o"), EngineKind.LLM, "m", "m", "CPU")
        assertEquals(bare, roundOp(bare))
    }

    @Test fun simpleOpsRoundTrip() {
        for (op in listOf(EngineOp.Close(tag, 3), EngineOp.Status(tag), EngineOp.ClearLogs(tag), EngineOp.ArmFaults(tag, "{\"a\":1}", 2))) {
            assertEquals(op, roundOp(op))
        }
    }

    @Test fun askAndEmbedTextRoundTripAroundTheInlineLimit() {
        for (n in listOf(0, 1, EngineConfig.INLINE_MAX_BYTES, EngineConfig.INLINE_MAX_BYTES + 1, 1_500_000)) {
            val ask = EngineOp.Ask(tag, 1, text(n), text(n) + "é", if (n % 2 == 0) "{}" else null, 512, 0.7, 40)
            assertEquals("ask n=$n", ask, roundOp(ask))
            val emb = EngineOp.EmbedText(tag, 1, text(n), true)
            assertEquals("embed n=$n", emb, roundOp(emb))
            val reply = EngineReply.Text(text(n), LlmCall(10, 1.5, 20, 2.5, 300))
            assertEquals("reply n=$n", reply, roundReply(reply))
        }
        assertEquals(EngineReply.Text("x", null), roundReply(EngineReply.Text("x", null)))
    }

    @Test fun pcmRoundTripsAtEverySize() {
        for (n in listOf(0, 1, 7, 20 * 16_000, 5_250_000)) {
            val pcm = ShortArray(n) { (it * 31).toShort() }
            val a = roundOp(EngineOp.EmbedAudio(tag, 2, pcm, "speaker")) as EngineOp.EmbedAudio
            assertArrayEquals("embed n=$n", pcm, a.pcm)
            assertEquals("speaker", a.role)
            val t = roundOp(EngineOp.Transcribe(tag, 2, pcm)) as EngineOp.Transcribe
            assertArrayEquals("transcribe n=$n", pcm, t.pcm)
        }
    }

    @Test fun repliesRoundTrip() {
        assertEquals(EngineReply.Opened(5, "GPU", 4096, "CPU"), roundReply(EngineReply.Opened(5, "GPU", 4096, "CPU")))
        assertEquals(EngineReply.Opened(5, "CPU", 0, null), roundReply(EngineReply.Opened(5, "CPU", 0, null)))
        assertEquals(EngineReply.Done, roundReply(EngineReply.Done))
        assertEquals(EngineReply.Refused, roundReply(EngineReply.Refused))
        val v = roundReply(EngineReply.Vector(floatArrayOf(0.5f, -1f, 3f))) as EngineReply.Vector
        assertArrayEquals(floatArrayOf(0.5f, -1f, 3f), v.v, 0f)
        val s = EngineReply.HostStatus(42, 1000, 2048, listOf("1:llm:m:GPU"))
        assertEquals(s, roundReply(s))
    }

    @Test fun errorsRoundTripAsTheSameClassOrANamedRemoteException() {
        val known = EngineCodec.error(EngineErrors.capture(IllegalStateException("boom"), redact = false))
        try {
            EngineCodec.decodeReply(wire(known))
            fail()
        } catch (e: IllegalStateException) {
            assertEquals("boom", e.message)
        }
        val named = EngineCodec.error(EngineErrors.capture(java.io.IOException("disk", RuntimeException("inner")), redact = false))
        try {
            EngineCodec.decodeReply(wire(named))
            fail()
        } catch (e: RemoteEngineException) {
            assertEquals("IOException", e.typeName)
            assertEquals("disk", e.message)
            assertTrue(e.cause is RemoteEngineException)
        }
        val redacted = EngineCodec.error(EngineErrors.capture(IllegalArgumentException("secret words"), redact = true))
        assertNull(EngineCodec.decodeError(wire(redacted)).message)
    }
}
