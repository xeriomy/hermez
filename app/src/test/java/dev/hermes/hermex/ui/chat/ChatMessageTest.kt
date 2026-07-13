package dev.hermes.hermex.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for ChatMessage data class.
 *
 * Pure data tests — no Android or Compose dependencies.
 */
class ChatMessageTest {

    @Test
    fun `ChatMessage holds all fields`() {
        val msg = ChatMessage(
            messageId = "msg-001",
            role = "user",
            content = "Hello, world!",
            timestamp = 1783449907000L
        )
        assertEquals("msg-001", msg.messageId)
        assertEquals("user", msg.role)
        assertEquals("Hello, world!", msg.content)
        assertEquals(1783449907000L, msg.timestamp)
    }

    @Test
    fun `ChatMessage equality`() {
        val a = ChatMessage("1", "user", "hi", 1000L)
        val b = ChatMessage("1", "user", "hi", 1000L)
        val c = ChatMessage("2", "user", "hi", 1000L)
        assertEquals(a, b)
        assertFalse(a == c)
    }

    @Test
    fun `ChatMessage copy works for content updates`() {
        val original = ChatMessage("1", "assistant", "Hello", 1000L)
        val updated = original.copy(content = "Hello world")
        assertEquals("Hello world", updated.content)
        assertEquals(original.messageId, updated.messageId)
        assertEquals(original.role, updated.role)
    }
}

/**
 * Unit tests for ToolCallInfo data class (QUAL-5).
 */
class ToolCallInfoTest {

    @Test
    fun `ToolCallInfo without result`() {
        val tool = ToolCallInfo(name = "read_file", args = "/path", result = null)
        assertEquals("read_file", tool.name)
        assertEquals("/path", tool.args)
        assertNull(tool.result)
    }

    @Test
    fun `ToolCallInfo with result`() {
        val tool = ToolCallInfo(name = "bash", args = "ls -la", result = "total 8")
        assertEquals("total 8", tool.result)
    }

    @Test
    fun `ToolCallInfo copy for result update`() {
        val pending = ToolCallInfo("grep", "pattern file", null)
        val completed = pending.copy(result = "match found")
        assertEquals("match found", completed.result)
        assertEquals("grep", completed.name)
    }
}
