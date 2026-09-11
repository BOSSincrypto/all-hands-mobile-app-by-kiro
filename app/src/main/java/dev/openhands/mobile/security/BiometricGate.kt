package dev.openhands.mobile.security

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import javax.crypto.Cipher
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/** Whether the device can satisfy the vault key's authentication requirement. */
enum class GateAvailability { AVAILABLE, NOT_ENROLLED, UNAVAILABLE }

sealed interface GateResult {
    /** Unlocked cipher, valid for exactly one crypto operation. */
    data class Success(val cipher: Cipher) : GateResult
    data object Cancelled : GateResult
    /** Biometric enrollment changed, so the key is gone and the user must sign in again. */
    data object KeyInvalidated : GateResult
    data class Failed(val message: String) : GateResult
}

private const val ALLOWED_AUTHENTICATORS =
    BiometricManager.Authenticators.BIOMETRIC_STRONG or
        BiometricManager.Authenticators.DEVICE_CREDENTIAL

fun BiometricManager.gateAvailability(): GateAvailability =
    when (canAuthenticate(ALLOWED_AUTHENTICATORS)) {
        BiometricManager.BIOMETRIC_SUCCESS -> GateAvailability.AVAILABLE
        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> GateAvailability.NOT_ENROLLED
        else -> GateAvailability.UNAVAILABLE
    }

/**
 * Shows the system biometric prompt and returns the cipher it unlocked.
 *
 * The cipher is passed into the prompt so the Keystore ties the unlock to this exact
 * operation; a result without a crypto object would not prove the key was unlocked.
 */
suspend fun FragmentActivity.authenticateForCipher(
    cipher: Cipher,
    title: String,
    subtitle: String,
    cancelLabel: String,
): GateResult = suspendCancellableCoroutine { continuation ->
    val prompt = BiometricPrompt(
        this,
        androidx.core.content.ContextCompat.getMainExecutor(this),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                val unlocked = result.cryptoObject?.cipher
                if (continuation.isActive) {
                    continuation.resume(
                        if (unlocked != null) {
                            GateResult.Success(unlocked)
                        } else {
                            GateResult.Failed("No crypto object returned")
                        },
                    )
                }
            }

            override fun onAuthenticationError(code: Int, message: CharSequence) {
                if (!continuation.isActive) return
                val cancelled = code == BiometricPrompt.ERROR_USER_CANCELED ||
                    code == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                    code == BiometricPrompt.ERROR_CANCELED
                continuation.resume(
                    if (cancelled) GateResult.Cancelled else GateResult.Failed(message.toString()),
                )
            }
        },
    )

    val info = BiometricPrompt.PromptInfo.Builder()
        .setTitle(title)
        .setSubtitle(subtitle)
        .setAllowedAuthenticators(ALLOWED_AUTHENTICATORS)
        .build()

    prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
    continuation.invokeOnCancellation { prompt.cancelAuthentication() }
}
