package com.therapylocal.app.vault

import com.therapylocal.app.testsupport.assertFailsCleanly
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import kotlin.random.Random

/** vault.json (docs/SECURITY.md "Key file"): salt, iterations, password-sealed DEK, optional biometric IV and sealed DEK. */
class KeyFileTest {
    private val full = KeyFile(byteArrayOf(1, 2, 3), 600_000, byteArrayOf(9, 8, 7), byteArrayOf(4, 4), byteArrayOf(5, 6), version = 1)

    private fun assertFailsCleanly(msg: String, json: String) = assertFailsCleanly(msg) { KeyFile.fromJson(json) }

    @Test
    fun roundTripWithoutBiometric() {
        val kf = KeyFile(byteArrayOf(1, 2, 3), 600_000, byteArrayOf(9, 8, 7))
        val json = JSONObject(kf.toJson())
        assertFalse(json.has("biometricIv"))
        assertFalse(json.has("biometricSealed"))
        val back = KeyFile.fromJson(kf.toJson())
        assertEquals(kf, back)
        assertNull(back.biometricIv)
        assertNull(back.biometricSealed)
    }

    @Test
    fun everyFieldSurvivesTheRoundTripIndividually() {
        val back = KeyFile.fromJson(full.toJson())
        assertArrayEquals(byteArrayOf(1, 2, 3), back.salt)
        assertEquals(600_000, back.iterations)
        assertArrayEquals(byteArrayOf(9, 8, 7), back.passwordSealed)
        assertArrayEquals(byteArrayOf(4, 4), back.biometricIv)
        assertArrayEquals(byteArrayOf(5, 6), back.biometricSealed)
        assertEquals(1, back.version)
    }

    @Test
    fun roundTripOfDeterministicEdgeCasesAndRandomBytes() {
        val allBytes = ByteArray(256) { it.toByte() }
        val edge = KeyFile(allBytes, 1, allBytes, allBytes, allBytes)
        assertEquals(edge, KeyFile.fromJson(edge.toJson()))
        assertArrayEquals(allBytes, KeyFile.fromJson(edge.toJson()).passwordSealed)
        // (empty biometric arrays are not covered: fromJson reads an empty string as "absent"; real IV/sealed blobs are never empty)
        val empties = KeyFile(ByteArray(0), 10_000_000, ByteArray(0))
        val back = KeyFile.fromJson(empties.toJson())
        assertEquals(empties, back)
        assertEquals(0, back.salt.size)
        assertEquals(0, back.passwordSealed.size)
        val rnd = Random(42)
        repeat(200) {
            val bio = rnd.nextBoolean()
            val kf = KeyFile(
                salt = rnd.nextBytes(rnd.nextInt(0, 40)),
                iterations = rnd.nextInt(1, 10_000_001),
                passwordSealed = rnd.nextBytes(rnd.nextInt(1, 100)),
                biometricIv = if (bio) rnd.nextBytes(12) else null,
                biometricSealed = if (bio) rnd.nextBytes(rnd.nextInt(1, 60)) else null,
                version = 1,
            )
            assertEquals(kf, KeyFile.fromJson(kf.toJson()))
        }
    }

    @Test
    fun serializedFormUsesTheDocumentedFieldNamesAndStandardBase64() {
        val o = JSONObject(full.toJson())
        assertEquals(setOf("version", "salt", "iterations", "passwordSealed", "biometricIv", "biometricSealed"), o.keys().asSequence().toSet())
        assertEquals("AQID", o.getString("salt")) // base64 of 01 02 03, so vault.json written by older builds stays readable
        assertEquals(600_000, o.getInt("iterations"))
        assertEquals(1, o.getInt("version"))
    }

    @Test
    fun aKeyFileBuiltLikeVaultCreateHoldsTheSealedDekAndNeverTheRawPasswordOrDek() {
        // The path Vault.create takes: derive the KEK from the password, seal the DEK under it, store salt and sealed DEK.
        val password = "correct horse battery staple"
        val dek = ByteArray(32) { (0xA0 + it).toByte() }
        val salt = byteArrayOf(1, 2, 3, 4)
        val kek = VaultCrypto.deriveKey(password.toCharArray(), salt, 1000)
        val json = KeyFile(salt, 1000, VaultCrypto.seal(kek, dek)).toJson()
        val o = JSONObject(json)
        val dekB64 = java.util.Base64.getEncoder().encodeToString(dek)
        val dekHex = dek.joinToString("") { "%02x".format(it) }
        assertFalse("the raw password", json.contains(password))
        assertFalse("the raw DEK (base64)", json.contains(dekB64))
        assertFalse("the raw DEK (hex)", json.contains(dekHex, ignoreCase = true))
        assertEquals(setOf("version", "salt", "iterations", "passwordSealed"), o.keys().asSequence().toSet())
        // what it holds is the DEK sealed under the password: read back, it opens to the DEK
        val back = KeyFile.fromJson(json)
        assertArrayEquals(dek, VaultCrypto.open(VaultCrypto.deriveKey(password.toCharArray(), back.salt, back.iterations), back.passwordSealed))
    }

