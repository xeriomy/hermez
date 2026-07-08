package dev.hermes.hermex.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.hermes.core.data.SessionRepository
import dev.hermes.core.network.ChatStream
import dev.hermes.core.network.friendlyError
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Drives the chat screen: holds the message list, streaming state, and
 * error state. Uses the [SessionRepository] and [ChatStream] passed from
 * [dev.hermes.hermex.ui.HermexApp] so all screens share the same
 * Activity-scoped instances (and the same auth cookie).
 *
 * Extends [ViewModel] (not AndroidViewModel) because it doesn't need
 * an Application context — all its dependencies are injected.
 */
class ChatViewModel(
    private val sessionRepository: SessionRepository,
    private val chatStream: ChatStream
) : ViewModel() {

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var streamJob: Job? = null

    /**
     * Load existing messages for [sessionId] from the local Room cache.
     * The cache is filled by [SessionRepository.loadSession] which is
     * called when the user navigates into the chat screen.
     */
    fun loadMessages(sessionId: String) {
        viewModelScope.launch {
            try {
                sessionRepository.getMessages(sessionId).collect { msgs ->
                    _messages.value = msgs.map {
                        ChatMessage(
                            messageId = it.messageId,
                            role = it.role,
                            content = it.content,
                            timestamp = it.timestamp
                        )
                    }
                }
            } catch (e: Exception) {
                _error.value = friendlyError(e)
            }
        }
    }

    /**
     * Send [text] to the server as a new message in [sessionId], then
     * stream the assistant's response token-by-token into [_messages].
     */
    fun sendMessage(text: String, sessionId: String) {
        if (text.isBlank() || _isStreaming.value) return

        // Optimistically add the user's message to the list immediately
        val userMessage = ChatMessage(
            messageId = "local_user_${System.currentTimeMillis()}",
            role = "user",
            content = text.trim(),
            timestamp = System.currentTimeMillis()
        )
        _messages.value = _messages.value + userMessage
        _error.value = null
        startStream(sessionId = sessionId, message = text.trim())
    }

    fun stopStreaming() {
        streamJob?.cancel()
        streamJob = null
        _isStreaming.value = false
    }

    private fun startStream(sessionId: String, message: String) {
        _isStreaming.value = true
        streamJob = viewModelScope.launch {
            try {
                val start = chatStream.startChat(
                    ChatStream.ChatStartRequest(
                        session_id = sessionId,
                        message = message,
                        model = null,
                        provider = null,
                        reasoning = null,
                        workspace = null,
                        profile = null,
                        files = null,
                        explicit_model_pick = false
                    )
                )
                if (start.isFailure) {
                    _error.value = start.exceptionOrNull()?.message ?: "Start failed"
                    _isStreaming.value = false
                    return@launch
                }
                val streamId = start.getOrNull()?.stream_id ?: run {
                    _error.value = "No stream ID returned"
                    _isStreaming.value = false
                    return@launch
                }

                // Collect SSE events until the stream completes
                chatStream.streamEvents(streamId).collect { event ->
                    when (event) {
                        is ChatStream.StreamEvent.Token -> {
                            // Append the token to the last assistant message,
                            // or create a new one if there isn't one yet
                            val existing = _messages.value
                            val last = existing.lastOrNull()
                            val updated = if (last != null && last.role == "assistant") {
                                existing.toMutableList().also {
                                    it[it.size - 1] = last.copy(content = last.content + event.token)
                                }
                            } else {
                                existing + ChatMessage(
                                    messageId = "stream_${System.currentTimeMillis()}",
                                    role = "assistant",
                                    content = event.token,
                                    timestamp = System.currentTimeMillis()
                                )
                            }
                            _messages.value = updated
                        }
                        is ChatStream.StreamEvent.Done -> Unit
                        is ChatStream.StreamEvent.StreamEnd -> Unit
                        is ChatStream.StreamEvent.Error -> {
                            _error.value = event.message ?: "Stream error"
                        }
                        else -> Unit
                    }
                }
            } catch (e: Exception) {
                _error.value = friendlyError(e)
            } finally {
                _isStreaming.value = false
                streamJob = null
            }
        }
    }
}
