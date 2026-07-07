package dev.hermes.hermex.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.rememberNavController
import dev.hermes.core.auth.AuthRepository
import dev.hermes.core.auth.AuthState
import dev.hermes.hermex.ui.navigation.HermesNavHost
import dev.hermes.hermex.ui.navigation.Routes

/**
 * The root composable. Hosts the [HermesNavHost] and observes
 * [AuthRepository.authState] to drive login↔session-list routing.
 *
 * The NavHost's `startDestination` is chosen ONCE at first composition
 * based on the initial auth state — so we don't flash the login screen
 * on cold start when the user is already logged in. After that, all
 * navigation is driven by the [LaunchedEffect] below, which fires
 * whenever `authState` changes.
 *
 * Why `remember { }` (no key) for startDestination: if we keyed it on
 * `authState`, the entire NavHost would be recreated on every auth
 * change (including logout), discarding the back stack and causing the
 * "logout hangs" bug. Instead, we compute it once and let the
 * LaunchedEffect handle navigation imperatively.
 */
@Composable
fun HermexApp() {
    val authRepository: AuthRepository = viewModel()
    val authState by authRepository.authState.collectAsStateWithLifecycle()
    val navController = rememberNavController()

    // Compute start destination ONCE. Subsequent auth changes are handled
    // by the LaunchedEffect below — we do NOT want to recreate the NavHost.
    val startDestination = remember {
        when (authRepository.authState.value) {
            is AuthState.LoggedIn -> Routes.SESSIONS
            AuthState.LoggedOut -> Routes.LOGIN
        }
    }

    // Drive navigation when auth state changes.
    LaunchedEffect(authState) {
        when (authState) {
            AuthState.LoggedOut -> {
                // Pop the ENTIRE back stack (including the current session
                // list) and show login as the new root. Without
                // popUpTo(graph.findStartDestination().id) { inclusive = true },
                // the session list stays on the stack and the user can
                // navigate back to it after logging out.
                navController.navigate(Routes.LOGIN) {
                    popUpTo(navController.graph.startDestinationId) {
                        inclusive = true
                    }
                    launchSingleTop = true
                }
            }
            is AuthState.LoggedIn -> {
                // Only navigate if we're currently on the login screen.
                // The login screen's onLoggedIn callback also navigates
                // to sessions — this is the backstop for cold-start
                // restoration where authState was already LoggedIn.
                val current = navController.currentDestination?.route
                if (current == Routes.LOGIN) {
                    navController.navigate(Routes.SESSIONS) {
                        popUpTo(Routes.LOGIN) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            }
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        HermesNavHost(
            navController = navController,
            startDestination = startDestination
        )
    }
}
