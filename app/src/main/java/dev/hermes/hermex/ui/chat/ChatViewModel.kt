package dev.hermes.hermex.ui.chat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.hermes.core.auth.AuthPrefsRepository
import dev.hermes.core.auth.AuthState
import dev.hermes.core.data.SessionRepository
import dev.hermes.core.network.ChatStream
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Drives the chat screen: holds the message list, streaming state, and
 * error state. Constructs its own [ChatStream] from the saved server URL.
 *
 * Extends [AndroidViewModel] so it can be created by the default Compose
 * `viewModel()` factory with just an [Application] parameter.
 */
class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val context = app.applicationContext
    private val sessionRepository = SessionRepository(app)
    private val serverUrl = AuthPrefsRepository(context).getServerUrl() ?: ""
    private val chatStream = ChatStream(serverUrl)

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
        if (serverUrl.isEmpty()) {
            _error.value = "Not connected to a server"
            _isStreaming.value = false
            return
        }
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
                        is ChatStream.StreamEvent.Done -> Unit
                        is ChatStream.StreamEvent.StreamEnd -> Unit
                        is ChatStream.StreamEvent.Error -> {
                            _error.value = event.message ?: "Stream error"
                        }
                        else -> Unit
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
