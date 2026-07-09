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
 *
 * Message loading strategy:
 *  - On entry, loads the last [INITIAL_MESSAGE_COUNT] messages from the
 *    local Room cache (one-shot, not a continuous Flow collection).
 *  - Also fetches fresh data from the server via loadSession() to update
 *    the cache, then reloads from cache.
 *  - "Load more" button fetches older messages via getMessagesBefore().
 *
 * Streaming state survives screen navigation because the ViewModel is
 * scoped to the NavBackStackEntry — but we explicitly preserve
 * [isStreaming] and [messages] so reopening the chat shows the current
 * state instead of a blank screen.
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

    private val _totalCount = MutableStateFlow(0)
    val totalCount: StateFlow<Int> = _totalCount.asStateFlow()

    private val _visibleCount = MutableStateFlow(0)
    val visibleCount: StateFlow<Int> = _visibleCount.asStateFlow()

    private var streamJob: Job? = null
    private var loadedSessionId: String? = null

    /**
     * Load the last [INITIAL_MESSAGE_COUNT] messages for [sessionId] from
     * the local Room cache. Uses Flow.first() so it's a one-shot read —
     * we don't collect forever (which caused duplicate collectors on
     * screen reopen).
     *
     * Also fetches fresh messages from the server to update the cache.
     */
    fun loadMessages(sessionId: String) {
        // Don't reload if we already have messages for this session
        if (loadedSessionId == sessionId && _messages.value.isNotEmpty()) {
            return
        }
        loadedSessionId = sessionId

        viewModelScope.launch {
            try {
                // One-shot read from Room cache — take only the first emission
                val cached = sessionRepository.getMessages(sessionId, INITIAL_MESSAGE_COUNT).first()
                _messages.value = cached.map { it.toChatMessage() }
                _visibleCount.value = cached.size

                // Fetch fresh data from server to update the cache
                val result = sessionRepository.loadSession(sessionId, msgLimit = INITIAL_MESSAGE_COUNT)
                result.onSuccess {
                    // Reload from cache after server fetch — only if we're not
                    // currently streaming (streaming adds its own messages)
                    if (!_isStreaming.value) {
                        val fresh = sessionRepository.getMessages(sessionId, INITIAL_MESSAGE_COUNT).first()
                        _messages.value = fresh.map { it.toChatMessage() }
                        _visibleCount.value = fresh.size
                    }
                }.onFailure { e ->
                    // Don't overwrite messages if we already have cached ones
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
     * Called when the user taps "Load more".
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
                    val olderMessages = older.map { it.toChatMessage() }
                    // Prepend older messages to the list (they have earlier timestamps)
                    _messages.value = olderMessages + _messages.value
                    _visibleCount.value = _messages.value.size
                }
            } catch (e: Exception) {
                _error.value = friendlyError(e)
            }
        }
    }

    /**
     * Send [text] to the server as a new message in [sessionId], then
     * stream the assistant's response token-by-token into [_messages].
     *
     * Handles 409 Conflict (stream already active) by showing a friendly
     * error instead of the raw server message.
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
                    val exception = start.exceptionOrNull()
                    val message = exception?.message ?: "Start failed"
                    // 409 Conflict = there's already an active stream on this session
                    _error.value = if (message.contains("409") || message.contains("Conflict")) {
                        "A response is already streaming. Wait for it to finish or tap Stop first."
                    } else {
                        message
                    }
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

    private fun dev.hermes.core.data.local.MessageEntity.toChatMessage() = ChatMessage(
        messageId = messageId,
        role = role,
        content = content,
        timestamp = timestamp
    )

    companion object {
        /** Number of messages to load initially (last N from cache). */
        private const val INITIAL_MESSAGE_COUNT = 20

        /** Number of older messages to load when "Load more" is tapped. */
        private const val LOAD_MORE_COUNT = 20
    }
}
