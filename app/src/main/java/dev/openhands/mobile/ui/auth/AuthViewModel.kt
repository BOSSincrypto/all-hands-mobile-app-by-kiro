package dev.openhands.mobile.ui.auth

import android.security.keystore.KeyPermanentlyInvalidatedException
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.openhands.mobile.data.remote.DeviceAuthorizationResponse
import dev.openhands.mobile.data.repo.AuthRepository
import dev.openhands.mobile.data.repo.ConversationRepository
import dev.openhands.mobile.data.repo.DeviceLoginResult
import javax.crypto.Cipher
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the auth surface should show. Mutually exclusive by construction. */
sealed interface AuthUiState {
    /** Deciding whether a credential exists; nothing interactive yet. */
    data object Loading : AuthUiState

    /** No credential stored: offer browser sign-in. */
    data class SignInRequired(val error: String? = null) : AuthUiState

    /** Device code issued; waiting for browser approval. */
    data class AwaitingApproval(
        val authorization: DeviceAuthorizationResponse,
        val opened: Boolean = false,
    ) : AuthUiState

    /** Credential exists but the session is locked behind biometrics. */
    data class Locked(val error: String? = null) : AuthUiState

    data object Unlocked : AuthUiState
}

/** A crypto operation that must be wrapped in a biometric prompt by the Activity. */
data class CipherRequest(
    val cipher: Cipher,
    val purpose: Purpose,
    /** Token to persist once the cipher is unlocked; null when decrypting. */
    val pendingToken: String? = null,
) {
    enum class Purpose { ENCRYPT, DECRYPT }
}

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val auth: AuthRepository,
    private val conversations: ConversationRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<AuthUiState>(AuthUiState.Loading)
    val state: StateFlow<AuthUiState> = _state.asStateFlow()

    private val _cipherRequest = MutableStateFlow<CipherRequest?>(null)
    val cipherRequest: StateFlow<CipherRequest?> = _cipherRequest.asStateFlow()

    private var pollJob: Job? = null

    init {
        _state.value = if (auth.hasStoredToken) AuthUiState.Locked() else AuthUiState.SignInRequired()
        observeCredentialRejection()
    }

    /** Drops to sign-in as soon as any API call reports the credential is no longer valid. */
    private fun observeCredentialRejection() {
        viewModelScope.launch {
            auth.credentialRejected.collect { rejected ->
                if (rejected && _state.value is AuthUiState.Unlocked) onUnauthorized()
            }
        }
    }

    /** Requests a device code and starts polling for approval. */
    fun startSignIn() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            val authorization = runCatching { auth.beginDeviceLogin() }.getOrElse {
                _state.value = AuthUiState.SignInRequired(it.message ?: "Could not reach OpenHands")
                return@launch
            }
            _state.value = AuthUiState.AwaitingApproval(authorization)

            when (val result = auth.pollForToken(authorization)) {
                is DeviceLoginResult.Success -> requestEncryptCipher(result.token)
                DeviceLoginResult.Expired ->
                    _state.value = AuthUiState.SignInRequired("The code expired. Try again.")

                is DeviceLoginResult.Denied ->
                    _state.value = AuthUiState.SignInRequired(result.reason)
            }
        }
    }

    fun markBrowserOpened() {
        _state.update { current ->
            if (current is AuthUiState.AwaitingApproval) current.copy(opened = true) else current
        }
    }

    fun cancelSignIn() {
        pollJob?.cancel()
        _state.value = AuthUiState.SignInRequired()
    }

    /** Begins the unlock path for an existing credential. */
    fun requestUnlock() {
        val cipher = runCatching { auth.newDecryptCipher() }.getOrElse { error ->
            if (error is KeyPermanentlyInvalidatedException) {
                // Biometric enrollment changed, so the key and token are unusable.
                auth.signOut()
                _state.value = AuthUiState.SignInRequired(
                    "Biometrics changed on this device. Please sign in again.",
                )
            } else {
                _state.value = AuthUiState.Locked(error.message)
            }
            return
        }
        _cipherRequest.value = CipherRequest(cipher, CipherRequest.Purpose.DECRYPT)
    }

    /** Called by the Activity once the biometric prompt unlocked the cipher. */
    fun onCipherUnlocked(request: CipherRequest, unlocked: Cipher) {
        _cipherRequest.value = null
        when (request.purpose) {
            CipherRequest.Purpose.ENCRYPT -> {
                val token = request.pendingToken ?: return
                auth.persistToken(unlocked, token)
                _state.value = AuthUiState.Unlocked
            }

            CipherRequest.Purpose.DECRYPT -> {
                _state.value = if (auth.unlockSession(unlocked)) {
                    AuthUiState.Unlocked
                } else {
                    AuthUiState.Locked("Could not decrypt the stored credential")
                }
            }
        }
    }

    fun onCipherCancelled() {
        _cipherRequest.value = null
    }

    fun onKeyInvalidated() {
        _cipherRequest.value = null
        auth.signOut()
        _state.value = AuthUiState.SignInRequired(
            "Biometrics changed on this device. Please sign in again.",
        )
    }

    fun onGateFailed(message: String) {
        _cipherRequest.value = null
        _state.update { current ->
            if (current is AuthUiState.Locked) current.copy(error = message) else current
        }
    }

    /** Clears the in-memory session so returning to the app requires biometrics again. */
    fun lock() {
        if (_state.value is AuthUiState.Unlocked) {
            auth.lock()
            _state.value = AuthUiState.Locked()
        }
    }

    fun signOut() {
        pollJob?.cancel()
        viewModelScope.launch {
            conversations.clearCache()
            auth.signOut()
            _state.value = AuthUiState.SignInRequired()
        }
    }

    /** Forces re-authentication when the server rejects the stored credential. */
    fun onUnauthorized() {
        auth.signOut()
        _state.value = AuthUiState.SignInRequired("OpenHands rejected the saved credential.")
    }

    private fun requestEncryptCipher(token: String) {
        val cipher = runCatching { auth.newEncryptCipher() }.getOrElse {
            _state.value = AuthUiState.SignInRequired(it.message ?: "Secure storage unavailable")
            return
        }
        _cipherRequest.value = CipherRequest(cipher, CipherRequest.Purpose.ENCRYPT, token)
    }
}
