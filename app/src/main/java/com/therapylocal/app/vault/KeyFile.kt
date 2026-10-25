package com.therapylocal.app.vault

import org.json.JSONObject
import java.util.Base64

/** On-disk key material (vault.json): the data key sealed by the password KEK and, optionally, by the biometric Keystore key. */
data class KeyFile(
    val salt: ByteArray,
    val iterations: Int,
    val passwordSealed: ByteArray,
    val biometricIv: ByteArray? = null,
    val biometricSealed: ByteArray? = null,
    val version: Int = 1,
) {
    fun toJson(): String {
        val o = JSONObject()
        o.put("version", version)
        o.put("salt", b64(salt))
        o.put("iterations", iterations)
        o.put("passwordSealed", b64(passwordSealed))
        if (biometricIv != null && biometricSealed != null) {
            o.put("biometricIv", b64(biometricIv))
            o.put("biometricSealed", b64(biometricSealed))
        }
        return o.toString()
    }

    override fun equals(other: Any?): Boolean =
        other is KeyFile && version == other.version && iterations == other.iterations && salt.contentEquals(other.salt) &&
            passwordSealed.contentEquals(other.passwordSealed) && biometricIv.contentEquals(other.biometricIv) &&
            biometricSealed.contentEquals(other.biometricSealed)

    override fun hashCode(): Int = 31 * (31 * version + iterations) + salt.contentHashCode()

    companion object {
        fun fromJson(s: String): KeyFile {
            val o = JSONObject(s)
            val version = o.optInt("version", 1)
            require(version == SUPPORTED_VERSION) { "unsupported key file version $version" }
            val iterations = o.getInt("iterations")
            require(iterations in MIN_ITERATIONS..MAX_ITERATIONS) { "implausible iteration count $iterations" }
            return KeyFile(
                salt = unb64(o.getString("salt")),
                iterations = iterations,
                passwordSealed = unb64(o.getString("passwordSealed")),
                biometricIv = o.optString("biometricIv").takeIf { it.isNotEmpty() }?.let(::unb64),
                biometricSealed = o.optString("biometricSealed").takeIf { it.isNotEmpty() }?.let(::unb64),
                version = version,
            )
        }

        private const val SUPPORTED_VERSION = 1
        private const val MIN_ITERATIONS = 1
        private const val MAX_ITERATIONS = 10_000_000

        private fun b64(b: ByteArray) = Base64.getEncoder().encodeToString(b)
        private fun unb64(s: String) = Base64.getDecoder().decode(s)
    }
}
