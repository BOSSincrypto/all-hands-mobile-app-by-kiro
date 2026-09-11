package dev.openhands.mobile.data.remote

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * Exercises the real Retrofit/OkHttp/serialization stack against a local server, so
 * serialization and auth wiring are verified rather than mocked. Response bodies mirror
 * the shapes returned by app.all-hands.dev (spec 1.59.1, checked 2026-09-11).
 */
class OpenHandsApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: OpenHandsApi
    private lateinit var session: SessionHolder

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()

        session = SessionHolder()
        val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            encodeDefaults = true
            coerceInputValues = true
        }
        val client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(session))
            .addInterceptor(UnauthorizedInterceptor(session))
            .build()

        api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(OpenHandsApi::class.java)
    }

    @After
    fun tearDown() = server.close()

    private fun enqueueJson(body: String, code: Int = 200) {
        server.enqueue(
            MockResponse.Builder()
                .code(code)
                .setHeader("Content-Type", "application/json")
                .body(body)
                .build(),
        )
    }

    @Test
    fun `start conversation parses a start task and omits null fields`() = runTest {
        session.unlock("sk-oh-test")
        enqueueJson("""{"id":"task-1","status":"WORKING"}""")

        val task = api.startConversation(
            StartConversationRequest(
                initialMessage = SendMessageRequest(content = listOf(TextContent(text = "hi"))),
                selectedRepository = "owner/repo",
            ),
        )

        assertEquals("task-1", task.id)
        assertEquals("WORKING", task.status)
        assertNull(task.appConversationId)

        val request = server.takeRequest()
        val body = request.body!!.utf8()
        assertTrue(body.contains("\"selected_repository\":\"owner/repo\""))
        assertTrue(body.contains("\"trigger\":\"openhands_api\""))
        // explicitNulls=false keeps unset optional fields off the wire.
        assertTrue("Unexpected nulls in payload: $body", !body.contains(":null"))
    }

    @Test
    fun `bearer token is attached to api calls`() = runTest {
        session.unlock("sk-oh-secret")
        enqueueJson("""{"items":[]}""")

        api.searchConversations()

        assertEquals("Bearer sk-oh-secret", server.takeRequest().headers["Authorization"])
    }

    @Test
    fun `device oauth endpoints are never sent the bearer token`() = runTest {
        // A stale session token must not leak into the unauthenticated device flow.
        session.unlock("sk-oh-stale")
        enqueueJson(
            """{"device_code":"dc","user_code":"ABCD1234",
                "verification_uri":"https://example/verify",
                "verification_uri_complete":"https://example/verify?user_code=ABCD1234",
                "expires_in":600,"interval":5}""",
        )

        val authorization = api.deviceAuthorize()

        assertEquals("ABCD1234", authorization.userCode)
        assertEquals(5, authorization.interval)
        assertNull(server.takeRequest().headers["Authorization"])
    }

    @Test
    fun `pending device token is returned as an unsuccessful response not an exception`() = runTest {
        enqueueJson(
            """{"error":"authorization_pending","error_description":"not yet"}""",
            code = 400,
        )

        val response = api.deviceToken("dc")

        assertEquals(400, response.code())
        assertNotNull(response.errorBody())
    }

    @Test
    fun `conversation record exposes agent server url and session key`() = runTest {
        session.unlock("sk-oh-test")
        enqueueJson(
            """[{"id":"conv-1","sandbox_id":"sb-1","sandbox_status":"RUNNING",
                "execution_status":"running","created_by_user_id":"u1",
                "conversation_url":"https://runtime.example/api/conversations/conv-1",
                "session_api_key":"sess-key","acp_server":null,
                "launched_agent_profile":null,
                "metrics":{"accumulated_cost":0.42,"model_name":"anthropic/claude-opus-5"}}]""",
        )

        val conversation = api.conversationsByIds("conv-1").single()

        assertEquals("RUNNING", conversation.sandboxStatus)
        assertEquals("sess-key", conversation.sessionApiKey)
        assertEquals(0.42, conversation.metrics?.accumulatedCost!!, 0.001)
    }

    @Test
    fun `unauthorized response flags the session without hiding the http error`() = runTest {
        session.unlock("sk-oh-expired")
        enqueueJson("""{"detail":"Not authenticated"}""", code = 401)

        // Retrofit still raises the HTTP error to the caller...
        val error = runCatching { api.searchConversations() }.exceptionOrNull()
        assertNotNull(error)
        // ...and the session is flagged so the UI can force re-authentication.
        assertTrue(session.rejected.value)
    }

    @Test
    fun `device flow 400 does not flag the session as rejected`() = runTest {
        // authorization_pending is normal flow control, not a bad credential.
        enqueueJson("""{"error":"authorization_pending"}""", code = 400)

        api.deviceToken("dc")

        assertFalse(session.rejected.value)
    }

    @Test
    fun `unknown response fields do not break parsing`() = runTest {
        session.unlock("sk-oh-test")
        // Server-side additions must not break an installed app.
        enqueueJson(
            """{"items":[{"id":"c1","sandbox_id":"s1","created_by_user_id":"u1",
                "acp_server":null,"launched_agent_profile":null,
                "brand_new_field":{"nested":true}}],"next_page_id":"page-2"}""",
        )

        val page = api.searchConversations()

        assertEquals("c1", page.items.single().id)
        assertEquals("page-2", page.nextPageId)
    }

    @Test
    fun `events search requests newest first and parses mixed event kinds`() = runTest {
        session.unlock("sk-oh-test")
        enqueueJson(
            """{"items":[
                {"id":"e1","kind":"ActionEvent","source":"agent","tool_name":"terminal"},
                {"id":"e2","kind":"ObservationEvent","source":"environment","action_id":"e1"}
               ]}""",
        )

        val page = api.searchEvents("conv-1", limit = 25)

        assertEquals(listOf("ActionEvent", "ObservationEvent"), page.items.map { it.kind })
        val url = server.takeRequest().url
        assertEquals("25", url.queryParameter("limit"))
        assertEquals("TIMESTAMP_DESC", url.queryParameter("sort_order"))
    }

    @Test
    fun `send message defaults to running the agent`() = runTest {
        session.unlock("sk-oh-test")
        server.enqueue(MockResponse.Builder().code(200).build())

        api.sendMessage("conv-1", SendMessageRequest(content = listOf(TextContent(text = "go"))))

        val body = server.takeRequest().body!!.utf8()
        assertTrue(body.contains("\"run\":true"))
        assertTrue(body.contains("\"role\":\"user\""))
    }

    @Test
    fun `hidden models are excluded from selection`() = runTest {
        session.unlock("sk-oh-test")
        enqueueJson(
            """{"items":[
                {"name":"anthropic/claude-opus-5","verified":true,"hidden":false},
                {"name":"legacy-alias","verified":false,"hidden":true,"canonical":"anthropic/claude-opus-5"}
               ]}""",
        )

        val visible = api.searchModels().items.filterNot { it.hidden }

        assertEquals(listOf("anthropic/claude-opus-5"), visible.map { it.name })
    }
}
