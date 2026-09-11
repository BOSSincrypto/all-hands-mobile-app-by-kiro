package dev.openhands.mobile.data.repo

import dev.openhands.mobile.data.remote.AuthInterceptor
import dev.openhands.mobile.data.remote.DeviceAuthorizationResponse
import dev.openhands.mobile.data.remote.OpenHandsApi
import dev.openhands.mobile.data.remote.SessionHolder
import dev.openhands.mobile.data.remote.UnauthorizedInterceptor
import dev.openhands.mobile.security.TokenVault
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * Covers the device OAuth polling loop, which is the only part of auth that can be tested
 * without a device Keystore. The token vault itself needs instrumented tests.
 */
class DeviceLoginTest {

    private lateinit var server: MockWebServer
    private lateinit var repository: AuthRepository

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()

        val session = SessionHolder()
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }
        val client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(session))
            .addInterceptor(UnauthorizedInterceptor(session))
            .build()
        val api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(OpenHandsApi::class.java)

        // The vault is only touched when a token is persisted, which these tests do not do.
        repository = AuthRepository(api, TokenVault(NoContext), session, json)
    }

    @After
    fun tearDown() = server.close()

    private fun authorization(intervalSeconds: Int = 0, expiresIn: Int = 600) =
        DeviceAuthorizationResponse(
            deviceCode = "dc",
            userCode = "ABCD1234",
            verificationUri = "https://example/verify",
            verificationUriComplete = "https://example/verify?user_code=ABCD1234",
            expiresIn = expiresIn,
            interval = intervalSeconds,
        )

    private fun enqueue(body: String, code: Int) {
        server.enqueue(
            MockResponse.Builder()
                .code(code)
                .setHeader("Content-Type", "application/json")
                .body(body)
                .build(),
        )
    }

    @Test
    fun `polling keeps going while authorization is pending then succeeds`() = runTest {
        enqueue("""{"error":"authorization_pending"}""", 400)
        enqueue("""{"error":"authorization_pending"}""", 400)
        enqueue("""{"access_token":"sk-oh-final","token_type":"Bearer"}""", 200)

        val result = repository.pollForToken(authorization())

        assertTrue(result is DeviceLoginResult.Success)
        assertEquals("sk-oh-final", (result as DeviceLoginResult.Success).token)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `expired token stops the loop`() = runTest {
        enqueue("""{"error":"expired_token"}""", 400)

        val result = repository.pollForToken(authorization())

        assertEquals(DeviceLoginResult.Expired, result)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `access denied is reported with the server's description`() = runTest {
        enqueue(
            """{"error":"access_denied","error_description":"User declined the request"}""",
            400,
        )

        val result = repository.pollForToken(authorization())

        assertTrue(result is DeviceLoginResult.Denied)
        assertEquals("User declined the request", (result as DeviceLoginResult.Denied).reason)
    }

    @Test
    fun `slow down widens the interval instead of failing`() = runTest {
        enqueue("""{"error":"slow_down","interval":1}""", 400)
        enqueue("""{"access_token":"sk-oh-ok","token_type":"Bearer"}""", 200)

        val result = repository.pollForToken(authorization())

        assertTrue(result is DeviceLoginResult.Success)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `an already expired authorization is not polled at all`() = runTest {
        val result = repository.pollForToken(authorization(expiresIn = 0))

        assertEquals(DeviceLoginResult.Expired, result)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `device code is sent as a form field`() = runTest {
        enqueue("""{"access_token":"sk-oh-ok","token_type":"Bearer"}""", 200)

        repository.pollForToken(authorization())

        val request = server.takeRequest()
        assertEquals("application/x-www-form-urlencoded", request.headers["Content-Type"])
        assertEquals("device_code=dc", request.body!!.utf8())
    }
}

/**
 * TokenVault only reads `filesDir` when storing a token; these tests never do, so a stub
 * avoids pulling in Robolectric for this suite.
 */
private object NoContext : android.content.ContextWrapper(null)
