package dev.hermes.core.network

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.plugins.sse.sse
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Streams chat events from `GET /api/chat/stream?stream_id=...` (SSE)
 * and starts/cancels chats via `POST /api/chat/start` and
 * `GET /api/chat/cancel`.
 *
 * The [baseClient] is borrowed from [SharedHttpClient] so it shares the
 * same cookie jar (and therefore the auth cookie set by AuthRepository's
 * login call). The [sseClient] is a separate HttpClient with the SSE
 * plugin installed — it needs its own client because SSE requires
 * long-lived streaming connections that the shared client doesn't
 * support well.
 *
 * To ensure the SSE client also has the auth cookie, we copy the
 * shared client's cookies into the SSE client on every request. This
 * is a best-effort approach; the server should also accept the cookie
 * on the SSE connection.
 *
 * @param serverUrl The base URL of the hermes-webui server (must
 *   include scheme, e.g. `http://127.0.0.1:8787`).
 * @param baseClient The shared [HttpClient] used for non-streaming
 *   requests (`POST /api/chat/start`, `GET /api/chat/cancel`). If
 *   null, a new client is created via [SharedHttpClient.client].
 */
class ChatStream(
    private val serverUrl: String,
    private val baseClient: HttpClient = SharedHttpClient.client(serverUrl)
        ?: throw IllegalStateException("Not logged in — SharedHttpClient has no client for $serverUrl")
) {

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val sseClient = HttpClient(OkHttp) {
        expectSuccess = false

        // PERF-4 fix: configure OkHttp timeouts for SSE.
        // OkHttp defaults: 10s connect, 10s read, 10s write.
        // The 10s read timeout kills SSE connections when the server
        // doesn't send a token within 10s (normal for long-running
        // reasoning or tool calls). Set read timeout to 0 (infinite)
        // so the SSE connection stays alive during long pauses.
        engine {
            config {
                connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                readTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS)  // infinite
                writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            }
        }

        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
        install(HttpCookies) {
            // Share the SAME cookie storage as SharedHttpClient so the
            // auth cookie set by AuthRepository.login() is sent with
            // SSE requests too. Without this, the SSE endpoint returns
            // 401 because the cookie jar is empty.
            storage = SharedHttpClient.cookieStorage
        }
        install(SSE)
        defaultRequest {
            url(serverUrl)
            headers.remove(HttpHeaders.Origin)
            headers.remove("Referer")
        }
    }

    // QUAL-12: @Serializable IS needed on subclasses because parseSseEvent
    // uses json.decodeFromString<Token>(data) for each event type. The
    // sealed interface itself is not @Serializable (no polymorphic
    // dispatch) — each subclass is decoded explicitly by event name.
    sealed interface StreamEvent {
        @Serializable
        data class Token(val token: String, val stream_id: String?) : StreamEvent
        @Serializable
        data class Tool(val name: String, val args: String, val stream_id: String?) : StreamEvent
        @Serializable
        data class ToolComplete(val name: String, val result: String, val stream_id: String?) : StreamEvent
        @Serializable
        data class Reasoning(val text: String, val stream_id: String?) : StreamEvent
        @Serializable
        data class Title(val title: String, val stream_id: String?) : StreamEvent
        @Serializable
        data class Done(val stream_id: String?) : StreamEvent
        @Serializable
        data class InterimAssistant(val content: String, val stream_id: String?) : StreamEvent
        @Serializable
        data class StreamEnd(val stream_id: String?) : StreamEvent
        @Serializable
        data class Error(val message: String? = null, val stream_id: String? = null) : StreamEvent
        data class Unknown(val type: String, val data: String, val stream_id: String?) : StreamEvent
    }

    suspend fun startChat(request: ChatStartRequest): Result<ChatStartResponse> {
        return try {
            val response: HttpResponse = baseClient.post(ApiEndpoint.ChatStart.path) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
            if (response.status.isSuccess()) {
                Result.success(response.body())
            } else {
                Result.failure(Exception("Start chat failed: ${response.status}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun streamEvents(streamId: String): Flow<StreamEvent> = channelFlow {
        var reconnectAttempts = 0
        val maxReconnectAttempts = 3

        while (reconnectAttempts < maxReconnectAttempts) {
            try {
                sseClient.sse(
                    ApiEndpoint.ChatStream.path,
                    request = { parameter("stream_id", streamId) }
                ) {
                    // `this` is ClientSSESession. `incoming` is a Flow<ServerSentEvent>.
                    incoming.collect { event ->
                        val data = event.data ?: return@collect
                        if (data.isEmpty()) return@collect
                        val eventName = event.event ?: "unknown"
                        val parsed = parseSseEvent(eventName, data, streamId)
                        if (parsed != null) {
                            trySend(parsed)
                        }
                    }
                }
                reconnectAttempts = maxReconnectAttempts // Exit loop on clean completion
            } catch (e: Exception) {
                reconnectAttempts++
                if (reconnectAttempts < maxReconnectAttempts) {
                    // Exponential backoff: 1s, 2s, 4s
                    val delayMs = (1000L * (1L shl (reconnectAttempts - 1)))
                    delay(delayMs)
                } else {
                    trySend(StreamEvent.Error(e.message ?: "Stream failed after retries", streamId))
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    suspend fun cancelStream(streamId: String): Result<Unit> {
        return try {
            val response = baseClient.get(ApiEndpoint.ChatCancel.path) {
                parameter("stream_id", streamId)
            }
            if (response.status.isSuccess()) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("Cancel failed: ${response.status}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // BUG-12 fix: reattachStream was dead code — never called from anywhere.
    // Deleted. The streamEvents docstring mentioned reattach as a concept,
    // but the implementation doesn't actually reattach — it just retries
    // the SSE connection from the top (losing any events the server already
    // sent). To be implemented properly in a future version.

    // QUAL-13 fix: hoisted Json instance to companion object instead of
    // creating a new one per SSE event (hundreds of allocations per
    // streaming response).
    private fun parseSseEvent(eventName: String, data: String, streamId: String): StreamEvent? {
        return when (eventName) {
            "token" -> JSON.decodeFromString<StreamEvent.Token>(data)
            "tool" -> JSON.decodeFromString<StreamEvent.Tool>(data)
            "tool_complete" -> JSON.decodeFromString<StreamEvent.ToolComplete>(data)
            "reasoning" -> JSON.decodeFromString<StreamEvent.Reasoning>(data)
            "title" -> JSON.decodeFromString<StreamEvent.Title>(data)
            "done" -> JSON.decodeFromString<StreamEvent.Done>(data)
            "interim_assistant" -> JSON.decodeFromString<StreamEvent.InterimAssistant>(data)
            "stream_end" -> JSON.decodeFromString<StreamEvent.StreamEnd>(data)
            "error" -> JSON.decodeFromString<StreamEvent.Error>(data)
            else -> StreamEvent.Unknown(eventName, data, streamId)
        }
    }

    @Serializable
    data class ChatStartRequest(
        val session_id: String?,
        val message: String,
        val model: String?,
        val provider: String?,
        val reasoning: String?,
        val workspace: String?,
        val profile: String?,
        val files: List<String>?,
        val explicit_model_pick: Boolean? = false
    )

    @Serializable
    data class ChatStartResponse(
        val stream_id: String,
        val session_id: String
    )

    @Serializable
    data class StreamStatusResponse(
        val stream_id: String,
        val status: String, // "active" | "completed" | "failed"
        val session_id: String?
    )

    companion object {
        // QUAL-13 fix: single Json instance reused across all SSE event
        // parsing. Previously a new Json instance was allocated per event.
        private val JSON = Json { ignoreUnknownKeys = true }
    }
}
