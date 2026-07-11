package dev.hermes.hermex.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.hermes.core.auth.AuthRepository
import dev.hermes.core.auth.AuthState
import dev.hermes.core.data.ConfigRepository
import dev.hermes.core.data.SessionRepository
import dev.hermes.core.data.WorkspaceRepository
import dev.hermes.hermex.ui.drawer.AppDrawer
import dev.hermes.hermex.ui.navigation.HermesNavHost
import dev.hermes.hermex.ui.navigation.Routes
import kotlinx.coroutines.launch

@Composable
fun HermexApp() {
    val authRepository: AuthRepository = viewModel()
    val sessionRepository: SessionRepository = viewModel()
    val configRepository: ConfigRepository = viewModel()
    val workspaceRepository: WorkspaceRepository = viewModel()
    val authState by authRepository.authState.collectAsStateWithLifecycle()
    val navController = rememberNavController()

    val serverUrl = (authState as? AuthState.LoggedIn)?.serverUrl ?: ""
    val isLoggedIn = authState is AuthState.LoggedIn

    val startDestination = remember {
        when (authRepository.authState.value) {
            is AuthState.LoggedIn -> Routes.SESSIONS
            AuthState.LoggedOut -> Routes.LOGIN
        }
    }

    val drawerState = rememberDrawerState(initialValue = androidx.compose.material3.DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    // Track the current route so the drawer knows which session is open
    // (for highlighting in the recents list)
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val currentSessionId = remember(currentRoute) {
        if (currentRoute?.startsWith("chat/") == true) {
            currentRoute.removePrefix("chat/").replace("/", "")
        } else null
    }

    fun openDrawer() { scope.launch { drawerState.open() } }
    fun closeDrawer() { scope.launch { drawerState.close() } }

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

    // Only show the drawer when logged in
    if (!isLoggedIn) {
        // Login screen — no drawer
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
                workspaceRepository = workspaceRepository,
                serverUrl = serverUrl,
                onOpenDrawer = {}
            )
        }
        return
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            AppDrawer(
                authRepository = authRepository,
                sessionRepository = sessionRepository,
                currentSessionId = currentSessionId,
                onNewChat = {
                    closeDrawer()
                    navController.navigate(Routes.SESSIONS) {
                        popUpTo(Routes.SESSIONS) { inclusive = true }
                        launchSingleTop = true
                    }
                },
                onShowChats = {
                    closeDrawer()
                    navController.navigate(Routes.SESSIONS) {
                        popUpTo(Routes.SESSIONS) { inclusive = true }
                        launchSingleTop = true
                    }
                },
                onShowArchived = {
                    closeDrawer()
                    navController.navigate(Routes.ARCHIVED)
                },
                onShowSettings = {
                    closeDrawer()
                    navController.navigate(Routes.SETTINGS)
                },
                onSessionClick = { sessionId ->
                    closeDrawer()
                    // If this session is already open, just close the drawer
                    // (don't reload the chat)
                    if (currentSessionId == sessionId) return@AppDrawer
                    navController.navigate(Routes.chat(sessionId)) {
                        launchSingleTop = true
                    }
                },
                onLogout = {
                    closeDrawer()
                    authRepository.logout()
                }
            )
        }
    ) {
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
                workspaceRepository = workspaceRepository,
                serverUrl = serverUrl,
                onOpenDrawer = ::openDrawer
            )
        }
    }
}
