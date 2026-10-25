package com.therapylocal.app.vault

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KeyFileTest {
    @Test
    fun roundTripWithoutBiometric() {
        val kf = KeyFile(byteArrayOf(1, 2, 3), 600_000, byteArrayOf(9, 8, 7))
        val back = KeyFile.fromJson(kf.toJson())
        assertEquals(kf, back)
        assertNull(back.biometricIv)
        assertNull(back.biometricSealed)
    }

    @Test
    fun roundTripWithBiometric() {
        val kf = KeyFile(byteArrayOf(1), 1000, byteArrayOf(2), byteArrayOf(3, 4), byteArrayOf(5, 6, 7), version = 1)
        val back = KeyFile.fromJson(kf.toJson())
        assertEquals(kf, back)
        assertArrayEquals(byteArrayOf(3, 4), back.biometricIv)
        assertArrayEquals(byteArrayOf(5, 6, 7), back.biometricSealed)
    }
}
