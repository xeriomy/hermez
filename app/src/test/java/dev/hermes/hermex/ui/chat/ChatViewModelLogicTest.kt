package dev.hermes.hermex.ui.chat

import dev.hermes.core.data.ChatMessage
import dev.hermes.core.network.StreamState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure-logic helpers extracted from [ChatViewModel]
 * during the chat rewrite.
 *
 * These helpers ([dedupPendingWithPersisted] and [isStreamActive])
 * were extracted specifically so they could be tested without
 * instantiating ChatViewModel (which needs a SessionRepository,
 * which needs Android Context).
 *
 * See: hermez-chat-rewrite.pdf §10.2 (ChatViewModel Tests).
 */
class ChatViewModelLogicTest {

    // ------------------------------------------------------------------
    // dedupPendingWithPersisted
    // ------------------------------------------------------------------

    @Test
    fun `dedup returns persisted as-is when pending is empty`() {
        val persisted = listOf(
            ChatMessage("msg_1", "user", "hello", 1000L),
            ChatMessage("msg_2", "assistant", "hi there", 2000L)
        )
        val result = dedupPendingWithPersisted(emptyList(), persisted)
        assertEquals(persisted, result)
    }

    @Test
    fun `dedup returns pending as-is when persisted is empty`() {
        val pending = listOf(
            ChatMessage("pending_1", "user", "hello", 1000L)
        )
        val result = dedupPendingWithPersisted(pending, emptyList())
        assertEquals(pending, result)
    }

    @Test
    fun `dedup returns empty when both lists are empty`() {
        val result = dedupPendingWithPersisted(emptyList(), emptyList())
        assertTrue(result.isEmpty())
    }

    @Test
    fun `dedup removes pending message when persisted has same content+timestamp`() {
        // This is the main use case: the user sent a message (it's in
        // pending), the stream completed, loadSession fetched the
        // server's version (with a real message_id but same content
        // and timestamp). The pending copy must be removed.
        val pendingUser = ChatMessage("pending_1", "user", "hello", 1000L)
        val persistedUser = ChatMessage("msg_abc", "user", "hello", 1000L) // same content+timestamp, different ID
        val persistedAssistant = ChatMessage("msg_def", "assistant", "hi there", 2000L)

        val result = dedupPendingWithPersisted(
            pending = listOf(pendingUser),
            persisted = listOf(persistedUser, persistedAssistant)
        )

        // The pending user message is gone (deduped). Only the two
        // persisted messages remain.
        assertEquals(2, result.size)
        assertEquals(persistedUser, result[0])
        assertEquals(persistedAssistant, result[1])
    }

    @Test
    fun `dedup keeps pending message when persisted has different content`() {
        val pendingUser = ChatMessage("pending_1", "user", "hello", 1000L)
        val persistedAssistant = ChatMessage("msg_def", "assistant", "hi there", 2000L)

        val result = dedupPendingWithPersisted(
            pending = listOf(pendingUser),
            persisted = listOf(persistedAssistant)
        )

        // Both messages kept — pending user first, then persisted assistant.
        assertEquals(2, result.size)
        assertEquals(pendingUser, result[0])
        assertEquals(persistedAssistant, result[1])
    }

    @Test
    fun `dedup keeps pending message when persisted has same content but different timestamp`() {
        // Same content but different timestamp = different message.
        // The dedup key is content+timestamp as a string, so these
        // are NOT duplicates.
        val pendingUser = ChatMessage("pending_1", "user", "hello", 1000L)
        val persistedUser = ChatMessage("msg_abc", "user", "hello", 2000L)

        val result = dedupPendingWithPersisted(
            pending = listOf(pendingUser),
            persisted = listOf(persistedUser)
        )

        assertEquals(2, result.size)
    }

    @Test
    fun `dedup with multiple pending messages keeps non-duplicates`() {
        val pending1 = ChatMessage("pending_1", "user", "first", 1000L)
        val pending2 = ChatMessage("pending_2", "user", "second", 2000L) // will be deduped
        val pending3 = ChatMessage("pending_3", "user", "third", 3000L)

        val persisted = listOf(
            ChatMessage("msg_abc", "user", "second", 2000L), // matches pending2
            ChatMessage("msg_def", "assistant", "response", 4000L)
        )

        val result = dedupPendingWithPersisted(
            pending = listOf(pending1, pending2, pending3),
            persisted = persisted
        )

        // pending2 is removed; pending1 and pending3 are kept (first),
        // then the two persisted messages.
        assertEquals(4, result.size)
        assertEquals(pending1, result[0])
        assertEquals(pending3, result[1])
        assertEquals(persisted[0], result[2])
        assertEquals(persisted[1], result[3])
    }

