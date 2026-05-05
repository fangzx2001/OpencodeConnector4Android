package com.opencode.remote.ui

import androidx.compose.runtime.Composable
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.opencode.remote.ui.connection.ConnectionScreen
import com.opencode.remote.ui.sessions.SessionsScreen
import com.opencode.remote.ui.sessions.SessionsViewModel
import com.opencode.remote.ui.sessions.ProjectSessionsScreen
import com.opencode.remote.ui.chat.ChatScreen
import com.opencode.remote.ui.help.HelpScreen

object Routes {
    const val CONNECTION = "connection"
    const val SESSIONS = "sessions"
    const val PROJECT_SESSIONS = "project/{directory}"
    const val CHAT = "chat/{sessionId}?directory={directory}"
    const val HELP = "help"

    fun chat(sessionId: String, directory: String? = null): String {
        val encodedDir = directory?.let { java.net.URLEncoder.encode(it, "UTF-8") } ?: ""
        return "chat/$sessionId?directory=$encodedDir"
    }
    fun projectSessions(directory: String) = "project/${java.net.URLEncoder.encode(directory, "UTF-8")}"
}

@Composable
fun OConnectorApp() {
    val navController = rememberNavController()
    NavHost(
        navController = navController,
        startDestination = Routes.CONNECTION
    ) {
        composable(Routes.CONNECTION) {
            ConnectionScreen(
                onConnected = {
                    navController.navigate(Routes.SESSIONS) {
                        popUpTo(Routes.CONNECTION) { inclusive = true }
                    }
                },
                onHelpClick = {
                    navController.navigate(Routes.HELP)
                }
            )
        }

        composable(Routes.SESSIONS) {
            SessionsScreen(
                onProjectClick = { directory ->
                    navController.navigate(Routes.projectSessions(directory))
                },
                onDisconnected = {
                    navController.navigate(Routes.CONNECTION) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }

        composable(Routes.PROJECT_SESSIONS) { backStackEntry ->
            val encoded = backStackEntry.arguments?.getString("directory") ?: return@composable
            val directory = java.net.URLDecoder.decode(encoded, "UTF-8")
            // Share the same SessionsViewModel with SessionsScreen so session count updates are visible immediately
            // Use try-catch to handle deep link scenarios where SESSIONS route is not in the back stack
            val sessionsEntry = try {
                navController.getBackStackEntry(Routes.SESSIONS)
            } catch (_: IllegalArgumentException) {
                backStackEntry
            }
            ProjectSessionsScreen(
                directory = directory,
                viewModel = hiltViewModel(sessionsEntry),
                onSessionClick = { sessionId ->
                    navController.navigate(Routes.chat(sessionId, directory))
                },
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.CHAT) { backStackEntry ->
            val sessionId = backStackEntry.arguments?.getString("sessionId") ?: return@composable
            val encodedDir = backStackEntry.arguments?.getString("directory") ?: ""
            val directory = if (encodedDir.isNotBlank()) java.net.URLDecoder.decode(encodedDir, "UTF-8") else null
            ChatScreen(
                sessionId = sessionId,
                directory = directory,
                onBack = { navController.popBackStack() }
            )
        }

        composable(Routes.HELP) {
            HelpScreen(
                onBack = { navController.popBackStack() }
            )
        }
    }
}
