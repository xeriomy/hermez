package dev.hermes.core.auth

import android.app.Application
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dev.hermes.core.network.ApiEndpoint
import dev.hermes.core.network.SharedHttpClient
import dev.hermes.core.network.friendlyError
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
 * held by [SharedHttpClient]'s single process-wide [HttpClient] — so
 * every other repository (SessionRepository, ChatStream) shares the
 * same cookie jar automatically.
 *
 * ARCH-1 fix: no longer extends AndroidViewModel. Plain class,
 * held as a singleton by [dev.hermes.core.di.ServiceLocator].
 */
class AuthRepository(app: Application) {

    private val context = app.applicationContext
    private val _authState = MutableStateFlow<AuthState>(AuthState.LoggedOut)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    init {
        AuthPrefsRepository.init(context)
        // On cold start, if we have a saved server URL, jump straight to
        // LoggedIn. We DON'T restore the auth cookie (cookies don't
        // survive app process death in Ktor's in-memory storage), so the
        // next API call will 401 and the UI can prompt for re-login.
        // The SessionRepository handles that 401 gracefully.
        val savedUrl = AuthPrefsRepository.getServerUrl()
        if (savedUrl != null) {
            _authState.value = AuthState.LoggedIn(savedUrl)
        }
    }

    /**
     * Probe the server at [serverUrl] to confirm it's reachable and
     * determine whether password auth is required. Does NOT log in.
     *
     * Calls `GET /health` (must return 2xx) then `GET /api/auth/status`
     * (returns `{auth_enabled, password_auth_enabled}`).
     *
     * Uses [SharedHttpClient] so the probe shares the same cookie jar
     * as subsequent login + API calls.
     */
    suspend fun testConnection(serverUrl: String): ConnectionProbeResult {
        val normalized = SharedHttpClient.normalizeUrl(serverUrl)
        val client = SharedHttpClient.client(normalized)
            ?: return ConnectionProbeResult.Failed("No server URL provided")
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
            ConnectionProbeResult.Failed(friendlyError(e))
        }
    }

    /**
     * Log in to the server at [serverUrl] with [password]. On success,
     * persists the server URL, transitions to [AuthState.LoggedIn], and
     * leaves the auth cookie in [SharedHttpClient]'s cookie jar so
     * every other repository inherits it.
     *
     * If the server reports `auth_enabled = false`, [password] is
     * ignored and login succeeds without calling `/api/auth/login`.
     */
    suspend fun login(serverUrl: String, password: String): LoginResult {
        val normalized = SharedHttpClient.normalizeUrl(serverUrl)
        val probe = testConnection(normalized)
        when (probe) {
            is ConnectionProbeResult.Failed -> return LoginResult.Failed(probe.message)
            is ConnectionProbeResult.Ok -> {
                val needsPassword = probe.authEnabled && probe.passwordAuthEnabled
                if (needsPassword && password.isBlank()) {
                    return LoginResult.Failed("Password is required")
                }

                if (needsPassword) {
                    // SharedHttpClient.client(normalized) returns the same
                    // client that testConnection just used — so any cookies
                    // the server set during the probe are already in the jar.
                    val client = SharedHttpClient.client(normalized)
                        ?: return LoginResult.Failed("No server URL provided")
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
                        return LoginResult.Failed(friendlyError(e))
                    }
                }
                // Either no auth required, or login succeeded — persist &
                // transition. The auth cookie is now in SharedHttpClient's
                // cookie jar, shared with every other repository.
                AuthPrefsRepository.saveServerUrl(normalized)
                _authState.value = AuthState.LoggedIn(normalized)
                return LoginResult.Success
            }
        }
    }

    /**
     * Log out: clear persisted URL, drop the shared HTTP client so
     * cookies are gone, and transition to LoggedOut.
     *
     * We don't call `POST /api/auth/logout` — the cookie lives in our
     * HttpClient's [HttpCookies] storage, not in the server's session
     * table, so dropping the client is sufficient. The server's auth
     * cookie has a 24h TTL per upstream and will expire on its own.
     */
    fun logout() {
        AuthPrefsRepository.clearServerUrl()
        SharedHttpClient.reset()
        _authState.value = AuthState.LoggedOut
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
 *
 * The encrypted prefs are lazily initialized on first access (not in
 * the constructor) because MasterKey + EncryptedSharedPreferences
 * creation involves Android Keystore operations that can take 100-500ms
 * on first launch. Doing it lazily keeps the Activity startup fast.
 *
 * ARCH-4 fix: made a singleton object so all repositories share one
 * instance (one MasterKey, one EncryptedSharedPreferences) instead
 * of each creating its own. Call AuthPrefsRepository.init(context)
 * once from Application onCreate (or from the first repository's init).
 */
object AuthPrefsRepository {

    private lateinit var appContext: android.content.Context

    fun init(context: android.content.Context) {
        if (!::appContext.isInitialized) {
            appContext = context.applicationContext
        }
    }

    private val encryptedPrefs by lazy {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            "hermes_auth_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun saveServerUrl(url: String) {
        encryptedPrefs.edit().putString(KEY_SERVER_URL, url).apply()
    }

    fun getServerUrl(): String? {
        return encryptedPrefs.getString(KEY_SERVER_URL, null)
    }

    fun clearServerUrl() {
        encryptedPrefs.edit().remove(KEY_SERVER_URL).apply()
    }

    private const val KEY_SERVER_URL = "server_url"
}
