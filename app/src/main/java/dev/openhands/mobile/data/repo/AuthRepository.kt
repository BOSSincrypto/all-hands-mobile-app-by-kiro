package dev.openhands.mobile.data.repo

import dev.openhands.mobile.data.remote.DeviceAuthorizationResponse
import dev.openhands.mobile.data.remote.DeviceTokenError
import dev.openhands.mobile.data.remote.OpenHandsApi
import dev.openhands.mobile.data.remote.SessionHolder
import dev.openhands.mobile.security.TokenVault
import javax.crypto.Cipher
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json

sealed interface DeviceLoginResult {
    data class Success(val token: String) : DeviceLoginResult
    data object Expired : DeviceLoginResult
    data class Denied(val reason: String) : DeviceLoginResult
}

/**
 * Owns the device OAuth flow and the encrypted token.
 *
 * The flow avoids ever asking the user to paste an API key on a phone keyboard: the app
 * gets a short user code, the user approves it in a real browser session, and the app
 * exchanges it for a token.
 */
@Singleton
class AuthRepository @Inject constructor(
    private val api: OpenHandsApi,
    private val vault: TokenVault,
    private val session: SessionHolder,
    private val json: Json,
) {

    val hasStoredToken: Boolean get() = vault.hasToken()

    /** Emits true when the server rejected the stored credential, so the UI must re-auth. */
    val credentialRejected: StateFlow<Boolean> = session.rejected

    suspend fun beginDeviceLogin(): DeviceAuthorizationResponse = api.deviceAuthorize()

    /**
     * Polls the token endpoint until the user approves or the code expires.
     *
     * The server honours `interval` and returns `slow_down` when polled too fast, so the
     * interval is widened on that signal rather than retried blindly.
     */
    suspend fun pollForToken(authorization: DeviceAuthorizationResponse): DeviceLoginResult {
        var intervalMs = authorization.interval.coerceAtLeast(1) * 1_000L
        val deadline = System.currentTimeMillis() + authorization.expiresIn * 1_000L

        while (System.currentTimeMillis() < deadline) {
            val response = api.deviceToken(authorization.deviceCode)
            val body = response.body()
            if (response.isSuccessful && body != null) {
                return DeviceLoginResult.Success(body.accessToken)
            }

            val error = response.errorBody()?.string()?.let { raw ->
                runCatching { json.decodeFromString(DeviceTokenError.serializer(), raw) }.getOrNull()
            }
            when (error?.error) {
                "authorization_pending" -> Unit
                "slow_down" -> intervalMs += (error.interval?.times(1_000L) ?: 5_000L)
                "expired_token" -> return DeviceLoginResult.Expired
                null -> Unit
                else -> return DeviceLoginResult.Denied(
                    error.errorDescription ?: error.error,
                )
            }
            delay(intervalMs)
        }
        return DeviceLoginResult.Expired
    }

    /** Encrypts and persists the token, then opens the in-memory session. */
    fun persistToken(cipher: Cipher, token: String) {
        vault.store(cipher, token)
        session.unlock(token)
    }

    /** Decrypts the stored token with a biometric-unlocked cipher and opens the session. */
    fun unlockSession(cipher: Cipher): Boolean {
        val token = runCatching { vault.retrieve(cipher) }.getOrNull() ?: return false
        session.unlock(token)
        return true
    }

    fun newEncryptCipher(): Cipher = vault.newEncryptCipher()

    fun newDecryptCipher(): Cipher = vault.newDecryptCipher()

    fun lock() = session.lock()

    /** Removes the credential and its key. Used for sign-out and invalidated keys. */
    fun signOut() {
        session.lock()
        vault.clear()
    }
}
