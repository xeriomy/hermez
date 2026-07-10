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
import dev.hermes.core.data.ConfigRepository
import dev.hermes.core.data.SessionRepository
import dev.hermes.hermex.ui.navigation.HermesNavHost
import dev.hermes.hermex.ui.navigation.Routes

@Composable
fun HermexApp() {
    val authRepository: AuthRepository = viewModel()
    val sessionRepository: SessionRepository = viewModel()
    val configRepository: ConfigRepository = viewModel()
    val authState by authRepository.authState.collectAsStateWithLifecycle()
    val navController = rememberNavController()

    val serverUrl = (authState as? AuthState.LoggedIn)?.serverUrl ?: ""

    val startDestination = remember {
        when (authRepository.authState.value) {
            is AuthState.LoggedIn -> Routes.SESSIONS
            AuthState.LoggedOut -> Routes.LOGIN
        }
    }

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
            sessionRepository = sessionRepository,
            configRepository = configRepository,
            serverUrl = serverUrl
        )
    }
}
