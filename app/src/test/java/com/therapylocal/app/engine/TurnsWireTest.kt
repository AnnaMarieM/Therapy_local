package com.therapylocal.app.engine

import com.therapylocal.app.asr.DiarTurn
import com.therapylocal.app.config.EngineConfig
import com.therapylocal.app.engine.wire.TurnsWire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnsWireTest {
    @Test
    fun turnsRoundTrip() {
        val turns = listOf(DiarTurn(0, 1_500, 0), DiarTurn(1_500, 3_600_000, 1), DiarTurn(3_600_000, 3_600_001, 12))
        assertEquals(turns, TurnsWire.unpack(TurnsWire.pack(turns)))
        assertEquals(emptyList<DiarTurn>(), TurnsWire.unpack(TurnsWire.pack(emptyList())))
    }

    @Test
    fun anHourOfBusyTalkStaysInline() {
        // 10k turns in 60 minutes would be a turn every 0.36 s; real sessions have a few hundred.
        val bytes = TurnsWire.pack(List(10_000) { DiarTurn(it * 360L, it * 360L + 300, it % 2) })
        assertEquals(120_000, bytes.size)
        assertTrue(bytes.size <= EngineConfig.INLINE_MAX_BYTES)
    }

    @Test
    fun aCutOffPayloadIsRefused() {
        assertThrows(IllegalArgumentException::class.java) { TurnsWire.unpack(ByteArray(13)) }
    }

    @Test
    fun aTimeThatDoesNotFitAnIntIsRefused() {
        assertThrows(ArithmeticException::class.java) { TurnsWire.pack(listOf(DiarTurn(0, Int.MAX_VALUE + 1L, 0))) }
    }
}
