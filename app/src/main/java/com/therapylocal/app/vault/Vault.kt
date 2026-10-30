package com.therapylocal.app.vault

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import com.therapylocal.app.data.MindStore
import com.therapylocal.app.log.AppLog
import com.therapylocal.app.log.LogCtx
import com.therapylocal.app.memory.MemoryFiles
import com.therapylocal.app.memory.MemoryService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class VaultLockedException : IllegalStateException("Vault is locked")

enum class BiometricAvailability { Available, NotEnrolled, Unsupported }

/**
 * Holds the data key (DEK) that encrypts all patient data. The DEK exists in RAM only while unlocked; on disk it is sealed by a
 * password-derived key and optionally by a biometric-gated Android Keystore key (see [KeyFile]).
 */
object Vault {
    private const val KEY_FILE = "vault.json"
    private const val DEK_AAD = "mindvault.dek.v1"
    private const val BIOMETRIC_ALIAS = "mindvault.biometric"
    private const val TAG = "VAULT"
    private const val ERASE_WAIT_MS = 10_000L

    @Volatile
    private var dek: ByteArray? = null
    private val _unlocked = MutableStateFlow(false)
    val unlocked: StateFlow<Boolean> = _unlocked.asStateFlow()
    val isUnlocked: Boolean get() = dek != null

