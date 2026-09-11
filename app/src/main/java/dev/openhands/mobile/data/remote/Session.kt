package dev.openhands.mobile.data.remote

import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Holds the decrypted API token for the lifetime of an unlocked session.
 *
 * The token lives only in memory here; the encrypted copy on disk stays the source of
 * truth. Locking the app clears it so a later resume needs a new biometric unlock.
 */
@Singleton
class SessionHolder @Inject constructor() {
    private val token = AtomicReference<String?>(null)
    private val _unlocked = MutableStateFlow(false)
    val unlocked: StateFlow<Boolean> = _unlocked.asStateFlow()

    /**
     * Set when the server rejects the stored credential. A flow rather than an exception,
     * because an interceptor that throws gets wrapped in `IOException` by OkHttp and the
     * original type is lost by the time it reaches the caller.
     */
    private val _rejected = MutableStateFlow(false)
    val rejected: StateFlow<Boolean> = _rejected.asStateFlow()

    fun unlock(value: String) {
        token.set(value)
        _rejected.value = false
        _unlocked.value = true
    }

    fun lock() {
        token.set(null)
        _unlocked.value = false
    }

    fun markRejected() {
        _rejected.value = true
    }

    fun tokenOrNull(): String? = token.get()
}

/** Attaches bearer auth to app-server calls, leaving the device OAuth endpoints untouched. */
class AuthInterceptor @Inject constructor(private val session: SessionHolder) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val path = request.url.encodedPath
        if (path.startsWith("/oauth/device/")) return chain.proceed(request)

        val token = session.tokenOrNull() ?: return chain.proceed(request)
        return chain.proceed(
            request.newBuilder()
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/json")
                .build(),
        )
    }
}

/**
 * Flags a rejected credential so the UI can force re-authentication.
 *
 * The response is passed through unchanged; Retrofit still reports the HTTP error to the
 * caller, and the session flag drives the sign-in prompt separately.
 */
class UnauthorizedInterceptor @Inject constructor(
    private val session: SessionHolder,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        // Device OAuth returns 400/401 as normal flow control, so it is excluded.
        val isDeviceFlow = chain.request().url.encodedPath.startsWith("/oauth/device/")
        if (!isDeviceFlow && (response.code == 401 || response.code == 403)) {
            session.markRejected()
        }
        return response
    }
}
