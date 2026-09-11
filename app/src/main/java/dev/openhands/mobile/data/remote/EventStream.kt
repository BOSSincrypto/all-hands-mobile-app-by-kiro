package dev.openhands.mobile.data.remote

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener

sealed interface StreamMessage {
    data class Event(val event: ConversationEvent) : StreamMessage
    data object Connected : StreamMessage
    data class Closed(val reason: String) : StreamMessage
    data class Error(val message: String) : StreamMessage
}

/**
 * Live conversation events from a sandbox's agent server.
 *
 * Auth is sent as the first frame rather than a query parameter: the agent server supports
 * both, but the query form is deprecated because it leaks the key into proxy access logs.
 */
@Singleton
class EventStreamClient @Inject constructor(
    private val client: OkHttpClient,
    private val json: Json,
) {
    fun stream(
        agentServerUrl: String,
        conversationId: String,
        sessionApiKey: String,
    ): Flow<StreamMessage> = callbackFlow {
        val wsUrl = agentServerUrl
            .replaceFirst("https://", "wss://")
            .replaceFirst("http://", "ws://")
            .trimEnd('/') + "/sockets/events/$conversationId"

        val socket = client.newWebSocket(
            Request.Builder().url(wsUrl).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                    webSocket.send(
                        json.encodeToString(
                            JsonObject.serializer(),
                            buildJsonObject {
                                put("type", "auth")
                                put("session_api_key", sessionApiKey)
                            },
                        ),
                    )
                    trySend(StreamMessage.Connected)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val parsed = runCatching {
                        val obj = json.parseToJsonElement(text) as? JsonObject ?: return@runCatching null
                        // Auth acks and other control frames carry no event kind; skip them.
                        if (obj["kind"]?.jsonPrimitive?.contentOrNull() == null) return@runCatching null
                        json.decodeFromJsonElement(ConversationEvent.serializer(), obj)
                    }.getOrNull()
                    if (parsed != null) trySend(StreamMessage.Event(parsed))
                }

                override fun onFailure(
                    webSocket: WebSocket,
                    t: Throwable,
                    response: okhttp3.Response?,
                ) {
                    trySend(StreamMessage.Error(t.message ?: "stream failure"))
                    close()
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    trySend(StreamMessage.Closed(reason))
                    close()
                }
            },
        )

        awaitClose { socket.cancel() }
    }
}

private fun kotlinx.serialization.json.JsonPrimitive.contentOrNull(): String? =
    runCatching { content }.getOrNull()
