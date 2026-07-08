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
import dev.hermes.core.data.SessionRepository
import dev.hermes.hermex.ui.navigation.HermesNavHost
import dev.hermes.hermex.ui.navigation.Routes

/**
 * The root composable. Hosts the [HermesNavHost] and observes
 * [AuthRepository.authState] to drive login↔session-list routing.
 *
 * CRITICAL: [authRepository] and [sessionRepository] are created HERE via
 * `viewModel()` (scoped to the Activity) and passed DOWN to all screens.
 * If screens call `viewModel()` themselves, they get a DIFFERENT instance
 * scoped to their NavBackStackEntry — and authState changes in the login
 * screen's instance never reach this observer, so navigation never fires.
 * That was the root cause of "Connect does nothing".
 */
@Composable
fun HermexApp() {
    val authRepository: AuthRepository = viewModel()
    val sessionRepository: SessionRepository = viewModel()
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
                navController.navigate(Routes.LOGIN) {
                    popUpTo(0) { inclusive = true }
                    launchSingleTop = true
                }
            }
            is AuthState.LoggedIn -> {
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
            startDestination = startDestination,
            authRepository = authRepository,
            sessionRepository = sessionRepository
        )
    }
}
