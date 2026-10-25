package com.therapylocal.app.vault

import com.therapylocal.app.testsupport.assertRejected
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.GeneralSecurityException
import kotlin.random.Random

/**
 * Verifies the security properties of docs/SECURITY.md for the primitives: AES-256-GCM sealing (confidentiality, integrity,
 * AAD binding, fresh IV per seal) and PBKDF2-HMAC-SHA256 key derivation (checked against independently computed vectors).
 */
class VaultCryptoTest {
    private val key = ByteArray(32) { (it * 7 + 3).toByte() }
    private val aad = "file.md".toByteArray()

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    // ---- seal / open ----

    @Test
    fun roundTripForBlockBoundarySizes() {
        val rnd = Random(1)
        for (size in listOf(0, 1, 15, 16, 17, 31, 32, 33, 1 shl 20)) {
            val plain = rnd.nextBytes(size)
            val sealed = VaultCrypto.seal(key, plain, aad)
            assertEquals("sealed length for $size", 12 + size + 16, sealed.size) // [12 IV][ciphertext][16 tag], documented layout
            assertArrayEquals("size $size", plain, VaultCrypto.open(key, sealed, aad))
        }
    }

    @Test
    fun ciphertextDoesNotContainThePlaintext() {
        val plain = "SECRET-MARKER-SECRET-MARKER".toByteArray()
        val sealed = VaultCrypto.seal(key, plain)
        assertEquals(-1, String(sealed, Charsets.ISO_8859_1).indexOf("SECRET-MARKER"))
    }

    @Test
    fun everySealOfTheSamePlaintextDiffersAndUsesAFreshIv() {
        val plain = ByteArray(32) { 0x41 }
        val sealed = (1..1000).map { VaultCrypto.seal(key, plain) }
        assertEquals("sealed blobs must be unique", 1000, sealed.map { it.toList() }.toSet().size)
        assertEquals("IVs must be unique (GCM nonce reuse is catastrophic)", 1000, sealed.map { it.copyOfRange(0, 12).toList() }.toSet().size)
        assertEquals(1000, sealed.map { it.copyOfRange(12, it.size).toList() }.toSet().size)
    }

    @Test
    fun flippingAnyBitOfAnyByteIsDetected() {
        val plain = Random(2).nextBytes(40)
        val sealed = VaultCrypto.seal(key, plain, aad)
        for (i in sealed.indices) {
            for (mask in intArrayOf(0x01, 0x80, 0xFF)) {
                val bad = sealed.copyOf()
                bad[i] = (bad[i].toInt() xor mask).toByte()
                assertRejected("byte $i mask $mask") { VaultCrypto.open(key, bad, aad) }
            }
        }
    }

    @Test
    fun flippingSampledBytesOfALargeBlobIsDetected() {
        val sealed = VaultCrypto.seal(key, Random(3).nextBytes(1 shl 20), aad)
        val rnd = Random(4)
        val positions = (0 until 12).toList() + (sealed.size - 16 until sealed.size).toList() + List(100) { rnd.nextInt(sealed.size) }
        for (i in positions) {
            val bad = sealed.copyOf()
            bad[i] = (bad[i].toInt() xor 0x10).toByte()
            assertRejected("byte $i") { VaultCrypto.open(key, bad, aad) }
        }
    }

    @Test
    fun truncationAtEveryLengthIsRejected() {
        val sealed = VaultCrypto.seal(key, Random(5).nextBytes(40), aad)
        for (len in 0 until sealed.size) {
            assertRejected("truncated to $len") { VaultCrypto.open(key, sealed.copyOf(len), aad) }
        }
        for (cut in 1..sealed.size) { // dropping the front is just as bad
            assertRejected("front cut $cut") { VaultCrypto.open(key, sealed.copyOfRange(cut, sealed.size), aad) }
        }
    }

    @Test
    fun truncationOfALargeBlobIsRejected() {
        val sealed = VaultCrypto.seal(key, Random(6).nextBytes(1 shl 20), aad)
        for (drop in listOf(1, 2, 15, 16, 17, 4096, 65536)) {
            assertRejected("dropped $drop") { VaultCrypto.open(key, sealed.copyOf(sealed.size - drop), aad) }
        }
    }

    @Test
    fun appendedBytesAreRejected() {
        val sealed = VaultCrypto.seal(key, Random(7).nextBytes(40), aad)
        for (extra in listOf(byteArrayOf(0), byteArrayOf(1, 2, 3), ByteArray(16))) {
            assertRejected("appended ${extra.size}") { VaultCrypto.open(key, sealed + extra, aad) }
        }
    }

    @Test
    fun emptyPlaintextIsStillAuthenticated() {
        val sealed = VaultCrypto.seal(key, ByteArray(0), aad)
        assertEquals(28, sealed.size)
        for (i in sealed.indices) {
            val bad = sealed.copyOf()
            bad[i] = (bad[i].toInt() xor 1).toByte()
            assertRejected("byte $i") { VaultCrypto.open(key, bad, aad) }
        }
    }

    @Test
    fun wrongKeyIsRejected() {
        val sealed = VaultCrypto.seal(key, byteArrayOf(1, 2, 3), aad)
        val rnd = Random(8)
        repeat(20) { assertRejected("random key") { VaultCrypto.open(rnd.nextBytes(32), sealed, aad) } }
        for (i in key.indices) { // a key that differs in a single bit
            val near = key.copyOf()
            near[i] = (near[i].toInt() xor 1).toByte()
            assertRejected("near key $i") { VaultCrypto.open(near, sealed, aad) }
        }
    }

