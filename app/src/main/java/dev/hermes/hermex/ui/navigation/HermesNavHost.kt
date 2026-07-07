package dev.hermes.hermex.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import androidx.navigation.NavType
import dev.hermes.hermex.ui.chat.ChatScreen
import dev.hermes.hermex.ui.login.LoginScreen
import dev.hermes.hermex.ui.sessions.SessionListScreen

/**
 * Central place for all navigation routes. Keeping them as constants
 * avoids typo bugs in `navigate()` calls and lets screens reference
 * each other without circular imports.
 *
 * The route strings are also the paths used in the NavHost's `composable()`
 * registrations below. Argument placeholders use the `{arg}` syntax.
 */
object Routes {
    const val LOGIN = "login"

    const val SESSIONS = "sessions"

    /**
     * Chat route with a `sessionId` path argument. Navigate with
     * `navController.navigate("chat/${sessionId}")`.
     */
    const val CHAT = "chat/{sessionId}"
    const val CHAT_ARG = "sessionId"

    /** Build a chat route string for the given session ID. */
    fun chat(sessionId: String): String = "chat/$sessionId"
}

/**
 * The root navigation graph. Hosted by [dev.hermes.hermex.ui.HermexApp].
 *
 * Three top-level destinations:
 *  - `login`   — shown when [AuthRepository.authState] is `LoggedOut`
 *  - `sessions` — shown when `LoggedIn`; the session list
 *  - `chat/{sessionId}` — pushed from the session list when the user
 *    taps a session
 *
 * The login→sessions transition is driven by observing `authState` in
 * `HermexApp` (which calls `navController.navigate` and `popBackStack`
 * as appropriate). Sessions→chat is driven by the session list's
 * `onSessionClick` callback.
 */
@Composable
fun HermesNavHost(
    navController: NavHostController,
    startDestination: String
) {
    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        composable(Routes.LOGIN) {
            LoginScreen(
                onLoggedIn = {
                    // Pop the login screen off the back stack so the user
                    // can't navigate back to it via the system back button
                    // after logging in.
                    navController.navigate(Routes.SESSIONS) {
                        popUpTo(Routes.LOGIN) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            )
        }

        composable(Routes.SESSIONS) {
            SessionListScreen(
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
                onBack = { navController.popBackStack() }
            )
        }
    }
}
