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
 * When `authState` flips to `LoggedOut`, the nav graph pops back to
 * the login screen. When it flips to `LoggedIn`, the graph navigates
 * to the session list (the login screen itself also routes on success
 * — this is the backstop for the cold-start-with-saved-URL case).
 *
 * The NavHost's `startDestination` is chosen once at composition based
 * on the initial auth state, so we don't flash the login screen on
 * cold start when the user is already logged in.
 */
@Composable
fun HermexApp() {
    val authRepository: AuthRepository = viewModel()
    val authState by authRepository.authState.collectAsStateWithLifecycle()
    val navController = rememberNavController()

    // Pick the start destination based on whether we have a saved server URL.
    // We only do this once at first composition — after that, navigation
    // is driven by the LaunchedEffect below.
    val startDestination = remember(authState) {
        when (authState) {
            is AuthState.LoggedIn -> Routes.SESSIONS
            AuthState.LoggedOut -> Routes.LOGIN
        }
    }

    // Drive navigation when auth state changes after composition.
    LaunchedEffect(authState) {
        when (authState) {
            AuthState.LoggedOut -> {
                // Pop everything and show login. Use launchSingleTop so we
                // don't stack multiple login instances.
                navController.navigate(Routes.LOGIN) {
                    popUpTo(0) { inclusive = true }
                    launchSingleTop = true
                }
            }
            is AuthState.LoggedIn -> {
                // If we're currently on login (i.e. user just logged in),
                // route to sessions. The login screen itself also does this
                // via onLoggedIn — this is the backstop for cold-start
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
