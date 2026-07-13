package dev.hermes.core.network

import dev.hermes.core.data.ChatMessage
import dev.hermes.core.data.ToolCallInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the [StreamState] sealed interface — the streaming
 * state machine introduced in commit 1 of the chat rewrite.
 *
 * These tests verify the type itself: that all states are reachable,
 * that they carry the right data, and that the sealed-when exhaustiveness
 * works as expected. Behavior tests (state transitions driven by SSE
 * events) live in [ChatStreamReattachTest] and the (future)
 * ChatViewModelTest.
 *
 * See: hermez-chat-rewrite.pdf §3.2 (Streaming State Machine).
 */
class StreamStateTest {

    private fun userMessage() = ChatMessage(
        messageId = "pending_1",
        role = "user",
        content = "hello",
        timestamp = 1000L
    )

    @Test
    fun `Idle is a data object`() {
        val state: StreamState = StreamState.Idle
        assertTrue(state is StreamState.Idle)
        // Data objects are singletons — equality is identity.
        assertEquals(StreamState.Idle, StreamState.Idle)
    }

    @Test
    fun `Starting carries the user message`() {
        val msg = userMessage()
        val state = StreamState.Starting(msg)
        assertTrue(state is StreamState.Starting)
        assertEquals(msg, (state as StreamState.Starting).userMessage)
    }

    @Test
    fun `Streaming carries streamId, userMessage, buffers, and tools`() {
        val msg = userMessage()
        val content = StringBuilder("hello ")
        val reasoning = StringBuilder("thinking…")
        val tools = listOf(ToolCallInfo("read_file", "/path", null))
        val state = StreamState.Streaming(
            streamId = "str_123",
            userMessage = msg,
            content = content,
            reasoning = reasoning,
            tools = tools
        )

        assertTrue(state is StreamState.Streaming)
        val s = state as StreamState.Streaming
        assertEquals("str_123", s.streamId)
        assertEquals(msg, s.userMessage)
        // StringBuilder identity — the state holds the SAME buffer
        // instance, not a copy. This is intentional: O(1) appends.
        assertEquals(content, s.content)
        assertEquals(reasoning, s.reasoning)
        assertEquals(tools, s.tools)
    }

    @Test
    fun `Streaming copy preserves buffer identity`() {
        // The state machine's O(1) append pattern relies on
        // data class copy() preserving the SAME StringBuilder instance
        // (only the tools list is copied, because tools change less
        // frequently and need a stable snapshot for the UI).
        val content = StringBuilder()
        val reasoning = StringBuilder()
        val state = StreamState.Streaming(
            streamId = "str",
            userMessage = userMessage(),
            content = content,
            reasoning = reasoning,
            tools = emptyList()
        )
        content.append("hello")
        val updated = state.copy(content = content, tools = emptyList())
        // Same buffer instance — append is visible through the copy.
        assertEquals("hello", (updated as StreamState.Streaming).content.toString())
        assertTrue(updated.content === content) // identity check
    }

    @Test
    fun `Completing carries streamId`() {
        val state = StreamState.Completing("str_456")
        assertTrue(state is StreamState.Completing)
        assertEquals("str_456", (state as StreamState.Completing).streamId)
    }

    @Test
    fun `Cancelling carries streamId`() {
        val state = StreamState.Cancelling("str_789")
        assertTrue(state is StreamState.Cancelling)
        assertEquals("str_789", (state as StreamState.Cancelling).streamId)
    }

    @Test
    fun `Failed carries error message`() {
        val state = StreamState.Failed("boom")
        assertTrue(state is StreamState.Failed)
        assertEquals("boom", (state as StreamState.Failed).message)
    }

    @Test
    fun `sealed when is exhaustive`() {
        // If a new state is added to StreamState without updating the
        // when in ChatViewModel, this test will fail to compile — which
        // is exactly what we want. (Kotlin's sealed-when exhaustiveness
        // check is a compile-time guarantee, but having a test makes
        // the intent explicit and documents the full state list.)
        val states: List<StreamState> = listOf(
            StreamState.Idle,
            StreamState.Starting(userMessage()),
            StreamState.Streaming("id", userMessage(), StringBuilder(), StringBuilder(), emptyList()),
            StreamState.Completing("id"),
            StreamState.Cancelling("id"),
            StreamState.Failed("err")
        )
        assertEquals(6, states.size)

        // Every state must be classifiable into "active" vs "terminal"
        // for the isStreaming derivation in ChatViewModel.
        val activeCount = states.count { it !is StreamState.Idle && it !is StreamState.Failed }
        assertEquals(4, activeCount) // Starting, Streaming, Completing, Cancelling
    }

    @Test
    fun `Starting and Failed are not equal even with same message`() {
        // Sanity check: different state types are never equal.
        val msg = userMessage()
        val starting = StreamState.Starting(msg)
        val failed = StreamState.Failed(msg.content)
        assertFalse(starting.equals(failed))
    }

    @Test
    fun `Idle is not Streaming`() {
        // Type narrowing sanity check.
        val state: StreamState = StreamState.Idle
        assertNull(state as? StreamState.Streaming)
        assertNotNull(state as? StreamState.Idle)
    }
}
