package dev.hermes.core.network

import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException

/**
 * Map a network/HTTP exception to a short, user-facing message.
 *
 * Network failures on Android come through as a messy mix of Ktor
 * exceptions wrapping Java socket exceptions. This helper inspects
 * the underlying cause chain and returns a friendly message that
 * tells the user *what to do next*:
 *
 *   "Can't find that host. Check the URL."             → UnknownHostException
 *   "Connection refused. Is the server running?"        → ConnectException
 *   "Timed out. Server may be slow or unreachable."      → SocketTimeoutException
 *   "TLS handshake failed. Try https://..."              → SSLHandshakeException
 *   "TLS error. Certificate problem?"                    → SSLException
 *   "Cleartext HTTP blocked by Android network policy."  → cleartext NSP
 *
 * Anything else falls back to the exception's own message, or a
 * generic "Network error" if there is no message at all.
 */
fun friendlyError(e: Throwable): String {
    // Walk the cause chain — Ktor often wraps the real IOException.
    var current: Throwable? = e
    var depth = 0
    while (current != null && depth < 10) {
        val msg = current.message.orEmpty()

        // Cleartext traffic blocked by Android network security policy.
        // Thrown as java.net.UnknownServiceException on some Android versions
        // or as an IllegalStateException on others — match by message text.
        if (msg.contains("CLEARTEXT communication", ignoreCase = true) ||
            msg.contains("cleartext traffic", ignoreCase = true) ||
            msg.contains("not permitted by network security policy", ignoreCase = true)
        ) {
            return "Plain HTTP is blocked on this device. Use https://, or run the server behind a TLS tunnel."
        }

        when (current) {
            is UnknownHostException ->
                return "Can't find that host. Check the URL and your network."
            is ConnectException ->
                return "Connection refused. Is the server running on that address/port?"
            is SocketTimeoutException ->
                return "Timed out. The server took too long to respond."
            is SSLHandshakeException ->
                return "TLS handshake failed. The server's certificate may be invalid or self-signed."
            is SSLException ->
                return "TLS error. The server's HTTPS setup may be broken."
        }

        current = current.cause
        depth++
    }

    // Fall back to the original message, or a generic error.
    val msg = e.message?.trim()
    return if (!msg.isNullOrEmpty()) msg else "Network error."
}
