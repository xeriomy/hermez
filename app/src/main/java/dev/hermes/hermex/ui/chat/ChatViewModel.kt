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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Drives the chat screen: holds the message list, streaming state, and
 * error state. Uses the [SessionRepository] and [ChatStream] passed from
 * [dev.hermes.hermex.ui.HermexApp] so all screens share the same
 * Activity-scoped instances (and the same auth cookie).
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

    /** Total messages in cache for this session. */
    private val _totalCached = MutableStateFlow(0)
    val totalCached: StateFlow<Int> = _totalCached.asStateFlow()

    /** How many older messages are available to load (total - visible). */
    val remainingToLoad: StateFlow<Int> = MutableStateFlow(0)

    private var streamJob: Job? = null
    private var loadedSessionId: String? = null

    /**
     * Load the last [INITIAL_MESSAGE_COUNT] messages for [sessionId] from
     * the local Room cache. Uses Flow.first() so it's a one-shot read.
     * Also fetches fresh data from the server to update the cache.
     */
    fun loadMessages(sessionId: String) {
        if (loadedSessionId == sessionId && _messages.value.isNotEmpty()) {
            return
        }
        loadedSessionId = sessionId

        viewModelScope.launch {
            try {
                // One-shot read from Room cache
                val cached = sessionRepository.getMessages(sessionId, INITIAL_MESSAGE_COUNT).first()
                _messages.value = cached.map { it.toChatMessage() }
                updateRemainingCount(sessionId)

                // Fetch fresh data from server to update the cache
                val result = sessionRepository.loadSession(sessionId, msgLimit = INITIAL_MESSAGE_COUNT)
                result.onSuccess {
                    if (!_isStreaming.value) {
                        val fresh = sessionRepository.getMessages(sessionId, INITIAL_MESSAGE_COUNT).first()
                        _messages.value = fresh.map { it.toChatMessage() }
                        updateRemainingCount(sessionId)
                    }
                }.onFailure { e ->
                    if (_messages.value.isEmpty()) {
                        _error.value = e.message ?: "Failed to load messages"
                    }
                }
            } catch (e: Exception) {
                _error.value = friendlyError(e)
            }
        }
    }

    /**
     * Load older messages (before the oldest currently visible message).
     */
    fun loadMoreMessages(sessionId: String) {
        val oldestTimestamp = _messages.value.firstOrNull()?.timestamp ?: return

        viewModelScope.launch {
            try {
                val older = sessionRepository.getMessagesBefore(
                    sessionId,
                    oldestTimestamp,
                    LOAD_MORE_COUNT
                )
                if (older.isNotEmpty()) {
                    val olderMessages = older.reversed().map { it.toChatMessage() }
                    _messages.value = olderMessages + _messages.value
                    updateRemainingCount(sessionId)
                } else {
                    // No more messages to load
                    updateRemainingCount(sessionId)
                }
            } catch (e: Exception) {
                _error.value = friendlyError(e)
            }
        }
    }

    /**
     * Update [remainingToLoad] based on total cached vs currently visible.
     */
    private suspend fun updateRemainingCount(sessionId: String) {
        val total = sessionRepository.getMessageCount(sessionId)
        _totalCached.value = total
        (remainingToLoad as MutableStateFlow).value = (total - _messages.value.size).coerceAtLeast(0)
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
                    val exception = start.exceptionOrNull()
                    val errorMsg = exception?.message ?: "Start failed"
                    _error.value = if (errorMsg.contains("409") || errorMsg.contains("Conflict")) {
                        "A response is already streaming. Wait for it to finish or tap Stop first."
                    } else {
                        errorMsg
                    }
                    _isStreaming.value = false
                    return@launch
                }
                val streamId = start.getOrNull()?.stream_id ?: run {
                    _error.value = "No stream ID returned"
                    _isStreaming.value = false
                    return@launch
                }

                chatStream.streamEvents(streamId).collect { event ->
                    when (event) {
                        is ChatStream.StreamEvent.Token -> {
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

    private fun dev.hermes.core.data.local.MessageEntity.toChatMessage() = ChatMessage(
        messageId = messageId,
        role = role,
        content = content,
        timestamp = timestamp
    )

    companion object {
        private const val INITIAL_MESSAGE_COUNT = 20
        private const val LOAD_MORE_COUNT = 20
    }
}
