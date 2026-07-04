package dev.hermes.core.auth

sealed interface AuthState {
    data class LoggedIn(val serverUrl: String) : AuthState
    object LoggedOut : AuthState
}