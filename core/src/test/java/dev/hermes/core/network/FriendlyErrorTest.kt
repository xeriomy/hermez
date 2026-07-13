package dev.hermes.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for FriendlyError — the exception-to-user-message mapper.
 *
 * These are pure-logic tests that construct exception chains and verify
 * the mapping. No Android or network dependencies needed.
 */
class FriendlyErrorTest {

    @Test
    fun `UnknownHostException maps to host-not-found message`() {
        val e = java.net.UnknownHostException("hermes.example.com")
        val msg = friendlyError(e)
        assert(msg.contains("find") || msg.contains("host")) { "Expected host-not-found message, got: $msg" }
    }

    @Test
    fun `ConnectException maps to connection-refused message`() {
        val e = java.net.ConnectException("Connection refused")
        val msg = friendlyError(e)
        assert(msg.contains("refused") || msg.contains("running")) { "Expected connection-refused message, got: $msg" }
    }

    @Test
    fun `SocketTimeoutException maps to timeout message`() {
        val e = java.net.SocketTimeoutException("Read timed out")
        val msg = friendlyError(e)
        assert(msg.contains("imed out") || msg.contains("timeout")) { "Expected timeout message, got: $msg" }
    }

    @Test
    fun `SSLHandshakeException maps to TLS message`() {
        val e = javax.net.ssl.SSLHandshakeException("PKIX validation failed")
        val msg = friendlyError(e)
        assert(msg.contains("TLS") || msg.contains("certificate")) { "Expected TLS message, got: $msg" }
    }

    @Test
    fun `cleartext NSC error maps to cleartext message`() {
        val e = RuntimeException("CLEARTEXT communication to localhost not permitted by network security policy")
        val msg = friendlyError(e)
        assert(msg.contains("cleartext") || msg.contains("HTTP") || msg.contains("blocked")) {
            "Expected cleartext message, got: $msg"
        }
    }

    @Test
    fun `unknown exception falls back to its own message`() {
        val e = IllegalStateException("Something weird happened")
        val msg = friendlyError(e)
        assertEquals("Something weird happened", msg)
    }

    @Test
    fun `exception with null message falls back to generic error`() {
        val e = RuntimeException()
        val msg = friendlyError(e)
        assertEquals("Network error.", msg)
    }

    @Test
    fun `walks cause chain to find known exception`() {
        val root = java.net.UnknownHostException("hermes.example.com")
        val wrapper = RuntimeException("Request failed", root)
        val msg = friendlyError(wrapper)
        assert(msg.contains("find") || msg.contains("host")) { "Expected root cause message, got: $msg" }
    }

    @Test
    fun `deep cause chain is traversed`() {
        val root = java.net.ConnectException("Connection refused")
        val mid = RuntimeException("Middleware error", root)
        val top = IllegalStateException("Top-level error", mid)
        val msg = friendlyError(top)
        assert(msg.contains("refused") || msg.contains("running")) { "Expected root cause message, got: $msg" }
    }
}
