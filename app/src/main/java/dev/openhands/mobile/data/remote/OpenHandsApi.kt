package dev.openhands.mobile.data.remote

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Url

/** OpenHands Cloud app-server API. Authenticated with `Authorization: Bearer <token>`. */
interface OpenHandsApi {

    // --- Device OAuth (unauthenticated) ---

    @POST("oauth/device/authorize")
    suspend fun deviceAuthorize(): DeviceAuthorizationResponse

    /** Returns 400 with `authorization_pending` until the user approves in the browser. */
    @FormUrlEncoded
    @POST("oauth/device/token")
    suspend fun deviceToken(@Field("device_code") deviceCode: String): Response<DeviceTokenResponse>

    // --- Account ---

    @GET("api/v1/users/me")
    suspend fun me(): Response<Unit>

    // --- Conversations ---

    @POST("api/v1/app-conversations")
    suspend fun startConversation(@Body body: StartConversationRequest): StartTask

    @GET("api/v1/app-conversations/start-tasks")
    suspend fun startTasks(@Query("ids") ids: String): List<StartTask>

    @GET("api/v1/app-conversations")
    suspend fun conversationsByIds(@Query("ids") ids: String): List<AppConversation>

    @GET("api/v1/app-conversations/search")
    suspend fun searchConversations(
        @Query("limit") limit: Int = 30,
        @Query("page_id") pageId: String? = null,
    ): Page<AppConversation>

    @PATCH("api/v1/app-conversations/{id}")
    suspend fun updateConversation(
        @Path("id") id: String,
        @Body body: UpdateConversationRequest,
    ): AppConversation

    @DELETE("api/v1/app-conversations/{id}")
    suspend fun deleteConversation(@Path("id") id: String): Response<Unit>

    @POST("api/v1/app-conversations/{id}/send-message")
    suspend fun sendMessage(
        @Path("id") id: String,
        @Body body: SendMessageRequest,
    ): Response<Unit>

    /** Queues a message for a conversation whose agent loop is not currently running. */
    @POST("api/v1/conversations/{id}/pending-messages")
    suspend fun queuePendingMessage(
        @Path("id") id: String,
        @Body body: SendMessageRequest,
    ): Response<Unit>

    // --- Events ---

    @GET("api/v1/conversation/{id}/events/search")
    suspend fun searchEvents(
        @Path("id") id: String,
        @Query("limit") limit: Int = 50,
        @Query("sort_order") sortOrder: String = "TIMESTAMP_DESC",
        @Query("page_id") pageId: String? = null,
    ): Page<ConversationEvent>

    // --- Files and git ---

    @GET("api/v1/app-conversations/{id}/files")
    suspend fun listFiles(
        @Path("id") id: String,
        @Query("path") path: String? = null,
    ): List<String>

    @GET("api/v1/app-conversations/{id}/file")
    suspend fun readFile(
        @Path("id") id: String,
        @Query("file_path") filePath: String,
    ): String

    @GET("api/v1/app-conversations/{id}/git/changes")
    suspend fun gitChanges(
        @Path("id") id: String,
        @Query("path") path: String,
    ): List<Map<String, String>>

    // --- Sandboxes ---

    @POST("api/v1/sandboxes/{id}/pause")
    suspend fun pauseSandbox(@Path("id") id: String): Response<Unit>

    @POST("api/v1/sandboxes/{id}/resume")
    suspend fun resumeSandbox(@Path("id") id: String): Response<Unit>

    // --- Pickers ---

    @GET("api/v1/git/repositories/search")
    suspend fun searchRepositories(
        @Query("provider") provider: String,
        @Query("query") query: String? = null,
        @Query("limit") limit: Int = 30,
    ): Page<Repository>

    @GET("api/v1/git/branches/search")
    suspend fun searchBranches(
        @Query("provider") provider: String,
        @Query("repository") repository: String,
        @Query("query") query: String,
        @Query("limit") limit: Int = 30,
    ): Page<Branch>

    @GET("api/v1/config/models/search")
    suspend fun searchModels(
        @Query("query") query: String? = null,
        @Query("limit") limit: Int = 100,
        @Query("page_id") pageId: String? = null,
    ): Page<LlmModel>

    // --- Secrets ---

    @GET("api/v1/secrets/search")
    suspend fun searchSecrets(@Query("limit") limit: Int = 50): Page<CustomSecret>

    @POST("api/v1/secrets")
    suspend fun createSecret(@Body body: CreateSecretRequest): Response<Unit>

    @DELETE("api/v1/secrets/{id}")
    suspend fun deleteSecret(@Path("id") id: String): Response<Unit>
}

/**
 * Per-sandbox agent server. Uses `X-Session-API-Key` with an absolute URL, because each
 * conversation has its own agent-server host that is only known at runtime.
 */
interface AgentServerApi {

    @POST
    suspend fun respondToConfirmation(
        @Url url: String,
        @Header("X-Session-API-Key") sessionKey: String,
        @Body body: ConfirmationResponseRequest,
    ): Response<Unit>

    @POST
    suspend fun pauseConversation(
        @Url url: String,
        @Header("X-Session-API-Key") sessionKey: String,
    ): Response<Unit>

    @POST
    suspend fun executeBash(
        @Url url: String,
        @Header("X-Session-API-Key") sessionKey: String,
        @Body body: ExecuteBashRequest,
    ): BashEvent
}
