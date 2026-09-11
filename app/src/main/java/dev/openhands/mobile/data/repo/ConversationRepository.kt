package dev.openhands.mobile.data.repo

import dev.openhands.mobile.data.local.CachedConversation
import dev.openhands.mobile.data.local.CachedEvent
import dev.openhands.mobile.data.local.OpenHandsDatabase
import dev.openhands.mobile.data.remote.AgentServerApi
import dev.openhands.mobile.data.remote.AppConversation
import dev.openhands.mobile.data.remote.Branch
import dev.openhands.mobile.data.remote.ConfirmationResponseRequest
import dev.openhands.mobile.data.remote.ConversationEvent
import dev.openhands.mobile.data.remote.CreateSecretRequest
import dev.openhands.mobile.data.remote.CustomSecret
import dev.openhands.mobile.data.remote.EventStreamClient
import dev.openhands.mobile.data.remote.ExecuteBashRequest
import dev.openhands.mobile.data.remote.LlmModel
import dev.openhands.mobile.data.remote.OpenHandsApi
import dev.openhands.mobile.data.remote.Repository
import dev.openhands.mobile.data.remote.SendMessageRequest
import dev.openhands.mobile.data.remote.StartConversationRequest
import dev.openhands.mobile.data.remote.StartTask
import dev.openhands.mobile.data.remote.StreamMessage
import dev.openhands.mobile.data.remote.TextContent
import dev.openhands.mobile.data.remote.UpdateConversationRequest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

/** Result of a conversation start attempt, once it reaches a terminal start state. */
sealed interface StartOutcome {
    data class Ready(val conversationId: String) : StartOutcome
    data class Failed(val detail: String) : StartOutcome
}

private const val EVENT_HISTORY_LIMIT = 400

