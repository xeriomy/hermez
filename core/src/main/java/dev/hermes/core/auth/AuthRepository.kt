package dev.hermes.core.auth

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dev.hermes.core.auth.AuthState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AuthRepository(context: Context) : ViewModel() {

    private val _authState = MutableStateFlow<AuthState>(AuthState.LoggedOut)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private val prefsRepository = AuthPrefsRepository(context)

    init {
        val savedUrl = prefsRepository.getServerUrl()
        if (savedUrl != null) {
            _authState.value = AuthState.LoggedIn(savedUrl)
        }
    }

    fun setLoggedIn(serverUrl: String) {
        prefsRepository.saveServerUrl(serverUrl)
        _authState.value = AuthState.LoggedIn(serverUrl)
    }

    fun logout() {
        prefsRepository.clearServerUrl()
        _authState.value = AuthState.LoggedOut
    }
}

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
