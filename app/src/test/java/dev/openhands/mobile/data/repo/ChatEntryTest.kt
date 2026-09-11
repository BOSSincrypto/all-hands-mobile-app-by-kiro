package dev.openhands.mobile.data.repo

import dev.openhands.mobile.data.remote.ConversationEvent
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Payload shapes below were captured from live conversation events on 2026-09-11 via
 * `GET /api/v1/conversation/{id}/events/search`, so the mapper is tested against the
 * real wire format rather than an assumed one.
 */
class ChatEntryTest {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private fun parse(raw: String): ConversationEvent =
        json.decodeFromString(ConversationEvent.serializer(), raw)

    @Test
    fun `user message becomes a user bubble`() {
        val entry = parse(
            """
            {"id":"1","kind":"MessageEvent","source":"user","timestamp":"2026-09-11T20:00:00",
             "message":{"role":"user","content":[{"type":"text","text":"Fix the README"}]}}
            """,
        ).toChatEntry()

        assertEquals(ChatRole.USER, entry?.role)
        assertEquals("Fix the README", entry?.text)
    }

    @Test
    fun `assistant message becomes an agent bubble`() {
        val entry = parse(
            """
            {"id":"2","kind":"MessageEvent","source":"assistant",
             "message":{"content":[{"type":"text","text":"Done. Opened a PR."}]}}
            """,
        ).toChatEntry()

        assertEquals(ChatRole.AGENT, entry?.role)
        assertEquals("Done. Opened a PR.", entry?.text)
    }

    @Test
    fun `action event prefers the agent's own summary over raw arguments`() {
        val entry = parse(
            """
            {"id":"3","kind":"ActionEvent","source":"agent","tool_name":"terminal",
             "summary":"List the repository files","security_risk":"LOW",
             "action":{"command":"ls -la /workspace"},"thought":[]}
            """,
        ).toChatEntry()

        assertEquals(ChatRole.TOOL, entry?.role)
        assertEquals("List the repository files", entry?.text)
        assertEquals("terminal", entry?.toolName)
        assertEquals("LOW", entry?.securityRisk)
    }

    @Test
    fun `action event falls back to the command when no summary is present`() {
        val entry = parse(
            """
            {"id":"4","kind":"ActionEvent","source":"agent","tool_name":"terminal",
             "action":{"command":"pytest -q"}}
            """,
        ).toChatEntry()

        assertEquals("pytest -q", entry?.text)
    }

    @Test
    fun `action thought is prepended to the summary`() {
        val entry = parse(
            """
            {"id":"5","kind":"ActionEvent","source":"agent","tool_name":"file_editor",
             "summary":"Edit config","thought":[{"type":"text","text":"Need to bump the version"}]}
            """,
        ).toChatEntry()

        assertTrue(entry!!.text.startsWith("Need to bump the version"))
        assertTrue(entry.text.endsWith("Edit config"))
    }

    @Test
    fun `empty thought array does not add a blank line`() {
        val entry = parse(
            """
            {"id":"6","kind":"ActionEvent","source":"agent","summary":"Run tests","thought":[]}
            """,
        ).toChatEntry()

        assertEquals("Run tests", entry?.text)
    }

    @Test
    fun `observation is collapsed by default because output can be long`() {
        val entry = parse(
            """
            {"id":"7","kind":"ObservationEvent","source":"environment","tool_name":"terminal",
             "observation":{"content":[{"type":"text","text":"total 24\ndrwxr-xr-x"}]},
             "action_id":"3"}
            """,
        ).toChatEntry()

        assertEquals(ChatRole.TOOL_RESULT, entry?.role)
        assertTrue(entry!!.collapsedByDefault)
        assertTrue(entry.text.contains("total 24"))
    }

    @Test
    fun `error event surfaces code and detail`() {
        val entry = parse(
            """
            {"id":"8","kind":"AgentErrorEvent","code":"LLMTimeout","detail":"Upstream timed out"}
            """,
        ).toChatEntry()

        assertEquals(ChatRole.ERROR, entry?.role)
        assertEquals("LLMTimeout: Upstream timed out", entry?.text)
    }

    @Test
    fun `noise events are dropped so the chat stays readable`() {
        listOf(
            """{"id":"9","kind":"SystemPromptEvent","source":"agent"}""",
            """{"id":"10","kind":"TokenEvent","source":"agent"}""",
            """{"id":"11","kind":"StreamingDeltaEvent","source":"agent"}""",
            """{"id":"12","kind":"LLMCompletionLogEvent","source":"agent"}""",
            """{"id":"13","kind":"ConversationStateUpdateEvent","source":"environment"}""",
        ).forEach { raw ->
            assertNull("Expected $raw to be dropped", parse(raw).toChatEntry())
        }
    }

    @Test
    fun `unknown future event kinds are ignored rather than crashing`() {
        val entry = parse("""{"id":"14","kind":"SomeFutureEvent","source":"agent"}""").toChatEntry()
        assertNull(entry)
    }

    @Test
    fun `message with no text yields no bubble`() {
        val entry = parse(
            """{"id":"15","kind":"MessageEvent","source":"assistant",
                "message":{"content":[{"type":"text","text":""}]}}""",
        ).toChatEntry()
        assertNull(entry)
    }

    @Test
    fun `very long output is truncated to keep rendering cheap`() {
        val long = "x".repeat(10_000)
        val entry = parse(
            """{"id":"16","kind":"ObservationEvent","source":"environment",
                "observation":{"content":[{"type":"text","text":"$long"}]}}""",
        ).toChatEntry()

        assertTrue("Expected truncation, got ${entry!!.text.length}", entry.text.length < 5_000)
        assertTrue(entry.text.endsWith("…"))
    }

    @Test
    fun `nested content is walked to find text`() {
        // Observations sometimes nest text several levels down inside content arrays.
        val entry = parse(
            """{"id":"17","kind":"ObservationEvent","source":"environment",
                "observation":{"content":[{"type":"text","text":"deep value"}],
                "extended_content":[]}}""",
        ).toChatEntry()

        assertEquals("deep value", entry?.text)
    }
}
