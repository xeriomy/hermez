package dev.hermes.hermex.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.hermes.core.data.ChatMessage
import dev.hermes.core.data.SessionRepository
import dev.hermes.core.data.ToolCallInfo
import dev.hermes.core.network.ChatStream
import dev.hermes.core.network.StreamState
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
 * Drives the chat screen.
 *
 * ## Architecture (chat rewrite — see hermez-chat-rewrite.pdf)
 *
 * **Single source of truth:** messages come from a Room Flow, collected
 * into a [StateFlow]. There is **no separate in-memory list**. The old
 * design had `_messages: MutableStateFlow` AND the Room cache, with no
 * coordination between them — they stomped on each other and caused
 * CHAT-2 (duplicates), CHAT-3 (flicker), CHAT-7 (pagination
 * overwritten), CHAT-11 (recomputation on every change).
 *
 * **Pagination** is done by adjusting the LIMIT clause in the Room
 * query ([_visibleCount]), not by manually prepending to a list. When
 * the user taps "Load more", `_visibleCount` increases by 20 and Room
 * re-emits with the larger window. No manual list manipulation = no
 * overwrite risk (CHAT-7 fix).
 *
 * **Streaming state machine:** streaming is modelled as a sealed
 * interface ([StreamState]) with explicit states (Idle, Starting,
 * Streaming, Completing, Cancelling, Failed). Transitions happen in
 * one place (the stream collector in [startStream]). Cancellation is
 * a first-class state, not an exception (CHAT-10 fix).
 *
 * **Server-authoritative persistence:** the client never invents
 * message IDs. On stream completion, the ViewModel calls
 * `loadSession(sessionId)` to fetch the server's authoritative version
 * (with real `message_id`s). The Room Flow observer picks up the new
 * messages. This eliminates CHAT-2 (duplicates) and CHAT-6 (orphans)
 * entirely — there's nothing to dedup because there's only one write
 * path, and it uses the server's IDs.
 *
 * **Optimistic UI:** the user message is shown immediately via
 * [pendingMessages] (an in-memory StateFlow, NOT Room). On stream
 * completion, `loadSession` fetches both the user message and the
 * assistant response with real IDs; the [allMessages] combiner dedups
 * the pending copy by content+timestamp and removes it.
 *
 * ## Bug-to-fix cross-reference
 *
 * | Bug | Fix |
 * |-----|-----|
 * | CHAT-1 (Stop loses messages) | [stopStreaming] + [handleCancellation] use `NonCancellable` |
 * | CHAT-3 (flicker) | Streaming overlay stays visible during `Completing` state |
 * | CHAT-5 (reasoning O(N)) | [StreamState.Streaming.reasoning] is a StringBuilder |
 * | CHAT-7 (pagination overwritten) | [_visibleCount] changes the Room query, not the list |
 * | CHAT-9 (no manual refresh) | [refresh] method forces loadSession |
 * | CHAT-10 (exception for control flow) | `for` loop with `break` instead of `StreamEndSignal` |
 */
