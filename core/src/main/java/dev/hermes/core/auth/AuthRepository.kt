package dev.hermes.core.auth

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import dev.hermes.core.network.ApiEndpoint
import dev.hermes.core.network.HttpClientProvider
import io.ktor.client.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.cookies.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class AuthRepository(private val context: Context) {

    private val client = HttpClientProvider.create("")

    private val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
    private val encryptedPrefs = EncryptedSharedPreferences.create(
        "hermes_auth_prefs",
        masterKeyAlias,
        context,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private const val KEY_SERVER_URL = "server_url"

    fun saveServerUrl(url: String) {
        encryptedPrefs.edit().putString(KEY_SERVER_URL, url).apply()
    }

    fun getServerUrl(): String? {
        return encryptedPrefs.getString(KEY_SERVER_URL, null)
    }

    fun clearServerUrl() {
        encryptedPrefs.edit().remove(KEY_SERVER_URL).apply()
    }

    suspend fun login(serverUrl: String, password: String): Result<AuthState> {
        return try {
            val loginClient = HttpClientProvider.create(serverUrl)
            val response = loginClient.post<LoginResponse>(ApiEndpoint.AuthLogin.path) {
                contentType(ContentType.Application.Json)
                setBody(LoginRequest(password))
            }
            if (response.status.isSuccess()) {
                saveServerUrl(serverUrl)
                Result.success(AuthState.LoggedIn(serverUrl))
            } else {
                Result.failure(Exception("Login failed: ${response.status}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun logout(serverUrl: String): Result<Unit> {
        return try {
            val logoutClient = HttpClientProvider.create(serverUrl)
            logoutClient.post(ApiEndpoint.AuthLogout.path) {
                contentType(ContentType.Application.Json)
            }
            clearServerUrl()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun checkAuthStatus(serverUrl: String): Result<AuthState> {
        return try {
            val statusClient = HttpClientProvider.create(serverUrl)
            val response = statusClient.get<AuthStatusResponse>(ApiEndpoint.AuthStatus.path)
            if (response.status.isSuccess() && response.body()?.authEnabled == false) {
                Result.success(AuthState.LoggedIn(serverUrl))
            } else if (response.status.isSuccess() && response.body()?.authEnabled == true) {
                val savedUrl = getServerUrl()
                if (savedUrl != null) {
                    Result.success(AuthState.LoggedIn(savedUrl))
                } else {
                    Result.success(AuthState.LoggedOut)
                }
            } else {
                Result.success(AuthState.LoggedOut)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    @Serializable
    private data class LoginRequest(val password: String)

    @Serializable
    private data class LoginResponse(val success: Boolean, val message: String?)

    @Serializable
    private data class AuthStatusResponse(val authEnabled: Boolean)
}