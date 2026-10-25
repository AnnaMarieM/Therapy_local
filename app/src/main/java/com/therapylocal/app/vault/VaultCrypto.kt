package com.therapylocal.app.vault

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Primitives for the Vault: PBKDF2 key derivation and AES-256-GCM sealing. Pure JVM. */
object VaultCrypto {
    const val KEY_BYTES = 32
    const val PBKDF2_ITERATIONS = 600_000
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private val random = SecureRandom()

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { random.nextBytes(it) }

    /** PBKDF2WithHmacSHA256, 256-bit output. */
    fun deriveKey(password: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(password, salt, iterations, KEY_BYTES * 8)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    /** Returns [12-byte IV][ciphertext || 16-byte tag]. */
    fun seal(key: ByteArray, plain: ByteArray, aad: ByteArray = ByteArray(0)): ByteArray {
        val iv = randomBytes(IV_BYTES)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
        c.updateAAD(aad)
        return iv + c.doFinal(plain)
    }

    /** Throws GeneralSecurityException on wrong key, wrong [aad] or tampering. */
    fun open(key: ByteArray, sealed: ByteArray, aad: ByteArray = ByteArray(0)): ByteArray {
        if (sealed.size < IV_BYTES + TAG_BITS / 8) throw javax.crypto.AEADBadTagException("sealed data too short")
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES))
        c.updateAAD(aad)
        return c.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES)
    }
}
