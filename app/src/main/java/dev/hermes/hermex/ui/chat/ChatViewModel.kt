package dev.hermes.hermex.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.hermes.core.data.ChatMessage
import dev.hermes.core.data.SessionRepository
import dev.hermes.core.data.ToolCallInfo
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

    /**
     * Holds the streaming assistant response text SEPARATELY from the
     * messages list. This is the PERF-1 fix: appending a token to a
     * String is O(1), vs the old O(N) list copy + Markdown re-parse.
     * On stream completion, the full text is persisted to Room and
     * appears in [_messages] via the Room Flow observer — with full
     * Markdown rendering. While streaming, the UI renders this as
     * plain Text (no Markdown) for maximum speed.
     */
    private val _streamingContent = MutableStateFlow("")
    val streamingContent: StateFlow<String> = _streamingContent.asStateFlow()

    /**
     * QUAL-5: Holds streaming reasoning text (shown in a collapsible block
     * above the assistant response while streaming). Cleared on stream end.
     */
    private val _streamingReasoning = MutableStateFlow("")
    val streamingReasoning: StateFlow<String> = _streamingReasoning.asStateFlow()

    /**
     * QUAL-5: Holds tool calls happening during streaming (shown as small
     * cards). Cleared on stream end.
     */
    private val _streamingTools = MutableStateFlow<List<ToolCallInfo>>(emptyList())
    val streamingTools: StateFlow<List<ToolCallInfo>> = _streamingTools.asStateFlow()

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
    private val _remainingToLoad = MutableStateFlow(0)
    val remainingToLoad: StateFlow<Int> = _remainingToLoad.asStateFlow()

    /**
     * Called when the chat screen appears. Does two things:
     *
     *  1. Starts observing the Room cache as a Flow — messages appear
     *     INSTANTLY from the local database (no network wait).
     *  2. Kicks off a background server fetch to update the cache.
     *     The Flow observer picks up the new data automatically.
     *
     * This is the local-first pattern used by Claude, Gemini, WhatsApp.
     *
     * BUG-5 fix: the early return `if (loadedSessionId == sessionId) return`
     * prevented re-initialising when the user navigated back to the same
     * session. But the cacheObserverJob cancel on line 98 never ran because
     * of that early return — so if the observer was cancelled (e.g. by the
     * NavBackStackEntry being cleared), returning to the chat did not
     * restart it. Messages became stale: writes to Room no longer
     * propagated to the UI.
     *
     * Fix: only skip the server fetch if already loaded for this session.
     * Always cancel + re-observe the Room Flow — the cost is negligible
     * (Room Flow is cheap) and ensures the UI stays in sync.
     */
    fun loadMessages(sessionId: String) {
        val isSameSession = loadedSessionId == sessionId
        loadedSessionId = sessionId

        // Always cancel + re-observe the Room Flow — even if it's the same
        // session. The previous observer may have been cancelled when the
        // NavBackStackEntry was cleared. Room Flow is cheap.
        cacheObserverJob?.cancel()
        cacheObserverJob = sessionRepository.getMessages(sessionId, INITIAL_MESSAGE_COUNT)
            .onEach { entities ->
                _messages.value = entities.map { it.toChatMessage() }
                updateRemainingCount(sessionId)
            }
            .launchIn(viewModelScope)

        // Only fetch from server if this is the first load for this session.
        // Skip on return navigation to avoid redundant network calls.
        if (isSameSession) return

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
        _remainingToLoad.value = (total - _messages.value.size).coerceAtLeast(0)
    }

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
            messageId = "local_user_${System.currentTimeMillis()}",
            role = "user",
            content = text.trim(),
            timestamp = System.currentTimeMillis()
        )
        _messages.value = _messages.value + userMessage
        _error.value = null
        startStream(
            sessionId = sessionId,
            message = text.trim(),
            model = model,
            provider = provider,
            workspace = workspace,
            profile = profile,
            files = attachments.ifEmpty { null }
        )
    }

    /**
     * Upload a file to the server. Returns the server file path on success.
     */
    suspend fun uploadAttachment(sessionId: String, fileUri: String): Result<String> {
        return sessionRepository.uploadFile(sessionId, fileUri)
    }

    fun stopStreaming() {
        streamJob?.cancel()
        streamJob = null
        _isStreaming.value = false
    }

    private fun startStream(
        sessionId: String,
        message: String,
        model: String? = null,
        provider: String? = null,
        workspace: String? = null,
        profile: String? = null,
        files: List<String>? = null
    ) {
        _isStreaming.value = true
        _streamingContent.value = ""
        _streamingReasoning.value = ""  // QUAL-5: clear previous reasoning
        _streamingTools.value = emptyList()  // QUAL-5: clear previous tools

        // Track the user message and assistant message so we can persist
        // them to Room when the stream completes (BUG-2 fix).
        val userMessage = _messages.value.lastOrNull { it.role == "user" }
        val assistantMessageId = "stream_${System.currentTimeMillis()}"
        val assistantContent = StringBuilder()

        streamJob = viewModelScope.launch {
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
                    val exception = start.exceptionOrNull()
                    val errorMsg = exception?.message ?: "Start failed"
                    _error.value = if (errorMsg.contains("409") || errorMsg.contains("Conflict")) {
                        "A response is already streaming. Wait for it to finish or tap Stop first."
                    } else {
                        errorMsg
                    }
                    _isStreaming.value = false
                    _streamingContent.value = ""
                    return@launch
                }
                val streamId = start.getOrNull()?.stream_id ?: run {
                    _error.value = "No stream ID returned"
                    _isStreaming.value = false
                    _streamingContent.value = ""
                    return@launch
                }

                chatStream.streamEvents(streamId).collect { event ->
                    when (event) {
                        is ChatStream.StreamEvent.Token -> {
                            assistantContent.append(event.token)
                            _streamingContent.value = assistantContent.toString()
                        }
                        is ChatStream.StreamEvent.Reasoning -> {
                            // QUAL-5: show reasoning text in a separate block
                            _streamingReasoning.value += event.text
                        }
                        is ChatStream.StreamEvent.Tool -> {
                            // QUAL-5: show tool call as a card
                            _streamingTools.value = _streamingTools.value + ToolCallInfo(
                                name = event.name,
                                args = event.args,
                                result = null
                            )
                        }
                        is ChatStream.StreamEvent.ToolComplete -> {
                            // QUAL-5: update the last tool call with its result
                            val tools = _streamingTools.value.toMutableList()
                            val lastToolIndex = tools.indexOfLast { it.name == event.name && it.result == null }
                            if (lastToolIndex >= 0) {
                                tools[lastToolIndex] = tools[lastToolIndex].copy(result = event.result)
                            }
                            _streamingTools.value = tools
                        }
                        is ChatStream.StreamEvent.Title -> {
                            // QUAL-5: could update the TopAppBar title — for now
                            // just log it (the session list will pick it up on refresh)
                            android.util.Log.d("ChatViewModel", "Session title updated: ${event.title}")
                        }
                        is ChatStream.StreamEvent.Done -> Unit
                        is ChatStream.StreamEvent.StreamEnd -> {
                            throw StreamEndSignal()
                        }
                        is ChatStream.StreamEvent.Error -> {
                            _error.value = event.message ?: "Stream error"
                        }
                        else -> Unit
                    }
                }
            } catch (e: StreamEndSignal) {
                // Expected — stream ended normally via StreamEnd event.
                // Don't show an error. The finally block handles cleanup.
            } catch (e: Exception) {
                _error.value = friendlyError(e)
            } finally {
                // CHAT-2/CHAT-6 fix (chat rewrite commit 4): persistLocalMessages
                // has been removed. The new architecture is server-authoritative:
                // the next loadSession call fetches the server's version with real
                // message_ids. The full rewrite in commit 5 wires this up properly
                // by calling loadSession on stream completion.
                //
                // For now (between commit 4 and commit 5), messages from this
                // stream will appear in the UI only after the next manual
                // navigation-triggered loadSession — a temporary regression that
                // commit 5 fixes.
                _isStreaming.value = false
                _streamingContent.value = ""  // clear streaming text
                _streamingReasoning.value = ""  // QUAL-5: clear reasoning
                _streamingTools.value = emptyList()  // QUAL-5: clear tools
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

/**
 * Thrown when a StreamEnd SSE event is received, to break out of the
 * collect loop. Caught and suppressed — not an error, just a signal
 * that the stream is done. (BUG-6 fix)
 */
private class StreamEndSignal : Exception()

// ToolCallInfo and ChatMessage now live in dev.hermes.core.data (see
// core/data/ChatMessage.kt). They were moved there so StreamState
// (core/network/StreamState.kt) can reference them without reversing
// the module dependency direction.
