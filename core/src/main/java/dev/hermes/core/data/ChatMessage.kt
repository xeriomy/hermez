package dev.hermes.core.data

/**
 * Domain model for a chat message shown in the UI.
 *
 * This is a projection of [dev.hermes.core.data.local.MessageEntity]
 * (which is what Room stores) — minus the JSON-serialized fields that
 * the UI doesn't directly render (toolCalls, attachments, metadata).
 *
 * Domain model vs. entity: the entity is the database shape; this is
 * the UI shape. Keeping them separate means a future schema change
 * doesn't ripple through every Composable.
 *
 * Instances come from two sources:
 *  1. **Server-authoritative** — `loadSession` fetches messages from
 *     the server, the DAO writes them to Room, the Room Flow maps
 *     [MessageEntity] → [ChatMessage]. These have real `messageId`s
 *     like `msg_abc123` from the server.
 *  2. **Optimistic / pending** — when the user taps Send, a
 *     `ChatMessage` with `messageId = "pending_${System.currentTimeMillis()}"`
 *     is added to `pendingMessages` (NOT to Room). On stream completion,
 *     `loadSession` fetches the server's version (with a real ID) and
 *     the pending copy is removed by the dedup logic in `allMessages`.
 *
 * @property messageId Server-assigned ID, or `pending_*` for an
 *   optimistic message that has not yet been confirmed by the server.
 * @property role `"user"` or `"assistant"`.
 * @property content The message text. For assistant messages, this may
 *   be Markdown that the UI renders with [com.mikepenz.markdown.m3.Markdown].
 * @property timestamp Unix epoch milliseconds. The server sends this as
 *   a Float seconds value (e.g. `1783449907.2857065`) — the
 *   [MessageEntity] mapper converts it to Long ms.
 */
data class ChatMessage(
    val messageId: String,
    val role: String,
    val content: String,
    val timestamp: Long
)

/**
 * Represents a tool call observed during streaming (QUAL-5).
 *
 * Shown as a small card in the chat UI while the agent is working.
 * - [result] is `null` while the tool is running.
 * - [result] is set when a matching `tool_complete` SSE event arrives.
 *
 * Matching logic: the last tool with the same [name] and `result == null`
 * is updated. This is a heuristic — if the agent calls the same tool
 * twice in parallel, we may attach the result to the wrong call. The
 * server does not currently provide a tool-call ID, so this is the best
 * we can do.
 */
data class ToolCallInfo(
    val name: String,
    val args: String,
    val result: String?
)