    private fun keyFile(c: Context) = File(c.noBackupFilesDir, KEY_FILE)
    private fun readKeyFile(c: Context): KeyFile = KeyFile.fromJson(keyFile(c).readText())
    private fun writeKeyFile(c: Context, kf: KeyFile) {
        val f = keyFile(c)
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, "$KEY_FILE.tmp")
        tmp.writeText(kf.toJson())
        if (!tmp.renameTo(f)) {
            f.delete()
            tmp.renameTo(f)
        }
    }

    fun isSetUp(context: Context): Boolean = keyFile(context).exists()

    /** The live DEK. Do not modify or keep it. */
    fun key(): ByteArray = dek ?: throw VaultLockedException()

    /** Fresh copy of the SQLCipher raw-key syntax x'<64 hex>' as UTF-8 bytes. */
    fun dbPassword(): ByteArray = "x'${hex(key())}'".toByteArray(Charsets.UTF_8)

    /** 8 hex chars of HMAC-SHA256(DEK, "log:" + value); "locked" while locked. */
    fun pseudonym(value: String): String {
        val k = dek ?: return "locked"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(k, "HmacSHA256"))
        return hex(mac.doFinal("log:$value".toByteArray(Charsets.UTF_8))).take(8)
    }

    // --- password path (blocking: KDF ~1 s) ---

    /** First-time setup: new DEK, key file, unlock. */
    fun create(context: Context, password: CharArray) {
        val newDek = VaultCrypto.randomBytes(VaultCrypto.KEY_BYTES)
        val salt = VaultCrypto.randomBytes(16)
        val kek = VaultCrypto.deriveKey(password, salt, VaultCrypto.PBKDF2_ITERATIONS)
        try {
            val sealed = VaultCrypto.seal(kek, newDek, DEK_AAD.toByteArray())
            writeKeyFile(context, KeyFile(salt, VaultCrypto.PBKDF2_ITERATIONS, sealed))
        } finally {
            kek.fill(0)
        }
        finishUnlock(context, newDek, "password")
    }

    /** False on wrong password. */
    fun unlock(context: Context, password: CharArray): Boolean {
        val kf = try {
            readKeyFile(context)
        } catch (e: Exception) {
            AppLog.e(TAG, "key_file_unreadable", t = e)
            return false
        }
        val kek = VaultCrypto.deriveKey(password, kf.salt, kf.iterations)
        val opened = try {
            VaultCrypto.open(kek, kf.passwordSealed, DEK_AAD.toByteArray())
        } catch (_: GeneralSecurityException) {
            return false
        } finally {
            kek.fill(0)
        }
        finishUnlock(context, opened, "password")
        return true
    }

    private fun finishUnlock(context: Context, key: ByteArray, method: String) {
        dek = key
        AppLog.pseudonymize = ::pseudonym
        _unlocked.value = true
        AppLog.i(TAG, "vault_unlocked", LogCtx.NONE, "method" to method)
    }

    // --- biometric path ---

    fun biometricAvailability(context: Context): BiometricAvailability =
        when (BiometricManager.from(context).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)) {
            BiometricManager.BIOMETRIC_SUCCESS -> BiometricAvailability.Available
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> BiometricAvailability.NotEnrolled
            else -> BiometricAvailability.Unsupported
        }

    fun isBiometricEnabled(context: Context): Boolean =
        isSetUp(context) && runCatching { readKeyFile(context).biometricSealed != null }.getOrDefault(false)

    /** Requires unlocked. (Re)creates the Keystore key; the returned cipher is in ENCRYPT_MODE and must be authorized by BiometricPrompt. */
    fun biometricEnrollCipher(): Cipher {
        key()
        val ks = keyStore()
        if (ks.containsAlias(BIOMETRIC_ALIAS)) ks.deleteEntry(BIOMETRIC_ALIAS)
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(BIOMETRIC_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setUserAuthenticationRequired(true)
                .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                .setInvalidatedByBiometricEnrollment(true)
                .build(),
        )
        val secret = gen.generateKey()
        return Cipher.getInstance("AES/GCM/NoPadding").also { it.init(Cipher.ENCRYPT_MODE, secret) }
    }

    /** [cipher] is the authorized result of [biometricEnrollCipher]. Requires unlocked. */
    fun completeBiometricEnroll(context: Context, cipher: Cipher) {
        val sealed = cipher.doFinal(key())
        writeKeyFile(context, readKeyFile(context).copy(biometricIv = cipher.iv, biometricSealed = sealed))
        AppLog.i(TAG, "biometric_enabled", LogCtx.NONE)
    }

    /** Null if biometric is not enabled or the key was invalidated (biometric is then disabled). */
    fun biometricUnlockCipher(context: Context): Cipher? {
        if (!isSetUp(context)) return null
        val kf = readKeyFile(context)
        val iv = kf.biometricIv ?: return null
        if (kf.biometricSealed == null) return null
        return try {
            val secret = keyStore().getKey(BIOMETRIC_ALIAS, null) as? SecretKey ?: throw GeneralSecurityException("key missing")
            Cipher.getInstance("AES/GCM/NoPadding").also { it.init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(128, iv)) }
        } catch (e: KeyPermanentlyInvalidatedException) {
            AppLog.w(TAG, "biometric_invalidated", LogCtx.NONE)
            disableBiometric(context)
            null
        } catch (e: Exception) {
            AppLog.w(TAG, "biometric_key_unavailable", LogCtx.NONE, "err" to e.javaClass.simpleName)
            disableBiometric(context)
            null
        }
    }

    /** [cipher] is the authorized result of [biometricUnlockCipher]. */
    fun completeBiometricUnlock(context: Context, cipher: Cipher): Boolean {
        val opened = try {
            cipher.doFinal(readKeyFile(context).biometricSealed ?: return false)
        } catch (_: GeneralSecurityException) {
            return false
        }
        finishUnlock(context, opened, "biometric")
        return true
    }

    fun disableBiometric(context: Context) {
        runCatching { keyStore().deleteEntry(BIOMETRIC_ALIAS) }
        if (isSetUp(context)) runCatching { writeKeyFile(context, readKeyFile(context).copy(biometricIv = null, biometricSealed = null)) }
    }

    // --- lifecycle ---

    /**
     * Wipes the DEK, relaunches the launcher activity in a fresh task and kills the process. A restart (rather than just clearing a flag)
     * guarantees no decrypted data lingers in ViewModels, singletons or caches.
     */
    fun lock(activity: Activity) {
        wipe()
        _unlocked.value = false
        val launch = activity.packageManager.getLaunchIntentForPackage(activity.packageName)!!
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        activity.startActivity(launch)
        activity.finishAffinity()
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    /**
     * "Forgot password": destroys the keys and all patient data. The runner is stopped (and given a moment to finish its step) and the
     * database closed before its file is deleted; a closed store never reopens, so a runner still holding it cannot re-create the file.
     */
    fun erase(context: Context) {
        MemoryService.stop(context)
        MemoryService.awaitStopped(ERASE_WAIT_MS)
        MindStore.close()
        wipe()
        _unlocked.value = false
        keyFile(context).delete()
        File(keyFile(context).parentFile, "$KEY_FILE.tmp").delete()
        runCatching { keyStore().deleteEntry(BIOMETRIC_ALIAS) }
        (MindStore.LEGACY + MindStore.NAME).forEach { context.deleteDatabase(it) }
        MemoryFiles.secureRoot(context).deleteRecursively()
        File(MemoryFiles.devRoot(context), "in").deleteRecursively()
        File(MemoryFiles.devRoot(context), "out").deleteRecursively()
        AppLog.i(TAG, "vault_erased", LogCtx.NONE)
    }

    private fun wipe() {
        dek?.fill(0)
        dek = null
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    internal fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }
}
