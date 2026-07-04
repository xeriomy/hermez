package dev.hermes.core.network

import io.ktor.client.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.cookies.*
import io.ktor.client.plugins.sse.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class ChatStream(
    private val serverUrl: String,
    private val baseClient: HttpClient = HttpClientProvider.create(serverUrl)
) {

    private val sseClient = HttpClient(OkHttp) {
        expectSuccess = false
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
        install(HttpCookies) {
            storage = AcceptAllCookiesStorage()
        }
        defaultRequest {
            url(serverUrl)
            headers.remove(HttpHeaders.Origin)
            headers.remove(HttpHeaders.Referer)
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
            val response = baseClient.post<ChatStartResponse>(ApiEndpoint.ChatStart.path) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
            if (response.status.isSuccess()) {
                Result.success(response.body()!!)
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
                val events = sseClient.sse(ApiEndpoint.ChatStream.path) {
                    parameter("stream_id", streamId)
                    onEvent { event ->
                        val data = event.data ?: return@onEvent
                        val eventName = event.type ?: "unknown"
                        val parsed = parseSseEvent(eventName, data, streamId)
                        if (parsed != null) {
                            trySend(parsed)
                        }
                    }
                    onCompletion { cause ->
                        if (cause != null) {
                            throw cause
                        }
                    }
                }
                // Wait for the SSE connection to complete
                events.join()
                reconnectAttempts = maxReconnectAttempts // Exit loop on clean completion
            } catch (e: Exception) {
                reconnectAttempts++
                if (reconnectAttempts < maxReconnectAttempts) {
                    // Exponential backoff: 1s, 2s, 4s
                    val delay = (1000 * Math.pow(2.0, (reconnectAttempts - 1).toDouble())).toLong()
                    kotlinx.coroutines.delay(delay)
                } else {
                    trySend(StreamEvent.Error(e.message ?: "Stream failed after retries", streamId))
                }
            }
        }
    }.flowOn(kotlinx.coroutines.Dispatchers.IO)

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
            val response = baseClient.get<StreamStatusResponse>(
                "${ApiEndpoint.ChatStreamStatus.path}?stream_id=$streamId"
            )
            if (response.status.isSuccess()) {
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception("Reattach failed: ${response.status}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun parseSseEvent(eventName: String, data: String, streamId: String): StreamEvent? {
        return when (eventName) {
            "token" -> {
                Json { ignoreUnknownKeys = true }.decodeFromString<StreamEvent.Token>(data)
            }
            "tool" -> {
                Json { ignoreUnknownKeys = true }.decodeFromString<StreamEvent.Tool>(data)
            }
            "tool_complete" -> {
                Json { ignoreUnknownKeys = true }.decodeFromString<StreamEvent.ToolComplete>(data)
            }
            "reasoning" -> {
                Json { ignoreUnknownKeys = true }.decodeFromString<StreamEvent.Reasoning>(data)
            }
            "title" -> {
                Json { ignoreUnknownKeys = true }.decodeFromString<StreamEvent.Title>(data)
            }
            "done" -> {
                Json { ignoreUnknownKeys = true }.decodeFromString<StreamEvent.Done>(data)
            }
            "interim_assistant" -> {
                Json { ignoreUnknownKeys = true }.decodeFromString<StreamEvent.InterimAssistant>(data)
            }
            "stream_end" -> {
                Json { ignoreUnknownKeys = true }.decodeFromString<StreamEvent.StreamEnd>(data)
            }
            "error" -> {
                Json { ignoreUnknownKeys = true }.decodeFromString<StreamEvent.Error>(data)
            }
            else -> {
                StreamEvent.Unknown(eventName, data, streamId)
            }
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