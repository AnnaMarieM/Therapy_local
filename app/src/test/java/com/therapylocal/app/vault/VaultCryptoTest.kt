package com.therapylocal.app.vault

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.GeneralSecurityException

class VaultCryptoTest {
    private val key = VaultCrypto.randomBytes(VaultCrypto.KEY_BYTES)

    @Test
    fun sealOpenRoundTrip() {
        val plain = "Sitzung mit Klient".toByteArray()
        val sealed = VaultCrypto.seal(key, plain, "aad".toByteArray())
        assertEquals(12 + plain.size + 16, sealed.size)
        assertArrayEquals(plain, VaultCrypto.open(key, sealed, "aad".toByteArray()))
    }

    @Test(expected = GeneralSecurityException::class)
    fun wrongKeyThrows() {
        VaultCrypto.open(VaultCrypto.randomBytes(32), VaultCrypto.seal(key, byteArrayOf(1, 2, 3)))
    }

    @Test(expected = GeneralSecurityException::class)
    fun tamperedByteThrows() {
        val sealed = VaultCrypto.seal(key, byteArrayOf(1, 2, 3))
        sealed[sealed.size - 1] = (sealed[sealed.size - 1].toInt() xor 1).toByte()
        VaultCrypto.open(key, sealed)
    }

    @Test(expected = GeneralSecurityException::class)
    fun wrongAadThrows() {
        VaultCrypto.open(key, VaultCrypto.seal(key, byteArrayOf(1), "a".toByteArray()), "b".toByteArray())
    }

    @Test
    fun deriveKeyIsDeterministicPerSalt() {
        val salt = VaultCrypto.randomBytes(16)
        val a = VaultCrypto.deriveKey("pw".toCharArray(), salt, 1000)
        assertEquals(VaultCrypto.KEY_BYTES, a.size)
        assertArrayEquals(a, VaultCrypto.deriveKey("pw".toCharArray(), salt, 1000))
        assertFalse(a.contentEquals(VaultCrypto.deriveKey("pw".toCharArray(), VaultCrypto.randomBytes(16), 1000)))
        assertFalse(a.contentEquals(VaultCrypto.deriveKey("other".toCharArray(), salt, 1000)))
    }
}