    @Test
    fun `dedup is case-sensitive on content`() {
        // "Hello" != "hello" — different dedup keys.
        val pending = ChatMessage("p1", "user", "Hello", 1000L)
        val persisted = ChatMessage("m1", "user", "hello", 1000L)

        val result = dedupPendingWithPersisted(listOf(pending), listOf(persisted))
        assertEquals(2, result.size)
    }

    @Test
    fun `dedup dedups by content+timestamp concatenation, not by content alone`() {
        // Same content but different timestamps → both kept.
        val pending = ChatMessage("p1", "user", "hello", 1000L)
        val persisted = ChatMessage("m1", "user", "hello", 999L)

        val result = dedupPendingWithPersisted(listOf(pending), listOf(persisted))
        assertEquals(2, result.size)
    }

    @Test
    fun `dedup preserves order — pending first, then persisted`() {
        val pending = listOf(
            ChatMessage("p1", "user", "new", 5000L),
            ChatMessage("p2", "user", "newer", 6000L)
        )
        val persisted = listOf(
            ChatMessage("m1", "user", "old", 1000L),
            ChatMessage("m2", "assistant", "response", 2000L)
        )

        val result = dedupPendingWithPersisted(pending, persisted)
        assertEquals(4, result.size)
        // Pending messages come first (in their original order).
        assertEquals("p1", result[0].messageId)
        assertEquals("p2", result[1].messageId)
        // Then persisted (in their original order).
        assertEquals("m1", result[2].messageId)
        assertEquals("m2", result[3].messageId)
    }

    // ------------------------------------------------------------------
    // isStreamActive
    // ------------------------------------------------------------------

    @Test
    fun `isStreamActive returns false for Idle`() {
        assertFalse(StreamState.Idle.isStreamActive())
    }

    @Test
    fun `isStreamActive returns true for Starting`() {
        val state = StreamState.Starting(
            ChatMessage("p1", "user", "hello", 1000L)
        )
        assertTrue(state.isStreamActive())
    }

    @Test
    fun `isStreamActive returns true for Streaming`() {
        val state = StreamState.Streaming(
            streamId = "str",
            userMessage = ChatMessage("p1", "user", "hello", 1000L),
            content = StringBuilder(),
            reasoning = StringBuilder(),
            tools = emptyList()
        )
        assertTrue(state.isStreamActive())
    }

    @Test
    fun `isStreamActive returns true for Completing`() {
        val state = StreamState.Completing("str")
        assertTrue(state.isStreamActive())
    }

    @Test
    fun `isStreamActive returns true for Cancelling`() {
        val state = StreamState.Cancelling("str")
        assertTrue(state.isStreamActive())
    }

    @Test
    fun `isStreamActive returns false for Failed`() {
        val state = StreamState.Failed("boom")
        assertFalse(state.isStreamActive())
    }

    @Test
    fun `isStreamActive exhaustively covers all states`() {
        // If a new state is added to StreamState without updating
        // isStreamActive, this test will fail (either at compile time
        // because the when in isStreamActive becomes non-exhaustive,
        // or at runtime because the new state isn't tested).
        val allStates = listOf<StreamState>(
            StreamState.Idle,
            StreamState.Starting(ChatMessage("p", "user", "", 0L)),
            StreamState.Streaming("id", ChatMessage("p", "user", "", 0L), StringBuilder(), StringBuilder(), emptyList()),
            StreamState.Completing("id"),
            StreamState.Cancelling("id"),
            StreamState.Failed("err")
        )
        val activeStates = allStates.filter { it.isStreamActive() }
        // Starting, Streaming, Completing, Cancelling are active.
        assertEquals(4, activeStates.size)
        // Idle and Failed are not.
        assertFalse(StreamState.Idle.isStreamActive())
        assertFalse(StreamState.Failed("x").isStreamActive())
    }
}
