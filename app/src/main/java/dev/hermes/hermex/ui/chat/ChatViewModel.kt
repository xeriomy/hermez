package dev.hermes.hermex.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.hermes.core.data.SessionRepository
import dev.hermes.core.network.ChatStream
import dev.hermes.core.network.friendlyError
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Drives the chat screen.
 *
 * **Loading strategy (local-first):**
 *  1. Instantly observe the Room cache as a Flow — messages appear in
 *     ~5ms, before any network request. This is how Claude/Gemini/
 *     WhatsApp feel instant.
 *  2. In the background, fetch fresh messages from the server via
 *     loadSession() and write them to Room. The Flow observer picks
 *     up the change and the UI updates automatically — no manual
 *     reload needed.
 *  3. If the cache is empty (first-ever open), show a loading spinner
 *     until the server responds.
 *
 * **Streaming strategy:**
 *  - Sending a message calls POST /api/chat/start, then collects the
 *    SSE event stream. Token events append to the last assistant
 *    message. Stop button cancels the stream job.
 *  - isStreaming is preserved across navigation because the ViewModel
 *    is scoped to the NavBackStackEntry.
 */
class ChatViewModel(
    private val sessionRepository: SessionRepository,
    private val chatStream: ChatStream
) : ViewModel() {

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** True while fetching from server AND cache is empty. */
    private val _isInitialLoading = MutableStateFlow(false)
    val isInitialLoading: StateFlow<Boolean> = _isInitialLoading.asStateFlow()

    /** Total cached messages for this session (for "Load more (N)" button). */
    private val _totalCached = MutableStateFlow(0)
    val totalCached: StateFlow<Int> = _totalCached.asStateFlow()

    private var streamJob: Job? = null
    private var cacheObserverJob: Job? = null
    private var loadedSessionId: String? = null

    // Backing flow for messages — set by the Room cache observer
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    /** How many older messages are available to load (total - visible). */
    val remainingToLoad: StateFlow<Int> = MutableStateFlow(0)

    /**
     * Called when the chat screen appears. Does two things:
     *
     *  1. Starts observing the Room cache as a Flow — messages appear
     *     INSTANTLY from the local database (no network wait).
     *  2. Kicks off a background server fetch to update the cache.
     *     The Flow observer picks up the new data automatically.
     *
     * This is the local-first pattern used by Claude, Gemini, WhatsApp.
     */
    fun loadMessages(sessionId: String) {
        if (loadedSessionId == sessionId) return
        loadedSessionId = sessionId

        // Cancel any previous cache observer (e.g. from a different session)
        cacheObserverJob?.cancel()

        // 1. Observe Room cache as a Flow — instant updates when cache changes
        cacheObserverJob = sessionRepository.getMessages(sessionId, INITIAL_MESSAGE_COUNT)
            .onEach { entities ->
                val chatMessages = entities.map { it.toChatMessage() }
                _messages.value = chatMessages
                updateRemainingCount(sessionId)
            }
            .launchIn(viewModelScope)

        // 2. Background fetch from server — don't block the UI
        val cacheIsEmpty = _messages.value.isEmpty()
        if (cacheIsEmpty) {
            _isInitialLoading.value = true
        }

        viewModelScope.launch {
            try {
                val result = sessionRepository.loadSession(sessionId, msgLimit = INITIAL_MESSAGE_COUNT)
                result.onFailure { e ->
                    // Only show error if we have no cached messages to show
                    if (_messages.value.isEmpty()) {
                        _error.value = e.message ?: "Failed to load messages"
                    }
                }
            } catch (e: Exception) {
                if (_messages.value.isEmpty()) {
                    _error.value = friendlyError(e)
                }
            } finally {
                _isInitialLoading.value = false
            }
        }
    }

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
                }
            } catch (e: Exception) {
                _error.value = friendlyError(e)
            }
        }
    }

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
