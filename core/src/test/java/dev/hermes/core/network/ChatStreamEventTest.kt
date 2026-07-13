package dev.hermes.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for ChatStream SSE event parsing.
 *
 * Tests the parseSseEvent logic via the sealed StreamEvent types.
 * Uses the companion object JSON instance (QUAL-13 fix).
 */
class ChatStreamEventTest {

    private val json = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    @Test
    fun `Token event parses correctly`() {
        val data = """{"token":"hello","stream_id":"abc123"}"""
        val event = json.decodeFromString<ChatStream.StreamEvent.Token>(data)
        assertEquals("hello", event.token)
        assertEquals("abc123", event.stream_id)
    }

    @Test
    fun `Token event with null stream_id`() {
        val data = """{"token":"world","stream_id":null}"""
        val event = json.decodeFromString<ChatStream.StreamEvent.Token>(data)
        assertEquals("world", event.token)
        assertNull(event.stream_id)
    }

    @Test
    fun `Tool event parses correctly`() {
        val data = """{"name":"read_file","args":"/path/to/file","stream_id":"s1"}"""
        val event = json.decodeFromString<ChatStream.StreamEvent.Tool>(data)
        assertEquals("read_file", event.name)
        assertEquals("/path/to/file", event.args)
    }

    @Test
    fun `ToolComplete event parses correctly`() {
        val data = """{"name":"read_file","result":"file contents here","stream_id":"s1"}"""
        val event = json.decodeFromString<ChatStream.StreamEvent.ToolComplete>(data)
        assertEquals("read_file", event.name)
        assertEquals("file contents here", event.result)
    }

    @Test
    fun `Reasoning event parses correctly`() {
        val data = """{"text":"Let me think about this...","stream_id":"s1"}"""
        val event = json.decodeFromString<ChatStream.StreamEvent.Reasoning>(data)
        assertEquals("Let me think about this...", event.text)
    }

    @Test
    fun `Done event with null stream_id`() {
        val data = """{"stream_id":null}"""
        val event = json.decodeFromString<ChatStream.StreamEvent.Done>(data)
        assertNull(event.stream_id)
    }

    @Test
    fun `StreamEnd event with stream_id`() {
        val data = """{"stream_id":"abc"}"""
        val event = json.decodeFromString<ChatStream.StreamEvent.StreamEnd>(data)
        assertEquals("abc", event.stream_id)
    }

    @Test
    fun `Error event parses message`() {
        val data = """{"message":"Rate limit exceeded","stream_id":"s1"}"""
        val event = json.decodeFromString<ChatStream.StreamEvent.Error>(data)
        assertEquals("Rate limit exceeded", event.message)
    }

    @Test
    fun `Error event with null message`() {
        val data = """{"message":null,"stream_id":null}"""
        val event = json.decodeFromString<ChatStream.StreamEvent.Error>(data)
        assertNull(event.message)
    }

    @Test
    fun `Title event parses title`() {
        val data = """{"title":"My Chat Session","stream_id":"s1"}"""
        val event = json.decodeFromString<ChatStream.StreamEvent.Title>(data)
        assertEquals("My Chat Session", event.title)
    }

    @Test
    fun `Token event with unknown fields is tolerant`() {
        val data = """{"token":"hi","stream_id":"s1","unknown_field":"ignored"}"""
        val event = json.decodeFromString<ChatStream.StreamEvent.Token>(data)
        assertEquals("hi", event.token)
    }

    @Test
    fun `ChatStartRequest serializes correctly`() {
        val request = ChatStream.ChatStartRequest(
            session_id = "session-123",
            message = "Hello",
            model = "gpt-4",
            provider = "openai",
            reasoning = null,
            workspace = null,
            profile = null,
            files = null,
            explicit_model_pick = true
        )
        val jsonStr = kotlinx.serialization.json.Json.encodeToString(
            ChatStream.ChatStartRequest.serializer(),
            request
        )
        assertTrue(jsonStr.contains("session-123"))
        assertTrue(jsonStr.contains("Hello"))
        assertTrue(jsonStr.contains("gpt-4"))
        assertTrue(jsonStr.contains("openai"))
        assertTrue(jsonStr.contains("explicit_model_pick\":true"))
    }

    @Test
    fun `ChatStartResponse deserializes correctly`() {
        val data = """{"stream_id":"str-456","session_id":"ses-789"}"""
        val response = json.decodeFromString<ChatStream.ChatStartResponse>(data)
        assertEquals("str-456", response.stream_id)
        assertEquals("ses-789", response.session_id)
    }
}
