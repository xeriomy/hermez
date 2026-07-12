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
import dev.hermes.core.data.WorkspaceRepository
import dev.hermes.hermex.ui.chat.ChatScreen
import dev.hermes.hermex.ui.login.LoginScreen
import dev.hermes.hermex.ui.sessions.ArchivedSessionsScreen
import dev.hermes.hermex.ui.sessions.SessionListScreen
import dev.hermes.hermex.ui.settings.SettingsScreen
import dev.hermes.hermex.ui.workspace.FileBrowserScreen
import dev.hermes.hermex.ui.workspace.FilePreviewScreen

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
    const val FILES = "files/{sessionId}"
    const val FILES_ARG = "sessionId"
    const val FILE_PREVIEW = "file/{sessionId}/{filePath}"
    const val FILE_PREVIEW_SESSION_ARG = "sessionId"
    const val FILE_PREVIEW_PATH_ARG = "filePath"

    fun chat(sessionId: String): String = "chat/$sessionId"
    fun files(sessionId: String): String = "files/$sessionId"
    fun filePreview(sessionId: String, filePath: String): String =
        "file/$sessionId/${android.net.Uri.encode(filePath)}"  // BUG-8 fix: proper URL encoding
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
    workspaceRepository: WorkspaceRepository,
    serverUrl: String,
    onOpenDrawer: () -> Unit
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
                },
                onOpenDrawer = onOpenDrawer
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
                onBack = { navController.popBackStack() },
                onShowFiles = { navController.navigate(Routes.files(sessionId)) },
                onOpenDrawer = onOpenDrawer
            )
        }

        composable(
            route = Routes.FILES,
            arguments = listOf(
                navArgument(Routes.FILES_ARG) { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val sessionId = backStackEntry.arguments?.getString(Routes.FILES_ARG).orEmpty()
            FileBrowserScreen(
                sessionId = sessionId,
                workspaceRepository = workspaceRepository,
                onBack = { navController.popBackStack() },
                onFileOpen = { filePath ->
                    navController.navigate(Routes.filePreview(sessionId, filePath))
                }
            )
        }

        composable(
            route = Routes.FILE_PREVIEW,
            arguments = listOf(
                navArgument(Routes.FILE_PREVIEW_SESSION_ARG) { type = NavType.StringType },
                navArgument(Routes.FILE_PREVIEW_PATH_ARG) { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val sessionId = backStackEntry.arguments?.getString(Routes.FILE_PREVIEW_SESSION_ARG).orEmpty()
            val encodedPath = backStackEntry.arguments?.getString(Routes.FILE_PREVIEW_PATH_ARG).orEmpty()
            val filePath = android.net.Uri.decode(encodedPath)  // BUG-8 fix: proper URL decoding
            FilePreviewScreen(
                sessionId = sessionId,
                filePath = filePath,
                workspaceRepository = workspaceRepository,
                onBack = { navController.popBackStack() }
            )
        }
    }
}
