package dev.hermes.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.cookies.AcceptAllCookiesStorage
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * A process-wide shared HTTP client holder for the app.
 *
 * Why this exists:
 *
 * Ktor's [HttpClient] holds its own [HttpCookies] storage (cookie jar).
 * If [AuthRepository] and [SessionRepository] each create their own
 * client, the auth cookie set by `POST /api/auth/login` lives only in
 * AuthRepository's client — SessionRepository's client has an empty
 * cookie jar and every API call returns 401.
 *
 * The fix: one shared [HttpClient] for the whole app, holding one
 * cookie jar. All repositories borrow it via [client].
 *
 * The client is keyed by server URL: when the user logs in to a new
 * server, the old client is closed (dropping its cookies) and a new
 * one is created for the new URL.
 *
 * Thread-safe via @Volatile + synchronized.
 */
object SharedHttpClient {

    /**
     * The single shared cookie storage. Lives across client recreations
     * so the auth cookie survives within a session. Exposed so
     * [ChatStream]'s SSE client can share it — the SSE plugin needs its
     * own HttpClient but should use the same cookies.
     *
     * Replaced with a fresh instance on logout (reset) and on URL change
     * (client) to prevent auth cookies from server A being sent to
     * server B. (BUG-10 fix)
     */
    @Volatile
    var cookieStorage: AcceptAllCookiesStorage = AcceptAllCookiesStorage()

    @Volatile
    private var currentUrl: String? = null

    @Volatile
    private var currentClient: HttpClient? = null

    /**
     * The server URL the shared client is currently configured for,
     * or null if no client has been created yet. Read-only.
     */
    val serverUrl: String? get() = currentUrl

    /**
     * Get the shared [HttpClient] for [serverUrl]. If the URL changed
     * since the last call, the old client is closed (dropping its
     * cookies) and a new one is created.
     *
     * @param serverUrl The base URL to point the client at (e.g.
     *   `http://127.0.0.1:8787`). Must include the scheme. Use
     *   [normalizeUrl] if the user may have typed a bare host:port.
     * @return The shared HttpClient, or null if [serverUrl] is null
     *   or blank.
     */
    fun client(serverUrl: String?): HttpClient? {
        if (serverUrl.isNullOrBlank()) return null
        val normalized = serverUrl.trim().trimEnd('/')

        // Fast path: same URL, client already exists
        if (normalized == currentUrl) {
            return currentClient ?: synchronized(this) {
                currentClient ?: createClient(normalized).also {
                    currentClient = it
                    currentUrl = normalized
                }
            }
        }

        // Slow path: URL changed (or first call) — close old client,
        // clear cookies (BUG-10: prevent auth cookies from server A
        // being sent to server B), create new client.
        synchronized(this) {
            currentClient?.close()
            cookieStorage = AcceptAllCookiesStorage()  // BUG-10: fresh cookie jar on URL change
            val newClient = createClient(normalized)
            currentClient = newClient
            currentUrl = normalized
            return newClient
        }
    }

    /**
     * Drop the current client and clear all cookies. Called on logout.
     * Ensures no auth cookies survive across logout/login cycles or
     * server switches. (BUG-10 fix)
     */
    fun reset() {
        synchronized(this) {
            currentClient?.close()
            currentClient = null
            currentUrl = null
            cookieStorage = AcceptAllCookiesStorage()  // BUG-10: fresh cookie jar on logout
        }
    }

    private fun createClient(baseUrl: String): HttpClient {
        return HttpClient(OkHttp) {
            expectSuccess = false

            install(ContentNegotiation) {
                json(
                    Json {
                        ignoreUnknownKeys = true
                        coerceInputValues = true
                    }
                )
            }

            install(HttpCookies) {
                // Share the same cookie storage across all clients, so the
                // auth cookie set by login is visible to every API call.
                storage = cookieStorage
            }

            defaultRequest {
                url(baseUrl)
                header(HttpHeaders.ContentType, ContentType.Application.Json)
                // Upstream treats missing Origin/Referer as non-browser
                // (curl-equivalent). Explicitly omit them to avoid CSRF
                // validation failures.
                headers.remove(HttpHeaders.Origin)
                headers.remove("Referer")
            }
        }
    }

    /**
     * Ensure a URL has an http:// or https:// scheme. If the user
     * typed a bare `host:port` (e.g. `127.0.0.1:8787`), prepend
     * `http://`. Ktor's URL parser throws "Fail to parse url" without
     * a scheme.
     */
    fun normalizeUrl(url: String): String {
        val trimmed = url.trim()
        return when {
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            trimmed.startsWith("//") -> "http:$trimmed"
            else -> "http://$trimmed"
        }
    }
}
