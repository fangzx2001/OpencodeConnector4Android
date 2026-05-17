package com.opencode.remote.ui

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.opencode.remote.ui.connection.ConnectionMode
import com.opencode.remote.ui.connection.ConnectionScreen
import com.opencode.remote.ui.serverlist.ServerListScreen
import com.opencode.remote.ui.serverlist.ServerListViewModel
import com.opencode.remote.ui.sessions.SessionsScreen
import com.opencode.remote.ui.sessions.ProjectSessionsScreen
import com.opencode.remote.ui.chat.ChatScreen
import com.opencode.remote.ui.help.HelpScreen
import com.opencode.remote.ui.strings.AppLocale
import com.opencode.remote.ui.update.UpdateViewModel

object Routes {
    const val SERVER_LIST = "serverList"
    const val ADD_SERVER = "addServer"
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
fun OConnectorApp(initialIntent: Intent? = null) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val pendingDeepLink = remember { mutableStateOf<Pair<String, String?>?>(null) }

    // Handle notification deep link from initial intent or new intent
    LaunchedEffect(initialIntent) {
        val intent = (context as? MainActivity)?.intent ?: initialIntent
        intent?.let { navigateFromIntent(it, navController, pendingDeepLink) }
    }

    NavHost(
        navController = navController,
        startDestination = Routes.SERVER_LIST
    ) {
        // === Server List (home) ===
        composable(Routes.SERVER_LIST) {
            val viewModel: ServerListViewModel = hiltViewModel()
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()

            // Auto-navigate when a server connects (handles both normal auto-connect and deep-link)
            LaunchedEffect(uiState.connectedServerId) {
                if (uiState.connectedServerId != null) {
                    val deepLink = pendingDeepLink.value
                    if (deepLink != null) {
                        pendingDeepLink.value = null
                        navController.navigate(Routes.chat(deepLink.first, deepLink.second)) {
                            popUpTo(Routes.SERVER_LIST) { inclusive = true }
                        }
                    } else {
                        navController.navigate(Routes.SESSIONS) {
                            popUpTo(Routes.SERVER_LIST) { inclusive = true }
                        }
                    }
                }
            }

            ServerListScreen(
                viewModel = viewModel,
                onAddServer = { navController.navigate(Routes.ADD_SERVER) },
                onServerSelected = { serverId -> viewModel.connectToServer(serverId) },
                onHelp = { navController.navigate(Routes.HELP) },
                onToggleLanguage = {
                    val newLang = if (AppLocale.language == "en") "zh" else "en"
                    AppLocale.language = newLang
                },
            )
        }

        // === Add Server ===
        composable(Routes.ADD_SERVER) {
            ConnectionScreen(
                mode = ConnectionMode.ADD_SERVER,
                onConnected = {
                    navController.navigate(Routes.SESSIONS) {
                        popUpTo(Routes.SERVER_LIST) { inclusive = true }
                    }
                },
                onBack = { navController.popBackStack() },
            )
        }

        // === Connection (legacy, kept for backward compat) ===
        composable(Routes.CONNECTION) {
            ConnectionScreen(
                onConnected = {
                    navController.navigate(Routes.SESSIONS) {
                        popUpTo(Routes.CONNECTION) { inclusive = true }
                    }
                },
            )
        }

        // === Sessions (updated: disconnect goes to SERVER_LIST) ===
        composable(Routes.SESSIONS) {
            SessionsScreen(
                onProjectClick = { directory ->
                    navController.navigate(Routes.projectSessions(directory))
                },
                onDisconnected = {
                    navController.navigate(Routes.SERVER_LIST) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }

        // === Project Sessions ===
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

        // === Chat ===
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

        // === Help ===
        composable(Routes.HELP) {
            val updateVm: UpdateViewModel = hiltViewModel()
            HelpScreen(
                onBack = { navController.popBackStack() },
                updateViewModel = updateVm,
            )
        }
    }
}

private fun navigateFromIntent(intent: Intent, navController: NavController, pendingDeepLink: MutableState<Pair<String, String?>?>) {
    val sessionId = intent.getStringExtra("sessionId") ?: return
    val directory = intent.getStringExtra("directory") ?: return
    // Only navigate if we're not already on the chat screen
    if (navController.currentDestination?.route?.startsWith("chat") == true) return

    // Store deep-link data for the ServerListScreen to consume after auto-connect
    pendingDeepLink.value = Pair(sessionId, directory)

    // Navigate to server list — the ViewModel auto-connects to the last server,
    // and the LaunchedEffect redirects to chat with the deep-link data
    navController.navigate(Routes.SERVER_LIST) {
        popUpTo(0) { inclusive = true }
    }
}
