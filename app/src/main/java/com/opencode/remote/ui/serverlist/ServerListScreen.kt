package com.opencode.remote.ui.serverlist

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.opencode.remote.data.api.dto.ServerInfo
import com.opencode.remote.data.download.DownloadHelper
import com.opencode.remote.data.github.GitHubReleaseService
import com.opencode.remote.ui.strings.AppLocale
import com.opencode.remote.ui.update.UpdateDialog
import com.opencode.remote.ui.update.UpdateUiState
import com.opencode.remote.ui.update.UpdateViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerListScreen(
    viewModel: ServerListViewModel = hiltViewModel(),
    onAddServer: () -> Unit,
    onServerSelected: (serverId: String) -> Unit,
    onHelp: () -> Unit,
    onToggleLanguage: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val s = AppLocale.strings

    val updateViewModel: UpdateViewModel = hiltViewModel()
    val updateState by updateViewModel.uiState.collectAsStateWithLifecycle()
    var showUpdateDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current

    // Check for updates on first composition
    LaunchedEffect(Unit) {
        updateViewModel.checkForUpdate()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(s.servers) },
                actions = {
                    // Update button — appears when new version available
                    if (updateState is UpdateUiState.Available) {
                        IconButton(onClick = { showUpdateDialog = true }) {
                            Icon(
                                imageVector = Icons.Default.Download,
                                contentDescription = "Update available",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    IconButton(onClick = onHelp) {
                        @Suppress("DEPRECATION")
                        Icon(Icons.Default.HelpOutline, contentDescription = s.helpLabel)
                    }
                    IconButton(onClick = onToggleLanguage) {
                        Icon(Icons.Default.Translate, contentDescription = "Language")
                    }
                    IconButton(onClick = onAddServer) {
                        Icon(Icons.Default.Add, contentDescription = s.addServer)
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            if (uiState.servers.isEmpty()) {
                EmptyServerList(modifier = Modifier.weight(1f))
            } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    items(uiState.servers, key = { it.id }) { server ->
                        ServerCard(
                            server = server,
                            onClick = { onServerSelected(server.id) },
                            onDelete = { viewModel.deleteServer(server.id) },
                        )
                    }
                }
            }

            // Tips card at the bottom
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = s.tipsTitle,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = s.tipsContent,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = MaterialTheme.typography.bodySmall.lineHeight,
                    )
                }
            }
        }
    }

    // Update dialog
    if (showUpdateDialog && updateState is UpdateUiState.Available) {
        val avail = updateState as UpdateUiState.Available
        UpdateDialog(
            version = avail.version,
            changelog = avail.changelog,
            changelogTitle = s.updateChangelog,
            downloadText = s.updateDownload,
            closeText = s.updateClose,
            noChangelogText = "No changelog provided.",
            onDownload = {
                showUpdateDialog = false
                if (avail.downloadUrl != null) {
                    val proxiedUrl = GitHubReleaseService.proxiedDownloadUrl(avail.downloadUrl)
                    DownloadHelper.downloadApk(
                        context,
                        proxiedUrl,
                        "OConnector-v${avail.version}.apk"
                    )
                    Toast.makeText(context, "Download started", Toast.LENGTH_SHORT).show()
                } else {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(avail.releaseUrl))
                    context.startActivity(intent)
                }
            },
            onDismiss = { showUpdateDialog = false }
        )
    }

    // Show update error as a Toast so the user knows what happened
    var lastShownError by remember { mutableStateOf("") }
    LaunchedEffect(updateState) {
        if (updateState is UpdateUiState.Error) {
            val msg = (updateState as UpdateUiState.Error).message
            if (msg != lastShownError) {
                lastShownError = msg
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            }
        }
    }
}

@Composable
private fun EmptyServerList(modifier: Modifier = Modifier) {
    val s = AppLocale.strings

    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Default.Computer,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.primary,
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = s.noServers,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = s.addFirstServer,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ServerCard(
    server: ServerInfo,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    var showDeleteMenu by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val s = AppLocale.strings

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = { showDeleteMenu = true },
            ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                val displayName = server.name.ifBlank { "${server.host}:${server.port}" }
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (server.name.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "${server.host}:${server.port}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    Box {
        DropdownMenu(
            expanded = showDeleteMenu,
            onDismissRequest = { showDeleteMenu = false },
        ) {
            DropdownMenuItem(
                text = { Text(s.deleteServer, color = MaterialTheme.colorScheme.error) },
                leadingIcon = {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                },
                onClick = {
                    showDeleteMenu = false
                    showDeleteConfirm = true
                },
            )
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(s.deleteServer) },
            text = { Text(s.deleteServerConfirm) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        onDelete()
                    },
                ) {
                    Text(s.delete, color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(s.close)
                }
            },
        )
    }
}
