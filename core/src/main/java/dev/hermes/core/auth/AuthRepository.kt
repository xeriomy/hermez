package dev.hermes.core.auth

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dev.hermes.core.network.ApiEndpoint
import dev.hermes.core.network.HttpClientProvider
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable

/**
 * Holds the auth state of the app: either logged out, or logged in to a
 * specific server URL. Observed by [dev.hermes.hermex.ui.HermexApp] to
 * route between the login screen and the main session list.
 */
sealed interface AuthState {
    data object LoggedOut : AuthState
    data class LoggedIn(val serverUrl: String) : AuthState
}

/**
 * Result of a connection probe. Used by the login screen to show
 * "Connection ok" or surface a specific error.
 */
sealed interface ConnectionProbeResult {
    /** Server reachable; password may or may not be required. */
    data class Ok(
        val authEnabled: Boolean,
        val passwordAuthEnabled: Boolean
    ) : ConnectionProbeResult

    /** Server unreachable, returned a non-2xx, or response was malformed. */
    data class Failed(val message: String) : ConnectionProbeResult
}

/**
 * Result of a login attempt.
 */
sealed interface LoginResult {
    data object Success : LoginResult
    data class Failed(val message: String) : LoginResult
}

/**
 * Repository for the user's auth state. Persists the server URL in
 * EncryptedSharedPreferences (Keystore-backed) and exposes the current
 * [AuthState] as a [StateFlow] so Compose can react to login/logout.
 *
 * Tests connection via `GET /health` + `GET /api/auth/status`, then logs
 * in via `POST /api/auth/login` with the password. The auth cookie is
 * held by the Ktor [HttpClient]'s [HttpCookies] plugin — we don't persist
 * it ourselves.
 *
 * Extends [ViewModel] so it survives configuration changes and can be
 * obtained via `viewModel()` in Compose.
 */
class AuthRepository(context: Context) : ViewModel() {

    private val _authState = MutableStateFlow<AuthState>(AuthState.LoggedOut)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private val prefsRepository = AuthPrefsRepository(context)

    /**
     * Lazily-created HTTP client for the currently-configured server URL.
     * Recreated when a new URL is used. Held as a private var so callers
     * (ChatStream, SessionRepository) can share one client and one cookie jar.
     */
    @Volatile
    private var httpClient: HttpClient? = null

    /**
     * The server URL the [httpClient] is currently configured for, or null
     * if no client has been created yet.
     */
    @Volatile
    private var currentServerUrl: String? = null

    init {
        // On cold start, if we have a saved server URL, jump straight to
        // LoggedIn — the user is already authenticated at the cookie level
        // (or will be prompted to re-enter password on first API call).
        // We don't restore the cookie itself, so the next request may 401
        // and the UI can route back to login.
        val savedUrl = prefsRepository.getServerUrl()
        if (savedUrl != null) {
            _authState.value = AuthState.LoggedIn(savedUrl)
            currentServerUrl = savedUrl
        }
    }

    /**
     * Probe the server at [serverUrl] to confirm it's reachable and
     * determine whether password auth is required. Does NOT log in.
     *
     * Calls `GET /health` (must return 2xx) then `GET /api/auth/status`
     * (returns `{auth_enabled, password_auth_enabled}`).
     */
    suspend fun testConnection(serverUrl: String): ConnectionProbeResult {
        val client = clientFor(serverUrl)
        return try {
            val health: HttpResponse = client.get(ApiEndpoint.Health.path)
            if (!health.status.isSuccess()) {
                return ConnectionProbeResult.Failed("Server returned ${health.status}")
            }

            val status: HttpResponse = client.get(ApiEndpoint.AuthStatus.path)
            if (!status.status.isSuccess()) {
                // Auth status endpoint missing — assume password required
                // (the safest fallback).
                return ConnectionProbeResult.Ok(authEnabled = true, passwordAuthEnabled = true)
            }

            val body: AuthStatusResponse = status.body()
            ConnectionProbeResult.Ok(
                authEnabled = body.auth_enabled ?: true,
                passwordAuthEnabled = body.password_auth_enabled ?: true
            )
        } catch (e: Exception) {
            ConnectionProbeResult.Failed(e.message ?: "Connection failed")
        }
    }

