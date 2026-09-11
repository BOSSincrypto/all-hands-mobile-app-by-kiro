package dev.openhands.mobile.data.repo

import dev.openhands.mobile.data.remote.ConversationEvent
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

enum class ChatRole { USER, AGENT, TOOL, TOOL_RESULT, ERROR, SYSTEM }

/** A single renderable line of conversation history. */
data class ChatEntry(
    val id: String,
    val role: ChatRole,
    val text: String,
    val toolName: String? = null,
    val timestamp: String? = null,
    val securityRisk: String? = null,
    val collapsedByDefault: Boolean = false,
)

private const val MAX_RENDERED_CHARS = 4_000

/**
 * Projects an API event onto a chat line, or null for events with nothing to show.
 *
 * Event kinds the UI does not render (system prompts, condensation bookkeeping, token
 * counters, streaming deltas) are dropped here so the chat stays readable and the
 * renderer never has to special-case them.
 */
fun ConversationEvent.toChatEntry(): ChatEntry? = when (kind) {
    "MessageEvent" -> {
        val body = extractText(message ?: llmMessage ?: content)
        if (body.isBlank()) {
            null
        } else {
            ChatEntry(
                id = id,
                role = if (source == "user") ChatRole.USER else ChatRole.AGENT,
                text = body,
                timestamp = timestamp,
            )
        }
    }

    "ActionEvent", "ACPToolCallEvent" -> {
        // `summary` is the agent's own one-line description; fall back to the raw action.
        val label = summary?.takeIf { it.isNotBlank() }
            ?: extractText(action).takeIf { it.isNotBlank() }
            ?: toolName
            ?: "action"
        val thoughtText = extractText(thought)
        ChatEntry(
            id = id,
            role = ChatRole.TOOL,
            text = if (thoughtText.isBlank()) label else "$thoughtText\n\n$label",
            toolName = toolName,
            timestamp = timestamp,
            securityRisk = securityRisk,
        )
    }

    "ObservationEvent" -> {
        val body = extractText(observation)
        if (body.isBlank()) {
            null
        } else {
            ChatEntry(
                id = id,
                role = ChatRole.TOOL_RESULT,
                text = body,
                toolName = toolName,
                timestamp = timestamp,
                collapsedByDefault = true,
            )
        }
    }

    "AgentErrorEvent", "ConversationErrorEvent", "ServerErrorEvent" -> ChatEntry(
        id = id,
        role = ChatRole.ERROR,
        text = listOfNotNull(code, detail ?: extractText(content)).joinToString(": ")
            .ifBlank { "Error" },
        timestamp = timestamp,
    )

    "UserRejectObservation" -> ChatEntry(
        id = id,
        role = ChatRole.SYSTEM,
        text = extractText(observation).ifBlank { "Action rejected" },
        timestamp = timestamp,
    )

    "PauseEvent" -> ChatEntry(id, ChatRole.SYSTEM, "Paused", timestamp = timestamp)
    "InterruptEvent" -> ChatEntry(id, ChatRole.SYSTEM, "Interrupted", timestamp = timestamp)
    "CondensationSummaryEvent" -> extractText(content).takeIf { it.isNotBlank() }?.let {
        ChatEntry(id, ChatRole.SYSTEM, it, timestamp = timestamp, collapsedByDefault = true)
    }

    else -> null
}

/**
 * Pulls display text out of the API's nested content shapes.
 *
 * Payloads vary by event kind: a bare string, `{text: ...}`, `{content: [{text: ...}]}`,
 * or a tool-specific object such as `{command: ...}`. Walking the tree for known text
 * keys handles all of them without a schema per tool.
 */
internal fun extractText(element: JsonElement?): String {
    val collected = mutableListOf<String>()
    collect(element, collected, depth = 0)
    return collected.joinToString("\n").trim().let {
        if (it.length > MAX_RENDERED_CHARS) it.take(MAX_RENDERED_CHARS) + "\n…" else it
    }
}

private val TEXT_KEYS = listOf("text", "command", "content", "message", "stdout", "output", "path")

private fun collect(element: JsonElement?, into: MutableList<String>, depth: Int) {
    if (element == null || depth > 6 || into.sumOf { it.length } > MAX_RENDERED_CHARS) return
    when (element) {
        is JsonPrimitive -> element.contentOrNull()?.takeIf { it.isNotBlank() && it != "null" }
            ?.let(into::add)

        is JsonArray -> element.forEach { collect(it, into, depth + 1) }

        is JsonObject -> {
            val matched = TEXT_KEYS.filter { element.containsKey(it) }
            if (matched.isEmpty()) {
                element.values.forEach { collect(it, into, depth + 1) }
            } else {
                matched.forEach { collect(element[it], into, depth + 1) }
            }
        }
    }
}

private fun JsonPrimitive.contentOrNull(): String? = runCatching { content }.getOrNull()
