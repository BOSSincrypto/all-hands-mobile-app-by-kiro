package dev.openhands.mobile.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.security.KeyStore
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stores the OpenHands API token encrypted under a hardware-backed AES-256/GCM key.
 *
 * androidx.security-crypto deprecated its whole API surface in favour of using the
 * Android Keystore directly, so this talks to the Keystore itself.
 *
 * The key requires user authentication, so the ciphertext cannot be decrypted without a
 * fresh biometric/device-credential unlock. Enrolling a new biometric invalidates the key
 * and the stored token becomes unreadable, which is the desired failure mode: re-authenticate.
 */
@Singleton
class TokenVault @Inject constructor(private val context: Context) {

    private val file: File get() = File(context.filesDir, TOKEN_FILE)

    /** True when a token is stored, regardless of whether it can currently be decrypted. */
    fun hasToken(): Boolean = file.exists()

    /**
     * Encrypts and persists [token]. Requires a [Cipher] already unlocked via biometric prompt
     * and initialised for encryption by [newEncryptCipher].
     */
    fun store(cipher: Cipher, token: String) {
        val ciphertext = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        // IV length is written first so decryption does not depend on a hardcoded size.
        val iv = cipher.iv
        val payload = ByteArray(1 + iv.size + ciphertext.size)
        payload[0] = iv.size.toByte()
        iv.copyInto(payload, 1)
        ciphertext.copyInto(payload, 1 + iv.size)
        file.writeBytes(Base64.encode(payload, Base64.NO_WRAP))
        restrictToOwner(file)
    }

    /** Decrypts the stored token using a [Cipher] from [newDecryptCipher]. */
    fun retrieve(cipher: Cipher): String {
        val payload = Base64.decode(file.readBytes(), Base64.NO_WRAP)
        val ivSize = payload[0].toInt()
        val ciphertext = payload.copyOfRange(1 + ivSize, payload.size)
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    /** Creates a cipher for encryption, generating the Keystore key if absent. */
    fun newEncryptCipher(): Cipher = Cipher.getInstance(TRANSFORMATION).apply {
        init(Cipher.ENCRYPT_MODE, getOrCreateKey())
    }

    /**
     * Creates a cipher for decryption bound to the stored IV.
     *
     * @throws KeyPermanentlyInvalidatedException when biometrics changed since the token was stored.
     */
    fun newDecryptCipher(): Cipher {
        val payload = Base64.decode(file.readBytes(), Base64.NO_WRAP)
        val ivSize = payload[0].toInt()
        val iv = payload.copyOfRange(1, 1 + ivSize)
        return Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, existingKey(), GCMParameterSpec(TAG_BITS, iv))
        }
    }

    /** Wipes the token and its key. Used for sign-out and for recovering from an invalidated key. */
    fun clear() {
        file.delete()
        runCatching {
            KeyStore.getInstance(KEYSTORE).apply { load(null) }.deleteEntry(KEY_ALIAS)
        }
    }

    private fun existingKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        return keyStore.getKey(KEY_ALIAS, null) as? SecretKey
            ?: throw GeneralSecurityException("Vault key missing")
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_SIZE_BITS)
            .setUserAuthenticationRequired(true)
            // Timeout 0 with BIOMETRIC_STRONG|DEVICE_CREDENTIAL means every crypto operation
            // needs its own unlock, so a stolen unlocked phone cannot silently reuse the key.
            .setUserAuthenticationParameters(
                0,
                KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
            )
            .setInvalidatedByBiometricEnrollment(true)
            .build()

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
            .apply { init(spec) }
            .generateKey()
    }

    private fun restrictToOwner(target: File) {
        target.setReadable(false, false)
        target.setWritable(false, false)
        target.setReadable(true, true)
        target.setWritable(true, true)
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "openhands_token_key_v1"
        const val TOKEN_FILE = "openhands_token.bin"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
        const val KEY_SIZE_BITS = 256
    }
}
