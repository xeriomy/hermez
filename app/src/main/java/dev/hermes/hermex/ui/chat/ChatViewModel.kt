package dev.hermes.hermex.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.hermes.core.data.ChatMessage
import dev.hermes.core.data.SessionRepository
import dev.hermes.core.data.ToolCallInfo
import dev.hermes.core.network.ChatStream
import dev.hermes.core.network.friendlyError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Drives the chat screen. Simple, working implementation.
 *
 * ## How it works
 *
 * **Messages** come from Room via a Flow observer. The Room cache is
 * populated by `loadSession` (fetches from server). The user's sent
 * message is shown as an in-memory `pendingMessage` appended to the
 * bottom of the list — it's NOT written to Room until the server
 * confirms it (via `loadSession` on next navigation or stream end).
 *
 * **Streaming** uses a separate `streamingContent: StateFlow<String>`.
 * Each token appends to a StringBuilder, then we publish
 * `_streamingContent.value = content.toString()`. On stream end, we
 * call `loadSession` to fetch the server's version (with real IDs).
 *
 * **No deletions, no race conditions.** The previous implementation
 * inserted a `local_` message to Room then deleted it after `loadSession`
 * — but if `loadSession` didn't return the user message in its window,
 * the message vanished. Now we just append a pending message in-memory
 * and clear it when the stream completes (by which point `loadSession`
 * has fetched everything).
 */
