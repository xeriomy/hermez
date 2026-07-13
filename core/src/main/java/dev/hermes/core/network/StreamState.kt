package dev.hermes.core.network

import dev.hermes.core.data.ChatMessage
import dev.hermes.core.data.ToolCallInfo

/**
 * Explicit state machine for the chat streaming lifecycle.
 *
 * The previous design used a boolean `isStreaming` flag plus ad-hoc
 * cleanup in a `finally` block. This led to CHAT-1 (Stop loses messages
 * + server keeps streaming), CHAT-6 (orphaned messages on start
 * failure), and CHAT-10 (StreamEndSignal exception-for-control-flow).
 *
 * The new design models streaming as a sealed interface with explicit
 * states. Transitions happen in one place (the stream collector in
 * [dev.hermes.hermex.ui.chat.ChatViewModel]). The UI observes the state
 * and renders accordingly. Cancellation is a first-class state, not an
 * exception.
 *
 * ## State transitions
 *
 * ```
 * Idle ──sendMessage──▶ Starting ──startChat ok──▶ Streaming ──StreamEnd──▶ Completing ──▶ Idle
 *   │                       │                          │
 *   │                       │                          ├──Stop──▶ Cancelling ──▶ Idle
 *   │                       │                          │
 *   │                       └──startChat fail──▶ Failed ──▶ Idle
 *   │
 *   └──(already Idle, nothing to do)
 * ```
 *
 * The UI renders the streaming overlay only when state is [Streaming]
 * or [Completing]; the persisted message replaces it once Room emits
 * the server-fetched version.
 *
 * ## Why the [Streaming] state holds [StringBuilder]s
 *
 * Tokens arrive at high frequency (sometimes 50+ per second on a fast
 * model). Using `String +=` for each token is O(N) per append → O(N²)
 * total per response (CHAT-5 for reasoning; PERF-1 originally fixed
 * this for content). Holding a [StringBuilder] in the state and only
 * emitting a copy of the state on each token keeps appends O(1) while
 * still triggering recomposition.
 *
 * **Threading note:** [StringBuilder] is NOT thread-safe. The state
 * must only be mutated from the single coroutine that collects the
 * stream events (the `streamJob` in ChatViewModel). The UI only reads
 * it.
 */
sealed interface StreamState {
    /**
     * No active stream. The chat is idle — the user can type and send.
     */
    data object Idle : StreamState

    /**
     * `POST /api/chat/start` is in flight. The user message is shown
     * optimistically (in `pendingMessages`, not in Room) and the UI
     * shows a spinner in the streaming bubble area.
     *
     * Transitions:
     *  - → [Streaming] on success (startChat returned a stream_id)
     *  - → [Failed] on failure (network, 401, 409 conflict, 500, …)
     */
    data class Starting(val userMessage: ChatMessage) : StreamState

    /**
     * The SSE stream is open and events are flowing. The UI shows the
     * partial response as plain text (no Markdown re-parse — see
     * PERF-1) and the Stop button is visible.
     *
     * @param streamId The server-assigned stream ID, used for cancel
     *   and reattach.
     * @param userMessage The user message that triggered this stream.
     *   Held so the Cancelling path can remove it from pendingMessages.
     * @param content O(1) append buffer for assistant tokens.
     * @param reasoning O(1) append buffer for reasoning/thinking tokens
     *   (CHAT-5 fix — was previously O(N) String concat).
     * @param tools Tool calls observed so far. Copied on update so the
     *   UI sees a stable snapshot.
     */
    data class Streaming(
        val streamId: String,
        val userMessage: ChatMessage,
        val content: StringBuilder,
        val reasoning: StringBuilder,
        val tools: List<ToolCallInfo>
    ) : StreamState

    /**
     * The stream emitted [ChatStream.StreamEvent.StreamEnd] (or the
     * for-loop broke out of the event collector). The ViewModel is now
     * calling `loadSession` to fetch the server's authoritative version
     * of the messages (with real message_ids, not the fake
     * `pending_*` IDs used by the optimistic UI).
     *
     * The UI keeps the streaming bubble visible during this state so
     * there's no flicker (CHAT-3 fix). When the Room Flow emits the
     * persisted message, the state goes to [Idle] and the streaming
     * bubble disappears.
     */
    data class Completing(val streamId: String) : StreamState

    /**
     * The user tapped Stop. The ViewModel has called
     * `streamJob?.cancel()` and is now (in a NonCancellable context)
     * calling `chatStream.cancelStream(streamId)` to tell the server
     * to stop generating, then `loadSession` to fetch whatever the
     * server already produced (the partial response).
     *
     * The UI disables the Stop button during this state to prevent
     * double-cancellation.
     */
    data class Cancelling(val streamId: String) : StreamState

    /**
     * The stream failed — either `startChat` returned an error, the
     * SSE stream emitted an [ChatStream.StreamEvent.Error], or the
     * reattach protocol gave up after 3 retries.
     *
     * The user message that triggered the failed stream has already
     * been removed from `pendingMessages` (CHAT-6 fix) before this
     * state is entered. The UI shows the error message and returns to
     * [Idle] on the next user action.
     */
    data class Failed(val message: String) : StreamState
}
