package dev.hermes.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for SharedHttpClient utility methods.
 *
 * These tests cover the pure-logic methods (normalizeUrl) that don't
 * require Android instrumentation or a running HTTP server.
 */
class SharedHttpClientTest {

    // --- normalizeUrl (BUG-9 fix) ---

    @Test
    fun `normalizeUrl prepends http to bare host`() {
        assertEquals("http://127.0.0.1:8787", SharedHttpClient.normalizeUrl("127.0.0.1:8787"))
    }

    @Test
    fun `normalizeUrl prepends http to bare hostname`() {
        assertEquals("http://hermes.example.com", SharedHttpClient.normalizeUrl("hermes.example.com"))
    }

    @Test
    fun `normalizeUrl preserves http scheme`() {
        assertEquals("http://localhost:8787", SharedHttpClient.normalizeUrl("http://localhost:8787"))
    }

    @Test
    fun `normalizeUrl preserves https scheme`() {
        assertEquals("https://hermes.example.com", SharedHttpClient.normalizeUrl("https://hermes.example.com"))
    }

    @Test
    fun `normalizeUrl preserves non-http schemes (BUG-9 fix)`() {
        assertEquals("ftp://example.com", SharedHttpClient.normalizeUrl("ftp://example.com"))
        assertEquals("ws://server:8787", SharedHttpClient.normalizeUrl("ws://server:8787"))
        assertEquals("file:///path", SharedHttpClient.normalizeUrl("file:///path"))
    }

    @Test
    fun `normalizeUrl handles protocol-relative URLs`() {
        assertEquals("http://example.com", SharedHttpClient.normalizeUrl("//example.com"))
    }

    @Test
    fun `normalizeUrl trims whitespace`() {
        assertEquals("http://example.com", SharedHttpClient.normalizeUrl("  example.com  "))
    }

    @Test
    fun `normalizeUrl does not double-prepend http`() {
        assertEquals("http://example.com", SharedHttpClient.normalizeUrl("http://example.com"))
        // Should NOT be "http://http://example.com"
        assertFalse(
            "normalizeUrl must not double-prepend http://",
            SharedHttpClient.normalizeUrl("http://example.com").startsWith("http://http://")
        )
    }

    @Test
    fun `normalizeUrl handles schemes with plus and dash`() {
        // RFC 3986: scheme = ALPHA *( ALPHA / DIGIT / "+" / "-" / "." )
        assertEquals("custom+proto://host", SharedHttpClient.normalizeUrl("custom+proto://host"))
        assertEquals("my-proto://host", SharedHttpClient.normalizeUrl("my-proto://host"))
        assertEquals("v1.2://host", SharedHttpClient.normalizeUrl("v1.2://host"))
    }

    @Test
    fun `normalizeUrl does not treat colon without slashes as a scheme`() {
        // "host:8787" has a colon but no "://" — should prepend http://
        assertEquals("http://host:8787", SharedHttpClient.normalizeUrl("host:8787"))
    }

    // --- reset / client lifecycle ---

    @Test
    fun `client returns null for blank URL`() {
        assertEquals(null, SharedHttpClient.client(null))
        assertEquals(null, SharedHttpClient.client(""))
        assertEquals(null, SharedHttpClient.client("   "))
    }
}