class ChatViewModel(
    private val sessionRepository: SessionRepository,
    private val chatStream: ChatStream
) : ViewModel() {

    // ------------------------------------------------------------------
    // Single source of truth: Room Flow
    // ------------------------------------------------------------------

    /**
     * The session whose messages we're currently showing.
     *
     * Set by [loadMessages]. Drives the [messages] Flow via
     * [flatMapLatest] — when this changes, the previous Room observer
     * is cancelled and a new one starts.
     */
    private val _sessionId = MutableStateFlow("")
    val sessionId: StateFlow<String> = _sessionId.asStateFlow()

    /**
     * How many messages to show. Initial = 20, grows by 20 on
     * "Load more". Drives the [messages] Flow — when this changes,
     * Room re-emits with the larger window.
     *
     * CHAT-7 fix: this replaces the old pattern of manually prepending
     * older messages to `_messages.value`, which got overwritten by the
     * Room Flow observer on every cache write.
     */
    private val _visibleCount = MutableStateFlow(INITIAL_MESSAGE_COUNT)

    /**
     * The single source of truth for persisted messages.
     *
     * ALWAYS a projection of Room. Nothing writes to it except the
     * Room Flow observer. Pagination changes the query (via
     * [_visibleCount]), not the list.
     *
     * Implementation: combine [_sessionId] and [_visibleCount] so the
     * Room query re-runs when EITHER changes. The outer [flatMapLatest]
     * cancels the previous Room observer when the session or visible
     * count changes.
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
    // Streaming state machine
    // ------------------------------------------------------------------

    private val _streamState = MutableStateFlow<StreamState>(StreamState.Idle)
    val streamState: StateFlow<StreamState> = _streamState.asStateFlow()

    /**
     * Convenience: true while any non-Idle state is active. The UI uses
     * this to disable the Send button, show the Stop button, etc.
     */
    val isStreaming: StateFlow<Boolean> = _streamState
        .map { it !is StreamState.Idle && it !is StreamState.Failed }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    // ------------------------------------------------------------------
    // Backward-compat shims for the old ChatScreen API
    // ------------------------------------------------------------------
    // These exist ONLY so the build stays green between this commit
    // (commit 5 — ViewModel rewrite) and commit 6 (ChatScreen rewrite).
    // Commit 6 will switch ChatScreen to read [streamState] directly
    // and these shims will be deleted.
    //
    // Do NOT use these in new code — read [streamState] instead.

    @Deprecated("Read streamState as? StreamState.Streaming instead. Removed in commit 6.")
    val streamingContent: StateFlow<String> = _streamState
        .map { state ->
            when (state) {
                is StreamState.Streaming -> state.content.toString()
                is StreamState.Completing -> ""  // brief gap — commit 6 fixes the flicker
                else -> ""
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    @Deprecated("Read streamState as? StreamState.Streaming instead. Removed in commit 6.")
    val streamingReasoning: StateFlow<String> = _streamState
        .map { (it as? StreamState.Streaming)?.reasoning?.toString() ?: "" }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    @Deprecated("Read streamState as? StreamState.Streaming instead. Removed in commit 6.")
    val streamingTools: StateFlow<List<ToolCallInfo>> = _streamState
        .map { (it as? StreamState.Streaming)?.tools ?: emptyList() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ------------------------------------------------------------------
    // Pending messages (optimistic UI, NOT in Room)
    // ------------------------------------------------------------------

    /**
     * User messages that have been sent but not yet acknowledged by the
     * server. Shown optimistically in the chat UI alongside persisted
     * messages.
     *
     * **Never written to Room.** If the stream fails to start, the
     * pending message is removed (CHAT-6 fix — no orphan). If the
     * stream succeeds, `loadSession` fetches the server's version
     * (with a real ID) and the [allMessages] combiner dedups the
     * pending copy by content+timestamp.
     */
    private val _pendingMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val pendingMessages: StateFlow<List<ChatMessage>> = _pendingMessages.asStateFlow()

    /**
     * Combined view: pending + persisted, for the UI to render.
     *
     * Pending messages come first (they're newer). The combiner dedups
     * by `content + timestamp` so that when `loadSession` returns the
     * server's version of the user message (with a real ID), the
     * pending copy disappears from the list without a flicker.
     *
     * The dedup heuristic could fail if the user sends two identical
     * messages in the same millisecond — vanishingly rare (keyboard
     * debounce alone is >16ms). A more robust dedup would use the
     * server's message_id, but that requires the stream_end event to
     * include it — a server-side change. For now, the heuristic is
     * good enough. (See chat rewrite §8.3.)
     */
    val allMessages: StateFlow<List<ChatMessage>> = combine(
        _pendingMessages, messages
    ) { pending, persisted ->
        if (pending.isEmpty()) return@combine persisted
        val persistedKeys = persisted.map { it.content + it.timestamp }.toSet()
        pending.filter { (it.content + it.timestamp) !in persistedKeys } + persisted
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ------------------------------------------------------------------
    // Misc state
    // ------------------------------------------------------------------

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** True while fetching from server AND cache is empty. */
    private val _isInitialLoading = MutableStateFlow(false)
    val isInitialLoading: StateFlow<Boolean> = _isInitialLoading.asStateFlow()

    /**
     * How many older messages are available to load (total - visible).
     *
     * Derived from the reactive [SessionRepository.getMessageCountFlow]
     * — updates automatically when loadSession inserts new messages
     * after a stream completes. No manual state updates needed.
     */
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

    /** Total cached messages for this session (for the "Load more (N)" label). */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val totalCached: StateFlow<Int> = _sessionId
        .flatMapLatest { sid ->
            if (sid.isEmpty()) kotlinx.coroutines.flow.flowOf(0)
            else sessionRepository.getMessageCountFlow(sid)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    private var streamJob: Job? = null

    // ------------------------------------------------------------------
    // Loading
    // ------------------------------------------------------------------

    /**
     * Called when the chat screen appears. Sets the session ID — the
     * [messages] Flow picks up the change via [flatMapLatest] and
     * starts observing the Room cache for that session.
     *
     * Also kicks off a background server fetch via [loadSession] to
     * refresh the cache. If the session is already loaded (return
     * navigation), the server fetch is skipped to avoid redundant
     * network calls — use [refresh] to force it.
     */
    fun loadMessages(sessionId: String) {
        val isSameSession = _sessionId.value == sessionId
        _sessionId.value = sessionId

        // The flatMapLatest on _sessionId handles the Room observer.
        // We just need to kick off the server fetch.
        if (isSameSession) return

        _isInitialLoading.value = true
        viewModelScope.launch {
            try {
                sessionRepository.loadSession(sessionId, msgLimit = INITIAL_MESSAGE_COUNT)
                    .onFailure { e ->
                        // Only show error if we have no cached messages to show
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

    /**
     * Manual refresh — forces [loadSession] regardless of whether the
     * session is already loaded. Use this when the user pulls to
     * refresh or taps the refresh action.
     *
     * CHAT-9 fix: the old `loadMessages` had `if (isSameSession) return`
     * which skipped the server fetch on return navigation. If new
     * messages arrived on the server while the user was away, they
     * never appeared. This method bypasses that guard.
     */
    fun refresh(sessionId: String) {
        _sessionId.value = sessionId
        _isInitialLoading.value = true
        viewModelScope.launch {
            try {
                sessionRepository.loadSession(sessionId, msgLimit = _visibleCount.value)
                    .onFailure { e ->
                        _error.value = e.message ?: "Failed to refresh"
                    }
            } catch (e: Exception) {
                _error.value = friendlyError(e)
            } finally {
                _isInitialLoading.value = false
            }
        }
    }

    /**
     * Pagination — just increase the LIMIT. Room re-emits with the
     * larger window. No manual list manipulation, no overwrite risk.
     *
     * CHAT-7 fix: the old `loadMoreMessages` manually prepended older
     * messages to `_messages.value`, which got overwritten by the Room
     * Flow observer on every cache write. Older messages vanished
     * moments after loading them.
     */
    fun loadMoreMessages(sessionId: String) {
        // sessionId parameter kept for source compatibility with the
        // old API — the new design derives the session from _sessionId.
        _visibleCount.value += LOAD_MORE_INCREMENT
    }

    // ------------------------------------------------------------------
    // Sending
    // ------------------------------------------------------------------

    /**
     * Upload a file to the server. Returns the server file path on
     * success.
     */
    suspend fun uploadAttachment(sessionId: String, fileUri: String): Result<String> {
        return sessionRepository.uploadFile(sessionId, fileUri)
    }

    /**
     * Send a message. The user message is added to [pendingMessages]
     * (optimistic UI, NOT Room) and the stream is started.
     *
     * If the stream fails to start, the pending message is removed
     * (CHAT-6 fix). If the stream succeeds, [loadSession] fetches the
     * server's version (with a real ID) and the [allMessages] combiner
     * dedups the pending copy.
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
        if (text.isBlank() || isStreaming.value) return

        val userMessage = ChatMessage(
            messageId = "pending_${System.currentTimeMillis()}", // never persisted
            role = "user",
            content = text.trim(),
            timestamp = System.currentTimeMillis()
        )
        _pendingMessages.value = _pendingMessages.value + userMessage
        _error.value = null

        startStream(
            sessionId = sessionId,
            userMessage = userMessage,
            message = text.trim(),
            model = model, provider = provider,
            workspace = workspace, profile = profile,
            files = attachments.ifEmpty { null }
        )
    }

    // ------------------------------------------------------------------
    // Streaming
    // ------------------------------------------------------------------

    /**
     * Start a chat stream. Implements the full streaming state machine
     * (see [StreamState] docstring for the transition diagram).
     *
     * ## Cancellation protocol (CHAT-1 fix)
     *
     * If the user taps Stop (or the ViewModel is cleared while
     * streaming), `streamJob?.cancel()` throws [CancellationException]
     * into this coroutine. The catch block calls [handleCancellation]
     * which runs in a `withContext(NonCancellable)` block so the
     * cleanup (calling [ChatStream.cancelStream] and [loadSession])
     * completes even though the coroutine is being cancelled.
     *
     * ## StreamEnd (CHAT-10 fix)
     *
     * The old code threw a `StreamEndSignal` exception to break out
     * of the `collect` loop. The new code uses a `for` loop with
     * `break` — no exception, no stack trace, no interaction with
     * cancellation.
     *
     * ## Reasoning StringBuilder (CHAT-5 fix)
     *
     * Both content and reasoning use [StringBuilder] for O(1) append
     * per token. The old code used `String +=` for reasoning, which
     * was O(N) per event → O(N²) total for long thinking traces.
     */
    private fun startStream(
        sessionId: String,
        userMessage: ChatMessage,
        message: String,
        model: String?, provider: String?,
        workspace: String?, profile: String?,
        files: List<String>?
    ) {
        _streamState.value = StreamState.Starting(userMessage)

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
                    _streamState.value = StreamState.Failed(_error.value!!)
                    // CHAT-6 fix: remove the pending user message —
                    // the server never received it.
                    _pendingMessages.value = _pendingMessages.value.filter { it != userMessage }
                    return@launch
                }

                streamId = start.getOrNull()?.stream_id ?: run {
                    _error.value = "No stream ID returned"
                    _streamState.value = StreamState.Failed(_error.value!!)
                    _pendingMessages.value = _pendingMessages.value.filter { it != userMessage }
                    return@launch
                }

                streamStarted = true
                _streamState.value = StreamState.Streaming(
                    streamId = streamId,
                    userMessage = userMessage,
                    content = content,        // shared StringBuilder
                    reasoning = reasoning,
                    tools = tools.toList()
                )

                // Use a for loop (not collect) so we can break on
                // StreamEnd. CHAT-10 fix: no exception for control flow.
                for (event in chatStream.streamEvents(streamId)) {
                    when (event) {
                        is ChatStream.StreamEvent.Token -> {
                            content.append(event.token)
                            // Emit a copy to trigger recomposition.
                            // The StringBuilder is shared — we copy the
                            // state object, not the buffer itself.
                            _streamState.value = (_streamState.value as StreamState.Streaming)
                                .copy(content = content, tools = tools.toList())
                        }
                        is ChatStream.StreamEvent.Reasoning -> {
                            // CHAT-5 fix: O(1) append, same as content.
                            reasoning.append(event.text)
                            _streamState.value = (_streamState.value as StreamState.Streaming)
                                .copy(reasoning = reasoning)
                        }
                        is ChatStream.StreamEvent.Tool -> {
                            tools.add(ToolCallInfo(event.name, event.args, null))
                            _streamState.value = (_streamState.value as StreamState.Streaming)
                                .copy(tools = tools.toList())
                        }
                        is ChatStream.StreamEvent.ToolComplete -> {
                            val idx = tools.indexOfLast {
                                it.name == event.name && it.result == null
                            }
                            if (idx >= 0) {
                                tools[idx] = tools[idx].copy(result = event.result)
                            }
                            _streamState.value = (_streamState.value as StreamState.Streaming)
                                .copy(tools = tools.toList())
                        }
                        is ChatStream.StreamEvent.Title -> {
                            // The session list will pick up the new title
                            // on next refresh. Logging for debug.
                            android.util.Log.d("ChatViewModel", "Title: ${event.title}")
                        }
                        is ChatStream.StreamEvent.Done -> Unit // per-message, not stream-end
                        is ChatStream.StreamEvent.StreamEnd -> break // CHAT-10 fix
                        is ChatStream.StreamEvent.Error -> {
                            _error.value = event.message ?: "Stream error"
                            _streamState.value = StreamState.Failed(_error.value!!)
                            return@launch
                        }
                        is ChatStream.StreamEvent.Unknown -> Unit
                    }
                }

                // Normal completion: fetch the authoritative version
                // from the server. This is the heart of the
                // server-authoritative persistence model — the client
                // never invents message IDs.
                _streamState.value = StreamState.Completing(streamId)
                sessionRepository.loadSession(sessionId, msgLimit = _visibleCount.value)

                // Room Flow emits → allMessages updates → UI swaps
                // streaming bubble for the persisted MessageBubble
                // automatically (CHAT-3 fix: no flicker because the
                // Streaming overlay stays visible during Completing).

                // Remove the pending user message (it's now in Room
                // with a real ID).
                _pendingMessages.value = _pendingMessages.value.filter { it != userMessage }
                _streamState.value = StreamState.Idle
            } catch (e: CancellationException) {
                // User tapped Stop, or ViewModel was cleared.
                // Handle cleanup in a NonCancellable block — the
                // coroutine is being cancelled and any suspending call
                // would throw CancellationException again.
                handleCancellation(sessionId, streamId, streamStarted, userMessage)
                throw e // re-throw so the coroutine properly terminates
            } catch (e: Exception) {
                _error.value = friendlyError(e)
                _streamState.value = StreamState.Failed(_error.value!!)
                if (!streamStarted) {
                    // CHAT-6 fix: stream never started — remove the
                    // pending user message so it doesn't become an orphan.
                    _pendingMessages.value = _pendingMessages.value.filter { it != userMessage }
                }
            }
        }
    }

    /**
     * Stop the active stream. Sets the state to [StreamState.Cancelling],
     * cancels the stream job, then (in a [NonCancellable] context) tells
     * the server to stop generating and fetches whatever was already
     * produced.
     *
     * CHAT-1 fix: the old `stopStreaming` just called
     * `streamJob?.cancel()` and set `_isStreaming = false`. The
     * coroutine's `finally` block tried to persist messages but was
     * itself being cancelled (suspending in a cancelled coroutine
     * throws [CancellationException]). Neither message was saved, and
     * `cancelStream` was never called — the server kept generating
     * tokens. The next sendMessage got a 409 Conflict.
     */
    fun stopStreaming() {
        val state = _streamState.value
        val streamId = (state as? StreamState.Streaming)?.streamId
            ?: (state as? StreamState.Completing)?.streamId

        _streamState.value = streamId?.let { StreamState.Cancelling(it) } ?: StreamState.Idle
        streamJob?.cancel()
        streamJob = null

        // Tell the server to stop generating. NonCancellable so it
        // runs even if the viewModelScope is already cancelling.
        viewModelScope.launch(Dispatchers.IO + NonCancellable) {
            streamId?.let { chatStream.cancelStream(it) }
            // Fetch whatever the server already generated (partial
            // response). The Room Flow observer picks it up and the
            // UI updates.
            sessionRepository.loadSession(_sessionId.value, msgLimit = _visibleCount.value)
            _pendingMessages.value = emptyList() // clear optimistic UI
            _streamState.value = StreamState.Idle
        }
    }

    /**
     * Cleanup after cancellation. Runs in a [NonCancellable] block so
     * it completes even though the coroutine is being cancelled.
     *
     * Called from the `catch (CancellationException)` block in
     * [startStream]. The sequence:
     *   1. Tell the server to stop generating (idempotent — 404/409 OK).
     *   2. If the stream had started, fetch whatever the server
     *      generated before cancellation.
     *   3. Remove the pending user message either way.
     *   4. Set state back to Idle.
     *
     * See: hermez-chat-rewrite.pdf §9.2 (Cancellation Protocol).
     */
    private suspend fun handleCancellation(
        sessionId: String,
        streamId: String?,
        streamStarted: Boolean,
        userMessage: ChatMessage
    ) {
        withContext(NonCancellable) {
            streamId?.let { chatStream.cancelStream(it) }
            if (streamStarted) {
                // Stream had started — fetch whatever the server
                // generated before we cancelled. The Room Flow observer
                // picks it up; the UI shows the partial response.
                sessionRepository.loadSession(sessionId, msgLimit = _visibleCount.value)
            }
            // Remove the pending user message either way. If the stream
            // started, loadSession fetched the server's version (with a
            // real ID) and the allMessages dedup logic removes the
            // pending copy. If it didn't start, we just clear the
            // optimistic UI.
            _pendingMessages.value = _pendingMessages.value.filter { it != userMessage }
            _streamState.value = StreamState.Idle
        }
    }

    // ------------------------------------------------------------------
    // Mapping
    // ------------------------------------------------------------------

    private fun dev.hermes.core.data.local.MessageEntity.toChatMessage() = ChatMessage(
        messageId = messageId,
        role = role,
        content = content,
        timestamp = timestamp
    )

    companion object {
        private const val INITIAL_MESSAGE_COUNT = 20
        private const val LOAD_MORE_INCREMENT = 20
    }
}