class ChatViewModel(
    private val sessionRepository: SessionRepository,
    private val chatStream: ChatStream
) : ViewModel() {

    private val _sessionId = MutableStateFlow("")
    val sessionId: StateFlow<String> = _sessionId.asStateFlow()

    private val _visibleCount = MutableStateFlow(INITIAL_MESSAGE_COUNT)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val messages: StateFlow<List<ChatMessage>> = combine(
        _sessionId, _visibleCount
    ) { sid, count -> sid to count }
        .flatMapLatest { (sid, count) ->
            if (sid.isEmpty()) kotlinx.coroutines.flow.flowOf(emptyList())
            else sessionRepository.getMessages(sid, count)
                .map { entities -> entities.map { it.toChatMessage() } }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ------------------------------------------------------------------
    // Streaming state
    // ------------------------------------------------------------------

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    private val _streamingContent = MutableStateFlow("")
    val streamingContent: StateFlow<String> = _streamingContent.asStateFlow()

    private val _streamingReasoning = MutableStateFlow("")
    val streamingReasoning: StateFlow<String> = _streamingReasoning.asStateFlow()

    private val _streamingTools = MutableStateFlow<List<ToolCallInfo>>(emptyList())
    val streamingTools: StateFlow<List<ToolCallInfo>> = _streamingTools.asStateFlow()

    /**
     * The user's sent message, shown optimistically at the bottom of
     * the list. NOT in Room. Cleared when the stream completes (by
     * which point loadSession has fetched the server's version).
     */
    private val _pendingMessage = MutableStateFlow<ChatMessage?>(null)
    val pendingMessage: StateFlow<ChatMessage?> = _pendingMessage.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _isInitialLoading = MutableStateFlow(false)
    val isInitialLoading: StateFlow<Boolean> = _isInitialLoading.asStateFlow()

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val remainingToLoad: StateFlow<Int> = combine(
        _sessionId, _visibleCount
    ) { sid, visible -> sid to visible }
        .flatMapLatest { (sid, visible) ->
            if (sid.isEmpty()) kotlinx.coroutines.flow.flowOf(0)
            else sessionRepository.getMessageCountFlow(sid)
                .map { total -> (total - visible).coerceAtLeast(0) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    private var streamJob: Job? = null
    private var loadedSessionId: String? = null

    // ------------------------------------------------------------------
    // Loading
    // ------------------------------------------------------------------

    fun loadMessages(sessionId: String) {
        val isSameSession = loadedSessionId == sessionId
        loadedSessionId = sessionId
        _sessionId.value = sessionId

        if (isSameSession) return

        _isInitialLoading.value = true
        viewModelScope.launch {
            try {
                sessionRepository.loadSession(sessionId, msgLimit = INITIAL_MESSAGE_COUNT)
                    .onFailure { e ->
                        if (messages.value.isEmpty()) {
                            _error.value = e.message ?: "Failed to load messages"
                        }
                    }
            } catch (e: Exception) {
                if (messages.value.isEmpty()) {
                    _error.value = friendlyError(e)
                }
            } finally {
                _isInitialLoading.value = false
            }
        }
    }

    fun loadMoreMessages(sessionId: String) {
        _visibleCount.value += LOAD_MORE_INCREMENT
    }

    // ------------------------------------------------------------------
    // Sending
    // ------------------------------------------------------------------

    suspend fun uploadAttachment(sessionId: String, fileUri: String): Result<String> {
        return sessionRepository.uploadFile(sessionId, fileUri)
    }

    /**
     * Send a message. The user message is shown as an in-memory
     * `pendingMessage` at the bottom of the list (NOT in Room). On
     * stream completion, `loadSession` fetches the server's version
     * (with a real message_id) and we clear the pending message.
     */
    fun sendMessage(
        text: String,
        sessionId: String,
        model: String? = null,
        provider: String? = null,
        workspace: String? = null,
        profile: String? = null,
        attachments: List<String> = emptyList()
    ) {
        if (text.isBlank() || _isStreaming.value) return

        val userMessage = ChatMessage(
            messageId = "pending_${System.currentTimeMillis()}",
            role = "user",
            content = text.trim(),
            timestamp = System.currentTimeMillis()
        )
        _pendingMessage.value = userMessage
        _error.value = null

        startStream(
            sessionId = sessionId,
            message = text.trim(),
            model = model, provider = provider,
            workspace = workspace, profile = profile,
            files = attachments.ifEmpty { null }
        )
    }

    // ------------------------------------------------------------------
    // Streaming
    // ------------------------------------------------------------------

    private fun startStream(
        sessionId: String,
        message: String,
        model: String?, provider: String?,
        workspace: String?, profile: String?,
        files: List<String>?
    ) {
        _isStreaming.value = true
        _streamingContent.value = ""
        _streamingReasoning.value = ""
        _streamingTools.value = emptyList()

        val content = StringBuilder()
        val reasoning = StringBuilder()
        val tools = mutableListOf<ToolCallInfo>()

        streamJob = viewModelScope.launch {
            var streamId: String? = null
            var streamStarted = false

            try {
                val start = chatStream.startChat(
                    ChatStream.ChatStartRequest(
                        session_id = sessionId,
                        message = message,
                        model = model,
                        provider = provider,
                        reasoning = null,
                        workspace = workspace,
                        profile = profile,
                        files = files,
                        explicit_model_pick = model != null
                    )
                )

                if (start.isFailure) {
                    val msg = start.exceptionOrNull()?.message ?: "Start failed"
                    _error.value = if (msg.contains("409") || msg.contains("Conflict")) {
                        "A response is already streaming. Wait for it or tap Stop first."
                    } else {
                        msg
                    }
                    _pendingMessage.value = null
                    return@launch
                }

                streamId = start.getOrNull()?.stream_id ?: run {
                    _error.value = "No stream ID returned"
                    _pendingMessage.value = null
                    return@launch
                }

                streamStarted = true

                var timedOut = false
                try {
                    withTimeout(5 * 60 * 1000L) {
                        chatStream.streamEvents(streamId).collect { event ->
                            when (event) {
                                is ChatStream.StreamEvent.Token -> {
                                    content.append(event.token)
                                    _streamingContent.value = content.toString()
                                }
                                is ChatStream.StreamEvent.Reasoning -> {
                                    reasoning.append(event.text)
                                    _streamingReasoning.value = reasoning.toString()
                                }
                                is ChatStream.StreamEvent.Tool -> {
                                    tools.add(ToolCallInfo(event.name, event.args, null))
                                    _streamingTools.value = tools.toList()
                                }
                                is ChatStream.StreamEvent.ToolComplete -> {
                                    val idx = tools.indexOfLast {
                                        it.name == event.name && it.result == null
                                    }
                                    if (idx >= 0) {
                                        tools[idx] = tools[idx].copy(result = event.result)
                                    }
                                    _streamingTools.value = tools.toList()
                                }
                                is ChatStream.StreamEvent.Title -> {
                                    android.util.Log.d("ChatViewModel", "Title: ${event.title}")
                                }
                                is ChatStream.StreamEvent.Done -> Unit
                                is ChatStream.StreamEvent.InterimAssistant -> Unit
                                is ChatStream.StreamEvent.StreamEnd -> return@collect
                                is ChatStream.StreamEvent.Error -> {
                                    _error.value = event.message ?: "Stream error"
                                    return@collect
                                }
                                is ChatStream.StreamEvent.Unknown -> Unit
                            }
                        }
                    }
                } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                    timedOut = true
                    streamId?.let { chatStream.cancelStream(it) }
                }

                // Fetch the server's authoritative version (with real
                // message_ids). The Room Flow observer picks up the new
                // messages automatically. This brings in BOTH the user
                // message and the assistant response.
                sessionRepository.loadSession(sessionId, msgLimit = _visibleCount.value)

                // Clear the pending message — the server's version is
                // now in Room.
                _pendingMessage.value = null
            } catch (e: CancellationException) {
                // User tapped Stop, or ViewModel was cleared.
                withContext(NonCancellable) {
                    streamId?.let { chatStream.cancelStream(it) }
                    if (streamStarted) {
                        sessionRepository.loadSession(sessionId, msgLimit = _visibleCount.value)
                    }
                    _pendingMessage.value = null
                }
                throw e
            } catch (e: Exception) {
                _error.value = friendlyError(e)
            } finally {
                _isStreaming.value = false
                _streamingContent.value = ""
                _streamingReasoning.value = ""
                _streamingTools.value = emptyList()
                streamJob = null
            }
        }
    }

    fun stopStreaming() {
        streamJob?.cancel()
        streamJob = null
    }

    private fun dev.hermes.core.data.local.MessageEntity.toChatMessage() = ChatMessage(
        messageId = messageId,
        role = role,
        content = content,
        timestamp = timestamp
    )

    companion object {
        private const val INITIAL_MESSAGE_COUNT = 50
        private const val LOAD_MORE_INCREMENT = 20
    }
}