    /**
     * Log in to the server at [serverUrl] with [password]. On success,
     * persists the server URL, transitions to [AuthState.LoggedIn], and
     * makes the new HTTP client (with fresh auth cookie) available to
     * other repositories.
     *
     * If the server reports `auth_enabled = false`, [password] is ignored
     * and login succeeds without calling `/api/auth/login`.
     */
    suspend fun login(serverUrl: String, password: String): LoginResult {
        val probe = testConnection(serverUrl)
        when (probe) {
            is ConnectionProbeResult.Failed -> return LoginResult.Failed(probe.message)
            is ConnectionProbeResult.Ok -> {
                val needsPassword = probe.authEnabled && probe.passwordAuthEnabled
                if (needsPassword && password.isBlank()) {
                    return LoginResult.Failed("Password is required")
                }

                if (needsPassword) {
                    val client = clientFor(serverUrl)
                    try {
                        val response: HttpResponse = client.post(ApiEndpoint.AuthLogin.path) {
                            contentType(ContentType.Application.Json)
                            setBody(LoginRequest(password))
                        }
                        if (!response.status.isSuccess()) {
                            return LoginResult.Failed(
                                when (response.status.value) {
                                    401 -> "Wrong password"
                                    403 -> "Forbidden — check server URL"
                                    in 500..599 -> "Server error (${response.status})"
                                    else -> "Login failed (${response.status})"
                                }
                            )
                        }
                    } catch (e: Exception) {
                        return LoginResult.Failed(e.message ?: "Login request failed")
                    }
                }
                // Either no auth required, or login succeeded — persist & transition.
                prefsRepository.saveServerUrl(serverUrl)
                _authState.value = AuthState.LoggedIn(serverUrl)
                return LoginResult.Success
            }
        }
    }

    /**
     * Log out: clear persisted URL, drop the HTTP client so cookies are
     * gone, and transition to LoggedOut.
     *
     * We don't call `POST /api/auth/logout` — the cookie lives in our
     * HttpClient's [HttpCookies] storage, not in the server's session
     * table, so dropping the client is sufficient. The server's auth
     * cookie has a 24h TTL per upstream and will expire on its own.
     */
    fun logout() {
        prefsRepository.clearServerUrl()
        httpClient?.close()
        httpClient = null
        currentServerUrl = null
        _authState.value = AuthState.LoggedOut
    }

    /**
     * Get or create an HTTP client pointed at [serverUrl]. If the URL
     * changed since the last call, the old client is closed and a new
     * one is created (so the cookie jar resets).
     */
    private fun clientFor(serverUrl: String): HttpClient {
        val normalized = serverUrl.trimEnd('/')
        val current = httpClient
        if (current != null && currentServerUrl == normalized) {
            return current
        }
        current?.close()
        val newClient = HttpClientProvider.create(normalized)
        httpClient = newClient
        currentServerUrl = normalized
        return newClient
    }

    @Serializable
    private data class AuthStatusResponse(
        val auth_enabled: Boolean? = null,
        val password_auth_enabled: Boolean? = null
    )

    @Serializable
    private data class LoginRequest(val password: String)
}

/**
 * EncryptedSharedPreferences-backed storage for the user's server URL.
 * The URL is the only thing we persist — auth cookies live in the
 * HttpClient's cookie jar and are recreated on each login.
 */
class AuthPrefsRepository(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val encryptedPrefs = EncryptedSharedPreferences.create(
        context,
        "hermes_auth_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun saveServerUrl(url: String) {
        encryptedPrefs.edit().putString(KEY_SERVER_URL, url).apply()
    }

    fun getServerUrl(): String? {
        return encryptedPrefs.getString(KEY_SERVER_URL, null)
    }

    fun clearServerUrl() {
        encryptedPrefs.edit().remove(KEY_SERVER_URL).apply()
    }

    companion object {
        private const val KEY_SERVER_URL = "server_url"
    }
}
