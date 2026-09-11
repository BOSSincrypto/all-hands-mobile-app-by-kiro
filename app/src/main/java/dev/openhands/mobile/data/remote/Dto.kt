package dev.openhands.mobile.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Wire models for the OpenHands Cloud V1 API, mirroring
 * https://app.all-hands.dev/openapi.json (spec version 1.59.1, checked 2026-09-11).
 */

@Serializable
data class DeviceAuthorizationResponse(
    @SerialName("device_code") val deviceCode: String,
    @SerialName("user_code") val userCode: String,
    @SerialName("verification_uri") val verificationUri: String,
    @SerialName("verification_uri_complete") val verificationUriComplete: String,
    @SerialName("expires_in") val expiresIn: Int,
    val interval: Int,
)

@Serializable
data class DeviceTokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("token_type") val tokenType: String = "Bearer",
)

@Serializable
data class DeviceTokenError(
    val error: String,
    @SerialName("error_description") val errorDescription: String? = null,
    val interval: Int? = null,
)

@Serializable
data class TextContent(
    val type: String = "text",
    val text: String,
)

@Serializable
data class SendMessageRequest(
    val role: String = "user",
    val content: List<TextContent>,
    val run: Boolean = true,
)

@Serializable
data class StartConversationRequest(
    @SerialName("initial_message") val initialMessage: SendMessageRequest? = null,
    @SerialName("selected_repository") val selectedRepository: String? = null,
    @SerialName("selected_branch") val selectedBranch: String? = null,
    @SerialName("git_provider") val gitProvider: String? = null,
    @SerialName("llm_model") val llmModel: String? = null,
    @SerialName("agent_profile_id") val agentProfileId: String? = null,
    val title: String? = null,
    /** Marks conversations created from this client, matching the API's own trigger enum. */
    val trigger: String? = "openhands_api",
)

/** Startup progress. `appConversationId` is only populated once [status] is READY. */
@Serializable
data class StartTask(
    val id: String,
    val status: String,
    val detail: String? = null,
    @SerialName("app_conversation_id") val appConversationId: String? = null,
    @SerialName("sandbox_id") val sandboxId: String? = null,
    @SerialName("agent_server_url") val agentServerUrl: String? = null,
)

@Serializable
data class TokenUsage(
    @SerialName("prompt_tokens") val promptTokens: Long? = null,
    @SerialName("completion_tokens") val completionTokens: Long? = null,
    @SerialName("cache_read_tokens") val cacheReadTokens: Long? = null,
    @SerialName("cache_write_tokens") val cacheWriteTokens: Long? = null,
    @SerialName("reasoning_tokens") val reasoningTokens: Long? = null,
    val context_window: Long? = null,
)

@Serializable
data class MetricsSnapshot(
    @SerialName("model_name") val modelName: String? = null,
    @SerialName("accumulated_cost") val accumulatedCost: Double = 0.0,
    @SerialName("max_budget_per_task") val maxBudgetPerTask: Double? = null,
    @SerialName("accumulated_token_usage") val tokenUsage: TokenUsage? = null,
)

@Serializable
data class AppConversation(
    val id: String,
    @SerialName("sandbox_id") val sandboxId: String? = null,
    val title: String? = null,
    @SerialName("selected_repository") val selectedRepository: String? = null,
    @SerialName("selected_branch") val selectedBranch: String? = null,
    @SerialName("git_provider") val gitProvider: String? = null,
    @SerialName("llm_model") val llmModel: String? = null,
    @SerialName("sandbox_status") val sandboxStatus: String = "MISSING",
    @SerialName("execution_status") val executionStatus: String? = null,
    @SerialName("conversation_url") val conversationUrl: String? = null,
    @SerialName("session_api_key") val sessionApiKey: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    val metrics: MetricsSnapshot? = null,
    @SerialName("pr_number") val prNumbers: List<Int> = emptyList(),
)

@Serializable
data class Page<T>(
    val items: List<T> = emptyList(),
    @SerialName("next_page_id") val nextPageId: String? = null,
)

/**
 * Conversation events are a large discriminated union; only the fields the UI renders
 * are typed, with the rest left as [JsonElement] so unknown event kinds never break parsing.
 */
@Serializable
data class ConversationEvent(
    val id: String,
    val kind: String,
    val source: String? = null,
    val timestamp: String? = null,
    @SerialName("tool_name") val toolName: String? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
    @SerialName("action_id") val actionId: String? = null,
    val summary: String? = null,
    @SerialName("security_risk") val securityRisk: String? = null,
    val thought: JsonElement? = null,
    val action: JsonElement? = null,
    val observation: JsonElement? = null,
    @SerialName("llm_message") val llmMessage: JsonElement? = null,
    val message: JsonElement? = null,
    val content: JsonElement? = null,
    val code: String? = null,
    val detail: String? = null,
)

@Serializable
data class Repository(
    val id: String,
    @SerialName("full_name") val fullName: String,
    @SerialName("git_provider") val gitProvider: String,
    @SerialName("is_public") val isPublic: Boolean = true,
    @SerialName("main_branch") val mainBranch: String? = null,
    @SerialName("pushed_at") val pushedAt: String? = null,
)

@Serializable
data class Branch(
    val name: String,
    @SerialName("commit_sha") val commitSha: String = "",
    val protected: Boolean = false,
)

@Serializable
data class LlmModel(
    val name: String,
    val provider: String? = null,
    val verified: Boolean = false,
    val hidden: Boolean = false,
)

@Serializable
data class CustomSecret(
    val id: String? = null,
    val name: String,
    val description: String? = null,
)

@Serializable
data class CreateSecretRequest(
    val name: String,
    val value: String,
    val description: String? = null,
)

@Serializable
data class UpdateConversationRequest(
    val title: String? = null,
)

@Serializable
data class ConfirmationResponseRequest(
    val accept: Boolean,
    val reason: String = "User rejected the action.",
)

@Serializable
data class ExecuteBashRequest(
    val command: String,
    val cwd: String? = null,
    val timeout: Int = 60,
)

@Serializable
data class BashEvent(
    val id: String? = null,
    val kind: String? = null,
    @SerialName("command_id") val commandId: String? = null,
    @SerialName("exit_code") val exitCode: Int? = null,
    val stdout: String? = null,
    val stderr: String? = null,
    val command: String? = null,
    val detail: String? = null,
)