@Singleton
class ConversationRepository @Inject constructor(
    private val api: OpenHandsApi,
    private val agentApi: AgentServerApi,
    private val db: OpenHandsDatabase,
    private val streamClient: EventStreamClient,
    private val json: Json,
) {

    fun observeConversations(): Flow<List<ConversationSummary>> =
        db.conversations().observeAll().map { rows -> rows.map { it.toSummary() } }

    fun observeConversation(id: String): Flow<ConversationSummary?> =
        db.conversations().observeOne(id).map { it?.toSummary() }

    fun observeEvents(id: String): Flow<List<ChatEntry>> =
        db.events().observeFor(id).map { rows -> rows.mapNotNull { it.toChatEntry() } }

    /** Fetches the newest page of conversations and refreshes the cache. */
    suspend fun refreshConversations(limit: Int = 30) {
        val page = api.searchConversations(limit = limit)
        db.conversations().upsert(page.items.map { it.toCached() })
    }

    /** Refreshes a single conversation, returning the live record for status decisions. */
    suspend fun refreshConversation(id: String): AppConversation? {
        val conversation = api.conversationsByIds(id).firstOrNull() ?: return null
        db.conversations().upsert(listOf(conversation.toCached()))
        return conversation
    }

    /**
     * Loads recent history. The default matches the API's own page size; older events stay
     * on the server rather than being paged in, since [EVENT_HISTORY_LIMIT] caps what the
     * device keeps anyway.
     */
    suspend fun refreshEvents(id: String, limit: Int = 100) {
        val page = api.searchEvents(id = id, limit = limit)
        cacheEvents(id, page.items)
    }

    suspend fun startConversation(
        prompt: String,
        repository: String?,
        branch: String?,
        gitProvider: String?,
        model: String?,
        title: String?,
    ): StartTask = api.startConversation(
        StartConversationRequest(
            initialMessage = SendMessageRequest(content = listOf(TextContent(text = prompt))),
            selectedRepository = repository,
            selectedBranch = branch,
            gitProvider = gitProvider,
            llmModel = model,
            title = title,
        ),
    )

    /**
     * Polls a start task until it is READY or ERROR.
     *
     * Sandbox provisioning plus repository setup regularly takes minutes, so the caller
     * runs this inside a foreground service to survive the app being swiped away.
     */
    suspend fun awaitStart(
        startTaskId: String,
        onProgress: (StartStatus) -> Unit = {},
        pollInterval: Long = 3_000,
        maxAttempts: Int = 200,
    ): StartOutcome {
        repeat(maxAttempts) {
            val task = api.startTasks(startTaskId).firstOrNull()
            val status = StartStatus.from(task?.status)
            onProgress(status)

            val conversationId = task?.appConversationId
            if (status == StartStatus.READY && conversationId != null) {
                refreshConversation(conversationId)
                return StartOutcome.Ready(conversationId)
            }
            if (status == StartStatus.ERROR) {
                return StartOutcome.Failed(task?.detail ?: "Conversation start failed")
            }
            delay(pollInterval)
        }
        return StartOutcome.Failed("Timed out waiting for the sandbox")
    }

    /**
     * Sends a follow-up message. When the agent loop is not running, the direct endpoint
     * is not applicable, so the message is queued instead.
     */
    suspend fun sendMessage(id: String, text: String, agentRunning: Boolean) {
        val body = SendMessageRequest(content = listOf(TextContent(text = text)), run = true)
        if (agentRunning) {
            api.sendMessage(id, body)
        } else {
            api.queuePendingMessage(id, body)
        }
    }

    suspend fun rename(id: String, title: String) {
        api.updateConversation(id, UpdateConversationRequest(title = title))
        refreshConversation(id)
    }

    suspend fun delete(id: String) {
        api.deleteConversation(id)
        db.conversations().delete(id)
    }

    suspend fun pauseSandbox(sandboxId: String) = api.pauseSandbox(sandboxId)

    suspend fun resumeSandbox(sandboxId: String) = api.resumeSandbox(sandboxId)

    /** Live events for a running conversation, cached as they arrive. */
    fun streamEvents(conversation: AppConversation): Flow<StreamMessage>? {
        val agentUrl = conversation.agentServerBaseUrl() ?: return null
        val key = conversation.sessionApiKey ?: return null
        return streamClient.stream(agentUrl, conversation.id, key)
    }

    suspend fun cacheStreamedEvent(conversationId: String, event: ConversationEvent) =
        cacheEvents(conversationId, listOf(event))

    /** Accepts or rejects an action the agent is waiting on. */
    suspend fun respondToConfirmation(conversation: AppConversation, accept: Boolean) {
        val base = conversation.agentServerBaseUrl() ?: return
        val key = conversation.sessionApiKey ?: return
        agentApi.respondToConfirmation(
            url = "$base/api/conversations/${conversation.id}/events/respond_to_confirmation",
            sessionKey = key,
            body = ConfirmationResponseRequest(accept = accept),
        )
    }

    suspend fun pauseAgent(conversation: AppConversation) {
        val base = conversation.agentServerBaseUrl() ?: return
        val key = conversation.sessionApiKey ?: return
        agentApi.pauseConversation("$base/api/conversations/${conversation.id}/pause", key)
    }

    /** Runs a shell command in the conversation's sandbox and returns combined output. */
    suspend fun runCommand(conversation: AppConversation, command: String): String {
        val base = conversation.agentServerBaseUrl() ?: return "No sandbox available"
        val key = conversation.sessionApiKey ?: return "No sandbox session"
        val result = agentApi.executeBash(
            url = "$base/api/bash/execute_bash_command",
            sessionKey = key,
            body = ExecuteBashRequest(command = command),
        )
        return listOfNotNull(
            result.stdout?.takeIf { it.isNotBlank() },
            result.stderr?.takeIf { it.isNotBlank() },
            result.detail?.takeIf { it.isNotBlank() },
            result.exitCode?.let { "exit=$it" },
        ).joinToString("\n")
    }

    suspend fun listFiles(id: String, path: String?): List<String> = api.listFiles(id, path)

    suspend fun readFile(id: String, path: String): String = api.readFile(id, path)

    suspend fun gitChanges(id: String, path: String): List<Map<String, String>> =
        api.gitChanges(id, path)

    suspend fun searchRepositories(provider: String, query: String?): List<Repository> =
        api.searchRepositories(provider = provider, query = query).items

    suspend fun searchBranches(provider: String, repository: String, query: String): List<Branch> =
        api.searchBranches(provider = provider, repository = repository, query = query).items

    /** Selectable models, excluding entries the server marks as hidden. */
    suspend fun models(): List<LlmModel> =
        api.searchModels().items.filterNot { it.hidden }.sortedWith(
            compareByDescending<LlmModel> { it.verified }.thenBy { it.name },
        )

    suspend fun secrets(): List<CustomSecret> = api.searchSecrets().items

    suspend fun createSecret(name: String, value: String, description: String?) =
        api.createSecret(CreateSecretRequest(name = name, value = value, description = description))

    suspend fun deleteSecret(id: String) = api.deleteSecret(id)

    /** Drops all cached data. Called on sign-out so nothing survives for the next user. */
    suspend fun clearCache() {
        db.events().clear()
        db.conversations().clear()
    }

    private suspend fun cacheEvents(conversationId: String, events: List<ConversationEvent>) {
        if (events.isEmpty()) return
        db.events().upsert(
            events.map { event ->
                CachedEvent(
                    id = event.id,
                    conversationId = conversationId,
                    kind = event.kind,
                    source = event.source,
                    timestamp = event.timestamp,
                    toolName = event.toolName,
                    summary = event.summary,
                    body = json.encodeToString(ConversationEvent.serializer(), event),
                )
            },
        )
        db.events().trim(conversationId, EVENT_HISTORY_LIMIT)
    }

    private fun CachedEvent.toChatEntry(): ChatEntry? {
        val event = runCatching {
            json.decodeFromString(ConversationEvent.serializer(), body)
        }.getOrNull() ?: return null
        return event.toChatEntry()
    }
}

/**
 * Derives the agent server origin from the conversation URL, which has the form
 * `https://<host>/api/conversations/<id>`.
 */
fun AppConversation.agentServerBaseUrl(): String? =
    conversationUrl?.substringBefore("/api/conversations", missingDelimiterValue = "")
        ?.takeIf { it.isNotBlank() }

private fun AppConversation.toCached(): CachedConversation = CachedConversation(
    id = id,
    title = title,
    repository = selectedRepository,
    branch = selectedBranch,
    model = llmModel ?: metrics?.modelName,
    sandboxStatus = sandboxStatus,
    executionStatus = executionStatus,
    updatedAt = updatedAt,
    costUsd = metrics?.accumulatedCost,
    cachedAt = System.currentTimeMillis(),
)

private fun CachedConversation.toSummary(): ConversationSummary = ConversationSummary(
    id = id,
    title = title,
    repository = repository,
    branch = branch,
    model = model,
    sandbox = SandboxStatus.from(sandboxStatus),
    execution = ExecutionStatus.from(executionStatus),
    updatedAt = updatedAt,
    costUsd = costUsd,
)

data class ConversationSummary(
    val id: String,
    val title: String?,
    val repository: String?,
    val branch: String?,
    val model: String?,
    val sandbox: SandboxStatus,
    val execution: ExecutionStatus,
    val updatedAt: String?,
    val costUsd: Double?,
)
