package dev.hermes.core.auth

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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

class AuthPrefsRepository(private val context: Context) {
    private val masterKeyAlias = androidx.security.crypto.MasterKeys.getOrCreate(
        androidx.security.crypto.MasterKeys.AES256_GCM_SPEC
    )
    private val encryptedPrefs = androidx.security.crypto.EncryptedSharedPreferences.create(
        "hermes_auth_prefs",
        masterKeyAlias,
        context,
        androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
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
}