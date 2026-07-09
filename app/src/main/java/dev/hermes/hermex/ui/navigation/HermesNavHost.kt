package dev.hermes.hermex.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import dev.hermes.core.auth.AuthRepository
import dev.hermes.core.data.SessionRepository
import dev.hermes.hermex.ui.chat.ChatScreen
import dev.hermes.hermex.ui.login.LoginScreen
import dev.hermes.hermex.ui.sessions.SessionListScreen

/**
 * Central place for all navigation routes.
 */
object Routes {
    const val LOGIN = "login"
    const val SESSIONS = "sessions"
    const val CHAT = "chat/{sessionId}"
    const val CHAT_ARG = "sessionId"

    fun chat(sessionId: String): String = "chat/$sessionId"
}

/**
 * The root navigation graph. Hosted by [dev.hermes.hermex.ui.HermexApp].
 *
 * [authRepository], [sessionRepository], and [serverUrl] are passed DOWN
 * from HermexApp (where they're scoped to the Activity and authState is
 * observed) so every screen shares the SAME instances and the SAME
 * server URL. This is critical — if screens create their own via
 * viewModel(), they get NavBackStackEntry-scoped instances that don't
 * share state.
 */
@Composable
fun HermesNavHost(
    navController: NavHostController,
    startDestination: String,
    authRepository: AuthRepository,
    sessionRepository: SessionRepository,
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
                authRepository = authRepository,
                sessionRepository = sessionRepository,
                onSessionClick = { sessionId ->
                    navController.navigate(Routes.chat(sessionId))
                }
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
                serverUrl = serverUrl,
                onBack = { navController.popBackStack() }
            )
        }
    }
}
