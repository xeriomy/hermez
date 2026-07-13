package dev.hermes.hermex.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.hermes.core.data.ChatMessage
import dev.hermes.core.data.SessionRepository
import dev.hermes.core.data.ToolCallInfo
import dev.hermes.core.network.ChatStream
import dev.hermes.core.network.friendlyError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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

/**
 * Drives the chat screen. Simple, working implementation.
 *
 * ## How it works
 *
 * **Messages** come from Room via a Flow observer. When the user sends
 * a message, it's inserted to Room immediately with a `local_` ID so it
 * appears instantly and survives navigation. On stream completion,
 * `loadSession` fetches the server's version (with real message_ids),
 * and the `local_` copy is deleted.
 *
 * **Streaming** uses a separate `streamingContent: StateFlow<String>`.
 * Each token appends to a StringBuilder, then we publish
 * `_streamingContent.value = content.toString()`. This is O(1) per
 * token for the append, and the StateFlow emission triggers because
 * String is a value type (no equality trap like StringBuilder in a
 * data class).
 *
 * **Cancellation** runs in a `NonCancellable` block so `cancelStream`
 * and `loadSession` complete even if the coroutine is being cancelled.
 *
 * ## Why the previous "rewrite" was reverted
 *
 * The chat rewrite (commits 859177c through 515ab06) introduced a
 * `StreamState` sealed interface, a `pendingMessages` + `allMessages`
 * combiner, and `flatMapLatest` chains. It looked clean but had three
 * critical bugs:
 *  1. `StreamState.Streaming` held a `StringBuilder` — `data class
 *     copy(content = sameStringBuilder)` didn't trigger StateFlow
 *     emission (identity equality) → tokens never appeared live.
 *  2. `pendingMessages` was prepended to `persisted` → new user
 *     messages appeared at the TOP instead of the bottom.
 *  3. The timestamp unit mismatch (server sends seconds, client uses
 *     ms) was never fixed → Room ordering was completely wrong.
 *
 * This implementation keeps the good parts of the rewrite (reattach,
 * idempotent cancel, NonCancellable cleanup, Mutex on loadSession) but
 * goes back to the simple streaming model that actually works.
 */
class ChatViewModel(
    private val sessionRepository: SessionRepository,
    private val chatStream: ChatStream
) : ViewModel() {

    // ------------------------------------------------------------------
    // Messages — from Room (single source of truth)
    // ------------------------------------------------------------------

    private val _sessionId = MutableStateFlow("")
    val sessionId: StateFlow<String> = _sessionId.asStateFlow()

    private val _visibleCount = MutableStateFlow(INITIAL_MESSAGE_COUNT)

    /**
     * Messages from Room. The Room Flow observer picks up any insert/
     * delete automatically — no manual refresh needed.
     *
     * Pagination is done by adjusting [_visibleCount] — Room re-emits
     * with the larger window.
     */
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
    // Streaming state — simple, flat StateFlows
    // ------------------------------------------------------------------

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    /**
     * The live streaming assistant response text. Plain Text in the UI
     * while streaming (no Markdown re-parse). On stream completion, this
     * is cleared and the persisted message (with Markdown) appears in
     * [messages] via the Room Flow.
     */
    private val _streamingContent = MutableStateFlow("")
    val streamingContent: StateFlow<String> = _streamingContent.asStateFlow()

    private val _streamingReasoning = MutableStateFlow("")
    val streamingReasoning: StateFlow<String> = _streamingReasoning.asStateFlow()

    private val _streamingTools = MutableStateFlow<List<ToolCallInfo>>(emptyList())
    val streamingTools: StateFlow<List<ToolCallInfo>> = _streamingTools.asStateFlow()

    // ------------------------------------------------------------------
    // Misc
    // ------------------------------------------------------------------

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

        // The flatMapLatest on _sessionId handles the Room observer.
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
     * Send a message. The user message is inserted to Room immediately
     * (optimistic UI) with a `local_` ID. On stream completion,
     * `loadSession` fetches the server's version and the `local_` copy
     * is deleted.
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

        val userMessageId = "local_${System.currentTimeMillis()}"
        val userTimestamp = System.currentTimeMillis()

        // Insert to Room immediately — appears instantly, survives
        // navigation, survives ViewModel being cleared mid-stream.
        viewModelScope.launch {
            sessionRepository.insertLocalMessage(
                sessionId = sessionId,
                messageId = userMessageId,
                role = "user",
                content = text.trim(),
                timestamp = userTimestamp
            )
        }

        _error.value = null
        startStream(
            sessionId = sessionId,
            userMessageId = userMessageId,
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
        userMessageId: String,
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
                    // Stream never started — delete the local user message
                    // (the server never received it).
                    sessionRepository.deleteMessage(userMessageId)
                    return@launch
                }

                streamId = start.getOrNull()?.stream_id ?: run {
                    _error.value = "No stream ID returned"
                    sessionRepository.deleteMessage(userMessageId)
                    return@launch
                }

                streamStarted = true

                chatStream.streamEvents(streamId).collect { event ->
                    when (event) {
                        is ChatStream.StreamEvent.Token -> {
                            content.append(event.token)
                            // CRITICAL: publish a String copy, not the
                            // StringBuilder. StateFlow compares by equality;
                            // if we stored the StringBuilder, the same
                            // reference would mean "no change" → no emission.
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

                // If the stream ended with an error, skip normal completion.
                if (_error.value != null) {
                    sessionRepository.deleteMessage(userMessageId)
                    return@launch
                }

                // Normal completion: fetch the server's authoritative
                // version (with real message_ids). The Room Flow observer
                // picks up the new messages automatically.
                sessionRepository.loadSession(sessionId, msgLimit = _visibleCount.value)
                // Delete the local user message — the server's version
                // (with a real message_id) is now in Room.
                sessionRepository.deleteMessage(userMessageId)
            } catch (e: CancellationException) {
                // User tapped Stop, or ViewModel was cleared.
                // Cleanup in NonCancellable so it completes.
                withContext(NonCancellable) {
                    streamId?.let { chatStream.cancelStream(it) }
                    if (streamStarted) {
                        // Stream had started — fetch whatever the server
                        // generated before cancellation, then delete the
                        // local user message (server has it now).
                        sessionRepository.loadSession(sessionId, msgLimit = _visibleCount.value)
                        sessionRepository.deleteMessage(userMessageId)
                    } else {
                        // Stream never started — delete local user message.
                        sessionRepository.deleteMessage(userMessageId)
                    }
                }
                throw e
            } catch (e: Exception) {
                _error.value = friendlyError(e)
                // Don't delete the local user message — the user can see
                // what they tried to send and retry.
            } finally {
                _isStreaming.value = false
                _streamingContent.value = ""
                _streamingReasoning.value = ""
                _streamingTools.value = emptyList()
                streamJob = null
            }
        }
    }

    /**
     * Stop the active stream. Cancels the stream job and tells the
     * server to stop generating. The catch(CancellationException) block
     * in [startStream] handles the cleanup (loadSession + delete local
     * message) in a NonCancellable context.
     */
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
