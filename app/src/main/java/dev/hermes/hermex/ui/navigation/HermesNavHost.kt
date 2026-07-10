package dev.hermes.hermex.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import dev.hermes.core.auth.AuthRepository
import dev.hermes.core.data.ConfigRepository
import dev.hermes.core.data.SessionRepository
import dev.hermes.hermex.ui.chat.ChatScreen
import dev.hermes.hermex.ui.login.LoginScreen
import dev.hermes.hermex.ui.sessions.ArchivedSessionsScreen
import dev.hermes.hermex.ui.sessions.SessionListScreen
import dev.hermes.hermex.ui.settings.SettingsScreen

/**
 * Central place for all navigation routes.
 */
object Routes {
    const val LOGIN = "login"
    const val SESSIONS = "sessions"
    const val ARCHIVED = "archived"
    const val SETTINGS = "settings"
    const val CHAT = "chat/{sessionId}"
    const val CHAT_ARG = "sessionId"

    fun chat(sessionId: String): String = "chat/$sessionId"
}

/**
 * The root navigation graph. Hosted by [dev.hermes.hermex.ui.HermexApp].
 */
@Composable
fun HermesNavHost(
    navController: NavHostController,
    startDestination: String,
    authRepository: AuthRepository,
    sessionRepository: SessionRepository,
    configRepository: ConfigRepository,
    serverUrl: String
) {
    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        composable(Routes.LOGIN) {
            LoginScreen(authRepository = authRepository)
        }

        composable(Routes.SESSIONS) {
            SessionListScreen(
                sessionRepository = sessionRepository,
                onSessionClick = { sessionId ->
                    navController.navigate(Routes.chat(sessionId))
                },
                onShowArchived = {
                    navController.navigate(Routes.ARCHIVED)
                },
                onShowSettings = {
                    navController.navigate(Routes.SETTINGS)
                }
            )
        }

        composable(Routes.ARCHIVED) {
            ArchivedSessionsScreen(
                sessionRepository = sessionRepository,
                onBack = { navController.popBackStack() },
                onSessionClick = { sessionId ->
                    navController.navigate(Routes.chat(sessionId))
                }
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                authRepository = authRepository,
                sessionRepository = sessionRepository,
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Routes.CHAT,
            arguments = listOf(
                navArgument(Routes.CHAT_ARG) { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val sessionId = backStackEntry.arguments?.getString(Routes.CHAT_ARG).orEmpty()
            ChatScreen(
                sessionId = sessionId,
                sessionRepository = sessionRepository,
                configRepository = configRepository,
                serverUrl = serverUrl,
                onBack = { navController.popBackStack() }
            )
        }
    }
}
