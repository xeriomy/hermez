package dev.hermes.core.network

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.cookies.AcceptAllCookiesStorage
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

class ChatStream(
    private val serverUrl: String,
    private val baseClient: HttpClient = HttpClientProvider.create(serverUrl)
) {

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val sseClient = HttpClient(OkHttp) {
        expectSuccess = false
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
        install(HttpCookies) {
            storage = AcceptAllCookiesStorage()
        }
        install(SSE)
        defaultRequest {
            url(serverUrl)
            headers.remove(HttpHeaders.Origin)
            headers.remove("Referer")
        }
    }

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
        data class Error(val message: String, val stream_id: String?) : StreamEvent
        @Serializable
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

    suspend fun reattachStream(streamId: String): Result<StreamStatusResponse> {
        return try {
            val response = baseClient.get("${ApiEndpoint.ChatStreamStatus.path}?stream_id=$streamId")
            if (response.status.isSuccess()) {
                Result.success(response.body())
            } else {
                Result.failure(Exception("Reattach failed: ${response.status}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun parseSseEvent(eventName: String, data: String, streamId: String): StreamEvent? {
        val json = Json { ignoreUnknownKeys = true }
        return when (eventName) {
            "token" -> json.decodeFromString<StreamEvent.Token>(data)
            "tool" -> json.decodeFromString<StreamEvent.Tool>(data)
            "tool_complete" -> json.decodeFromString<StreamEvent.ToolComplete>(data)
            "reasoning" -> json.decodeFromString<StreamEvent.Reasoning>(data)
            "title" -> json.decodeFromString<StreamEvent.Title>(data)
            "done" -> json.decodeFromString<StreamEvent.Done>(data)
            "interim_assistant" -> json.decodeFromString<StreamEvent.InterimAssistant>(data)
            "stream_end" -> json.decodeFromString<StreamEvent.StreamEnd>(data)
            "error" -> json.decodeFromString<StreamEvent.Error>(data)
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
}