    @Test
    fun wrongOrMissingAadIsRejected() {
        val sealed = VaultCrypto.seal(key, byteArrayOf(1, 2, 3), aad)
        assertRejected("different aad") { VaultCrypto.open(key, sealed, "file.mD".toByteArray()) }
        assertRejected("shorter aad") { VaultCrypto.open(key, sealed, "file.m".toByteArray()) }
        assertRejected("longer aad") { VaultCrypto.open(key, sealed, aad + 0) }
        assertRejected("missing aad") { VaultCrypto.open(key, sealed) }
        val noAad = VaultCrypto.seal(key, byteArrayOf(1, 2, 3)) // and the reverse
        assertRejected("unexpected aad") { VaultCrypto.open(key, noAad, aad) }
        assertArrayEquals(byteArrayOf(1, 2, 3), VaultCrypto.open(key, noAad)) // default AAD is empty
    }

    @Test
    fun invalidKeyLengthIsRejectedNotSilentlyAccepted() {
        assertRejected("seal with 31-byte key") { VaultCrypto.seal(ByteArray(31), byteArrayOf(1)) }
        try { // SecretKeySpec itself refuses an empty key (IllegalArgumentException)
            VaultCrypto.seal(ByteArray(0), byteArrayOf(1))
            fail("empty key accepted")
        } catch (_: IllegalArgumentException) {
        } catch (_: GeneralSecurityException) {
        }
        val sealed = VaultCrypto.seal(key, byteArrayOf(1))
        assertRejected("open with 33-byte key") { VaultCrypto.open(ByteArray(33), sealed) }
    }

    @Test
    fun randomBytesHasRequestedLengthAndIsNotConstant() {
        assertEquals(0, VaultCrypto.randomBytes(0).size)
        assertEquals(32, VaultCrypto.randomBytes(32).size)
        assertFalse(VaultCrypto.randomBytes(32).contentEquals(VaultCrypto.randomBytes(32)))
        assertTrue(VaultCrypto.randomBytes(64).any { it.toInt() != 0 })
    }

    // ---- deriveKey ----

    @Test
    fun deriveKeyMatchesIndependentPbkdf2Vectors() {
        // Computed with Python hashlib.pbkdf2_hmac('sha256', ..., dklen=32). The first is the RFC 7914 vector for ("passwd", "salt", 1).
        assertArrayEquals(
            hex("55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc"),
            VaultCrypto.deriveKey("passwd".toCharArray(), "salt".toByteArray(), 1),
        )
        // non-ASCII password (UTF-8 encoded) and a salt with binary bytes
        assertArrayEquals(
            hex("814d5cd8cb26240c518122ab4fc5dccfe43eff7fc97e7d5448fe1f4cccd7e93f"),
            VaultCrypto.deriveKey("passäword".toCharArray(), byteArrayOf(0, 1, 's'.code.toByte(), 'a'.code.toByte(), 'l'.code.toByte(), 't'.code.toByte()), 1000),
        )
    }

    @Test
    fun deriveKeyIsDeterministicAndHasKeyLength() {
        val salt = Random(9).nextBytes(16)
        val pw = "correct horse".toCharArray()
        val a = VaultCrypto.deriveKey(pw, salt, 1000)
        assertEquals(32, a.size)
        assertEquals(VaultCrypto.KEY_BYTES, a.size)
        assertArrayEquals(a, VaultCrypto.deriveKey(pw, salt, 1000)) // same CharArray instance reused: it must still be usable
        assertArrayEquals(a, VaultCrypto.deriveKey("correct horse".toCharArray(), salt.copyOf(), 1000))
    }

    @Test
    fun deriveKeyChangesWithEveryInput() {
        val salt = Random(10).nextBytes(16)
        val base = VaultCrypto.deriveKey("pw".toCharArray(), salt, 1000)
        val saltFlip = salt.copyOf().also { it[15] = (it[15].toInt() xor 1).toByte() }
        assertFalse(base.contentEquals(VaultCrypto.deriveKey("pw".toCharArray(), saltFlip, 1000)))
        assertFalse(base.contentEquals(VaultCrypto.deriveKey("pw".toCharArray(), salt + 0, 1000)))
        assertFalse(base.contentEquals(VaultCrypto.deriveKey("pW".toCharArray(), salt, 1000)))
        assertFalse(base.contentEquals(VaultCrypto.deriveKey("pw ".toCharArray(), salt, 1000)))
        assertFalse(base.contentEquals(VaultCrypto.deriveKey("pw".toCharArray(), salt, 1001)))
        assertFalse(base.contentEquals(VaultCrypto.deriveKey("pw".toCharArray(), salt, 999)))
    }

    @Test
    fun derivedKeyIsUsableAsSealKeyAndOnlyTheRightPasswordOpens() {
        val salt = Random(11).nextBytes(16)
        val sealed = VaultCrypto.seal(VaultCrypto.deriveKey("right".toCharArray(), salt, 100), byteArrayOf(5, 6, 7))
        assertArrayEquals(byteArrayOf(5, 6, 7), VaultCrypto.open(VaultCrypto.deriveKey("right".toCharArray(), salt, 100), sealed))
        assertRejected("wrong password") { VaultCrypto.open(VaultCrypto.deriveKey("wrong".toCharArray(), salt, 100), sealed) }
    }

    @Test
    fun productionParametersMatchSecurityDoc() {
        assertEquals(600_000, VaultCrypto.PBKDF2_ITERATIONS) // docs/SECURITY.md: 600,000 iterations
        assertEquals(32, VaultCrypto.KEY_BYTES) // AES-256
    }
}
