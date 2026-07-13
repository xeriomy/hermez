package dev.hermes.core.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for AuthState sealed interface.
 *
 * Pure data-class tests — no Android or network dependencies.
 */
class AuthStateTest {

    @Test
    fun `LoggedIn holds server URL`() {
        val state = AuthState.LoggedIn("http://hermes.example.com")
        assertEquals("http://hermes.example.com", state.serverUrl)
    }

    @Test
    fun `LoggedOut is a data object`() {
        // Should be a singleton — same instance everywhere
        val a: AuthState = AuthState.LoggedOut
        val b: AuthState = AuthState.LoggedOut
        assertTrue(a === b)
    }

    @Test
    fun `LoggedIn equality based on URL`() {
        val a = AuthState.LoggedIn("http://a.com")
        val b = AuthState.LoggedIn("http://a.com")
        val c = AuthState.LoggedIn("http://b.com")
        assertEquals(a, b)
        assertFalse(a == c)
    }

    @Test
    fun `LoggedIn and LoggedOut are different types`() {
        val loggedIn = AuthState.LoggedIn("http://a.com") as AuthState
        val loggedOut = AuthState.LoggedOut as AuthState
        assertFalse(loggedIn == loggedOut)
    }
}

/**
 * Unit tests for ConnectionProbeResult sealed interface.
 */
class ConnectionProbeResultTest {

    @Test
    fun `Ok holds auth flags`() {
        val result = ConnectionProbeResult.Ok(authEnabled = true, passwordAuthEnabled = true)
        assertTrue(result.authEnabled)
        assertTrue(result.passwordAuthEnabled)
    }

    @Test
    fun `Ok with auth disabled`() {
        val result = ConnectionProbeResult.Ok(authEnabled = false, passwordAuthEnabled = false)
        assertFalse(result.authEnabled)
    }

    @Test
    fun `Failed holds message`() {
        val result = ConnectionProbeResult.Failed("Connection refused")
        assertEquals("Connection refused", result.message)
    }
}

/**
 * Unit tests for LoginResult sealed interface.
 */
class LoginResultTest {

    @Test
    fun `Success is a data object singleton`() {
        val a: LoginResult = LoginResult.Success
        val b: LoginResult = LoginResult.Success
        assertTrue(a === b)
    }

    @Test
    fun `Failed holds message`() {
        val result = LoginResult.Failed("Wrong password")
        assertEquals("Wrong password", result.message)
    }
}
