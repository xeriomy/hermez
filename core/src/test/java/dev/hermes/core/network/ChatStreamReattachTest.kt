package dev.hermes.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.ContentType
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the chat-rewrite additions to [ChatStream]:
 *   - [ChatStream.reattachStream] (CHAT-4 fix)
 *   - [ChatStream.cancelStream] idempotency (CHAT-1 fix)
 *   - [ChatStream.startChat] (unchanged but re-tested for regression)
 *
 * Uses ktor-client-mock's [MockEngine] to stub HTTP responses without
 * a real server. The SSE streaming path ([ChatStream.streamEvents])
 * is not tested here because MockEngine doesn't speak SSE — that path
 * is covered by integration tests against a live hermes-webui server.
 *
 * See: hermez-chat-rewrite.pdf §10.1 (ChatStream Tests).
 */
class ChatStreamReattachTest {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Build a ChatStream whose [ChatStream.baseClient] is backed by a
     * [MockEngine] built from [handler]. The SSE client inside ChatStream
     * is still created with OkHttp, but these tests never open an SSE
     * connection — they only call startChat, reattachStream, and
     * cancelStream, all of which use baseClient.
     *
     * The handler lambda's type is inferred from MockEngine's
     * constructor, so we don't need to import HttpRequestData /
     * HttpResponseData explicitly.
     */
    private fun chatStreamWith(
        handler: suspend MockRequestHandleScope.(io.ktor.client.request.HttpRequestData) -> io.ktor.client.request.HttpResponseData
    ): ChatStream {
        val engine = MockEngine(handler)
        val client = HttpClient(engine) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }
        // ChatStream's secondary constructor accepts a baseClient.
        // We pass our mock-backed client; the default (SharedHttpClient)
        // would throw because we're not logged in.
        return ChatStream(serverUrl = "http://test", baseClient = client)
    }

    /**
     * Helper: respond with a JSON body. Must be an extension on
     * [MockRequestHandleScope] because [respond] is itself an extension
     * on that type — without the receiver, `respond` would be an
     * unresolved reference.
     */
    private fun MockRequestHandleScope.jsonRespond(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK
    ) = respond(
        body,
        status,
        headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
    )

    // ------------------------------------------------------------------
    // reattachStream
    // ------------------------------------------------------------------

    @Test
    fun `reattachStream returns success on 200 with active status`() = runBlocking {
        val stream = chatStreamWith { request ->
            // Verify the request shape: GET /api/chat/stream/status?stream_id=...
            assertEquals("/api/chat/stream/status", request.url.encodedPath)
            assertEquals("str_123", request.url.parameters["stream_id"])
            jsonRespond("""{"stream_id":"str_123","status":"active","session_id":"ses_1"}""")
        }

        val result = stream.reattachStream("str_123")
        assertTrue(result.isSuccess)
        val status = result.getOrNull()!!
        assertEquals("str_123", status.stream_id)
        assertEquals("active", status.status)
        assertEquals("ses_1", status.session_id)
    }

    @Test
    fun `reattachStream returns success with completed status`() = runBlocking {
        val stream = chatStreamWith {
            jsonRespond("""{"stream_id":"str_123","status":"completed","session_id":null}""")
        }

        val result = stream.reattachStream("str_123")
        assertTrue(result.isSuccess)
        assertEquals("completed", result.getOrNull()!!.status)
        assertNull(result.getOrNull()!!.session_id)
    }

    @Test
    fun `reattachStream returns failure on 404`() = runBlocking {
        val stream = chatStreamWith {
            respond("", HttpStatusCode.NotFound)
        }

        val result = stream.reattachStream("str_123")
        assertTrue(result.isFailure)
        val ex = result.exceptionOrNull()
        assertNotNull(ex)
        assertTrue(ex!!.message!!.contains("Reattach failed"))
        assertTrue(ex.message!!.contains("404"))
    }

    @Test
    fun `reattachStream returns failure on 500`() = runBlocking {
        val stream = chatStreamWith {
            respond("", HttpStatusCode.InternalServerError)
        }

        val result = stream.reattachStream("str_123")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("500"))
    }

    @Test
    fun `reattachStream returns failure on network error`() = runBlocking {
        val stream = chatStreamWith {
            throw java.io.IOException("Network unreachable")
        }

        val result = stream.reattachStream("str_123")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is java.io.IOException)
    }

    // ------------------------------------------------------------------
    // cancelStream (idempotent — CHAT-1 fix)
    // ------------------------------------------------------------------

    @Test
    fun `cancelStream succeeds on 200`() = runBlocking {
        val stream = chatStreamWith { request ->
            assertEquals("/api/chat/cancel", request.url.encodedPath)
            assertEquals("str_abc", request.url.parameters["stream_id"])
            respond("", HttpStatusCode.OK)
        }

        val result = stream.cancelStream("str_abc")
        assertTrue(result.isSuccess)
    }

    @Test
    fun `cancelStream succeeds on 204 No Content`() = runBlocking {
        val stream = chatStreamWith {
            respond("", HttpStatusCode.NoContent)
        }

        val result = stream.cancelStream("str_abc")
        assertTrue(result.isSuccess)
    }

    @Test
    fun `cancelStream treats 404 as success (stream already gone)`() = runBlocking {
        // CHAT-1 fix: the user may tap Stop after the stream has
        // already been cleaned up by the server (timeout, normal
        // completion). 404 must not be an error.
        val stream = chatStreamWith {
            respond("", HttpStatusCode.NotFound)
        }

        val result = stream.cancelStream("str_abc")
        assertTrue("404 must be treated as success for idempotency", result.isSuccess)
    }

    @Test
    fun `cancelStream treats 409 as success (stream already completed)`() = runBlocking {
        // CHAT-1 fix: 409 Conflict means the stream is in a state that
        // can't be cancelled (e.g. already completed). Treat as success.
        val stream = chatStreamWith {
            respond("", HttpStatusCode.Conflict)
        }

        val result = stream.cancelStream("str_abc")
        assertTrue("409 must be treated as success for idempotency", result.isSuccess)
    }

    @Test
    fun `cancelStream fails on 401`() = runBlocking {
        val stream = chatStreamWith {
            respond("", HttpStatusCode.Unauthorized)
        }

        val result = stream.cancelStream("str_abc")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("401"))
    }

    @Test
    fun `cancelStream fails on 500`() = runBlocking {
        val stream = chatStreamWith {
            respond("", HttpStatusCode.InternalServerError)
        }

        val result = stream.cancelStream("str_abc")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("500"))
    }

    @Test
    fun `cancelStream fails on network error`() = runBlocking {
        val stream = chatStreamWith {
            throw java.io.IOException("Connection reset")
        }

        val result = stream.cancelStream("str_abc")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is java.io.IOException)
    }

    // ------------------------------------------------------------------
    // startChat (regression test — unchanged by the rewrite)
    // ------------------------------------------------------------------

    @Test
    fun `startChat returns success with stream_id`() = runBlocking {
        val stream = chatStreamWith { request ->
            assertEquals("/api/chat/start", request.url.encodedPath)
            // Note: Content-Type is on the OutgoingContent body, not in
            // request.headers — so we can't easily assert it here. The
            // important thing is that the path is correct and the response
            // is parsed correctly.
            jsonRespond("""{"stream_id":"str_001","session_id":"ses_001"}""")
        }

        val request = ChatStream.ChatStartRequest(
            session_id = "ses_001",
            message = "hello",
            model = null, provider = null, reasoning = null,
            workspace = null, profile = null, files = null,
            explicit_model_pick = false
        )
        val result = stream.startChat(request)
        assertTrue(result.isSuccess)
        assertEquals("str_001", result.getOrNull()!!.stream_id)
        assertEquals("ses_001", result.getOrNull()!!.session_id)
    }

    @Test
    fun `startChat returns failure on 409 Conflict`() = runBlocking {
        val stream = chatStreamWith {
            respond("", HttpStatusCode.Conflict)
        }

        val result = stream.startChat(
            ChatStream.ChatStartRequest(
                session_id = "ses", message = "hi",
                model = null, provider = null, reasoning = null,
                workspace = null, profile = null, files = null
            )
        )
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("409"))
    }

    @Test
    fun `startChat returns failure on 500`() = runBlocking {
        val stream = chatStreamWith {
            respond("", HttpStatusCode.InternalServerError)
        }

        val result = stream.startChat(
            ChatStream.ChatStartRequest(
                session_id = "ses", message = "hi",
                model = null, provider = null, reasoning = null,
                workspace = null, profile = null, files = null
            )
        )
        assertTrue(result.isFailure)
    }

    @Test
    fun `startChat returns failure on network error`() = runBlocking {
        val stream = chatStreamWith {
            throw java.io.IOException("timeout")
        }

        val result = stream.startChat(
            ChatStream.ChatStartRequest(
                session_id = "ses", message = "hi",
                model = null, provider = null, reasoning = null,
                workspace = null, profile = null, files = null
            )
        )
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is java.io.IOException)
    }

    // ------------------------------------------------------------------
    // StreamStatusResponse deserialization
    // ------------------------------------------------------------------

    @Test
    fun `StreamStatusResponse deserializes active status`() {
        val data = """{"stream_id":"s1","status":"active","session_id":"ses"}"""
        val response = json.decodeFromString<ChatStream.StreamStatusResponse>(data)
        assertEquals("s1", response.stream_id)
        assertEquals("active", response.status)
        assertEquals("ses", response.session_id)
    }

    @Test
    fun `StreamStatusResponse deserializes completed with null session_id`() {
        val data = """{"stream_id":"s1","status":"completed","session_id":null}"""
        val response = json.decodeFromString<ChatStream.StreamStatusResponse>(data)
        assertEquals("completed", response.status)
        assertNull(response.session_id)
    }

    @Test
    fun `StreamStatusResponse tolerates unknown fields`() {
        val data = """{"stream_id":"s1","status":"active","session_id":"ses","extra":"ignored"}"""
        val response = json.decodeFromString<ChatStream.StreamStatusResponse>(data)
        assertEquals("active", response.status)
    }
}