    @Test
    fun aBiometricIvWithoutSealedKeyIsNotHalfWritten() {
        val half = KeyFile(byteArrayOf(1), 10, byteArrayOf(2), biometricIv = byteArrayOf(3), biometricSealed = null)
        val o = JSONObject(half.toJson())
        assertFalse(o.has("biometricIv"))
        assertFalse(o.has("biometricSealed"))
    }

    @Test
    fun missingRequiredFieldsFailCleanly() {
        for (field in listOf("salt", "iterations", "passwordSealed")) {
            val o = JSONObject(full.toJson())
            o.remove(field)
            assertFailsCleanly("missing $field", o.toString())
        }
    }

    @Test
    fun wrongTypesFailCleanly() {
        val o1 = JSONObject(full.toJson()).put("iterations", "lots")
        assertFailsCleanly("iterations not a number", o1.toString())
        val o2 = JSONObject(full.toJson()).put("salt", "!!!not base64!!!")
        assertFailsCleanly("salt not base64", o2.toString())
        val o3 = JSONObject(full.toJson()).put("passwordSealed", "AQ=")
        assertFailsCleanly("passwordSealed bad padding", o3.toString())
        val o4 = JSONObject(full.toJson()).put("biometricIv", "@@@@")
        assertFailsCleanly("biometricIv not base64", o4.toString())
    }

    @Test
    fun garbageAndTruncatedInputFailCleanly() {
        assertFailsCleanly("empty", "")
        assertFailsCleanly("whitespace", "   ")
        assertFailsCleanly("not json", "this is not json")
        assertFailsCleanly("empty object", "{}")
        assertFailsCleanly("array", "[1,2,3]")
        assertFailsCleanly("null literal", "null")
        val good = full.toJson()
        for (cut in listOf(1, good.length / 3, good.length / 2, good.length - 1)) {
            assertFailsCleanly("cut at $cut", good.substring(0, cut))
        }
        val rnd = Random(7)
        repeat(50) {
            val junk = String(CharArray(rnd.nextInt(1, 200)) { (rnd.nextInt(32, 127)).toChar() })
            assertFailsCleanly("random junk parsed as a key file: $junk", junk)
        }
    }

    @Test
    fun equalityIsFieldWise() {
        val same = KeyFile.fromJson(full.toJson())
        assertEquals(full, same)
        assertNotEquals(full, full.copy(salt = byteArrayOf(1, 2, 4)))
        assertNotEquals(full, full.copy(iterations = 600_001))
        assertNotEquals(full, full.copy(passwordSealed = byteArrayOf(9, 8, 6)))
        assertNotEquals(full, full.copy(biometricIv = null))
        assertNotEquals(full, full.copy(biometricSealed = byteArrayOf(5, 7)))
        assertNotEquals(full, full.copy(version = 2))
    }

    @Test
    fun missingVersionDefaultsToOne() {
        val o = JSONObject(full.toJson())
        o.remove("version")
        assertEquals(1, KeyFile.fromJson(o.toString()).version)
    }

    @Test
    fun unsupportedVersionsAreRejectedAndVersionOneIsAccepted() {
        for (v in listOf(0, 2, 99, -1, Int.MIN_VALUE, Int.MAX_VALUE)) {
            val o = JSONObject(full.toJson()).put("version", v)
            assertFailsCleanly("version $v", o.toString())
        }
        assertEquals(1, KeyFile.fromJson(JSONObject(full.toJson()).put("version", 1).toString()).version)
    }

    @Test
    fun iterationsOutsideTheSaneRangeAreRejected() {
        for (n in listOf(0, -1, Int.MIN_VALUE, 10_000_001, Int.MAX_VALUE)) {
            val o = JSONObject(full.toJson()).put("iterations", n)
            assertFailsCleanly("iterations $n", o.toString())
        }
    }

    @Test
    fun iterationsAtTheBoundsAreAccepted() {
        for (n in listOf(1, 2, 600_000, 9_999_999, 10_000_000)) {
            val o = JSONObject(full.toJson()).put("iterations", n)
            assertEquals(n, KeyFile.fromJson(o.toString()).iterations)
        }
    }

    @Test
    fun aRejectedFileDoesNotLeakPartialState() {
        // both checks happen on the whole document: a good version with bad iterations (and the reverse) is still rejected
        assertFailsCleanly("good version, bad iterations", JSONObject(full.toJson()).put("iterations", 0).toString())
        assertFailsCleanly("bad version, good iterations", JSONObject(full.toJson()).put("version", 2).toString())
    }
}
