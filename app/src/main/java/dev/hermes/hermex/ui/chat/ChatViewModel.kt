package dev.hermes.hermex.ui.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.hermes.core.auth.AuthRepository
import dev.hermes.core.data.SessionRepository
import dev.hermes.core.network.ChatStream
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ChatViewModel(
    private val sessionRepository: SessionRepository,
    private val authRepository: AuthRepository,
    private val chatStream: ChatStream
) : ViewModel() {

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _newMessage = MutableStateFlow("")
    val newMessage: StateFlow<String> = _newMessage.asStateFlow()

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var streamJob: Job? = null

    fun onMessageChanged(text: String) {
        _newMessage.value = text
        _error.value = null
    }

    fun sendMessage(text: String, sessionId: String) {
        if (text.isBlank() || _isStreaming.value) return
        val userMessage = ChatMessage(
            messageId = "local_user_${System.currentTimeMillis()}",
            role = "user",
            content = text.trim(),
            timestamp = System.currentTimeMillis()
        )
        _messages.value = _messages.value + userMessage
        _newMessage.value = ""
        startStream(sessionId = sessionId, message = text.trim())
    }

    fun stopStreaming() {
        streamJob?.cancel()
        streamJob = null
        _isStreaming.value = false
    }

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
                _error.value = e.message
            }
        }
    }

    private fun startStream(sessionId: String, message: String) {
        _error.value = null
        _isStreaming.value = true
        val serverUrl = (authRepository.authState.value as? AuthState.LoggedIn)?.serverUrl ?: return
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
                val streamId = start.getOrNull()?.stream_id ?: return@launch
                chatStream.streamEvents(streamId).collect { event ->
                    when (event) {
                        is ChatStream.StreamEvent.Token -> {
                            val existing = _messages.value.toMutableList()
                            val last = existing.lastOrNull()
                            val updated = if (last != null && last.role == "assistant") {
                                existing.toMutableList().also { it[it.size - 1] = last.copy(content = last.content + event.token) }
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
                        ChatStream.StreamEvent.Done -> Unit
                        ChatStream.StreamEvent.StreamEnd -> Unit
                        is ChatStream.StreamEvent.Error -> {
                            _error.value = event.message ?: "Stream error"
                        }
                    }
                }
            } catch (e: Exception) {
                _error.value = e.message ?: "Stream error"
            } finally {
                _isStreaming.value = false
                streamJob = null
            }
        }
    }
}
