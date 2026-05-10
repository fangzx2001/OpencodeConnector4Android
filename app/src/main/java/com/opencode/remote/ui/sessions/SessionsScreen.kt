package com.opencode.remote.ui.sessions

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
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
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.opencode.remote.data.api.dto.SessionInfo
import com.opencode.remote.ui.components.ErrorSnackbar
import com.opencode.remote.ui.strings.AppLocale
// ─── Level 1: Projects List ──────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsScreen(
    onProjectClick: (String) -> Unit,
    onDisconnected: () -> Unit,
    viewModel: SessionsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val s = AppLocale.strings

    // Auto-refresh when navigating back to this screen
    LifecycleResumeEffect(Unit) {
        viewModel.loadSessions()
        viewModel.loadProjectName()
        onPauseOrDispose { /* no-op */ }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(s.projects)
                        uiState.projectName?.let { name ->
                            Text(
                                text = "${s.serverPath}: $name",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.toggleHideChildSessions() }) {
                        Icon(
                            imageVector = if (uiState.hideChildSessions) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (uiState.hideChildSessions) s.showChildSessions else s.hideChildSessions,
                        )
                    }
                    IconButton(onClick = { viewModel.toggleDarkMode() }) {
                        Icon(
                            if (AppLocale.darkMode) Icons.Default.LightMode else Icons.Default.DarkMode,
                            contentDescription = if (AppLocale.darkMode) "Light mode" else "Dark mode",
                        )
                    }
                    IconButton(onClick = { viewModel.loadSessions() }) {
                        Icon(Icons.Default.Refresh, contentDescription = s.refresh)
                    }
                    IconButton(onClick = { viewModel.logout(onDisconnected) }) {
                        Icon(Icons.Default.PowerSettingsNew, contentDescription = s.disconnect)
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                uiState.isLoading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                    )
                }

                uiState.sessions.isEmpty() -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            imageVector = Icons.Default.FolderOpen,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = s.noProjects,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                else -> {
                    val grouped = remember(uiState.sessions) {
                        uiState.sessions
                            .groupBy { it.directory ?: "unknown" }
                            .mapValues { (_, sessions) ->
                                sessions.sortedByDescending { it.time?.updated ?: it.time?.created ?: 0L }
                            }
                            .toList()
                            .sortedByDescending { (_, sessions) ->
                                sessions.maxOfOrNull { it.time?.updated ?: it.time?.created ?: 0L } ?: 0L
                            }
                    }

                    LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(
                            items = grouped,
                            key = { (dir, _) -> dir }
                        ) { (directory, sessions) ->
                            ProjectCard(
                                directory = directory,
                                sessionCount = sessions.size,
                                onClick = { onProjectClick(directory) },
                            )
                        }
                    }
                }
            }

            ErrorSnackbar(
                error = uiState.error,
                onDismiss = viewModel::clearError,
            )
        }
    }
}

@Composable
private fun ProjectCard(
    directory: String,
    sessionCount: Int,
    onClick: () -> Unit,
) {
    val s = AppLocale.strings
    val folderName = directory.replace('\\', '/').substringAfterLast('/')

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(36.dp),
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = folderName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = directory,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "$sessionCount ${s.sessions}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ─── Level 2: Sessions within a Project ──────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectSessionsScreen(
    directory: String,
    onSessionClick: (String) -> Unit,
    onBack: () -> Unit,
    viewModel: SessionsViewModel,  // Shared with SessionsScreen — passed from AppNavigation
) {
    val uiState by viewModel.uiState.collectAsState()
    val s = AppLocale.strings
    val folderName = directory.replace('\\', '/').substringAfterLast('/')

    // Auto-refresh when navigating back to this screen
    LifecycleResumeEffect(Unit) {
        viewModel.loadSessions()
        viewModel.loadSessionUsageForDirectory(directory)
        onPauseOrDispose { /* no-op */ }
    }

    // Auto-navigate when a new session is created
    LaunchedEffect(Unit) {
        viewModel.creationEvents.collect { newSessionId ->
            onSessionClick(newSessionId)
        }
    }

    val projectSessions = remember(uiState.sessions, directory) {
        uiState.sessions
            .filter { it.directory == directory }
            .sortedByDescending { it.time?.updated ?: it.time?.created ?: 0L }
    }

    LaunchedEffect(directory, projectSessions.map { it.id }) {
        if (directory.isNotBlank()) {
            viewModel.loadSessionUsageForDirectory(directory)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(folderName)
                        Text(
                            text = directory,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = s.helpBack)
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.toggleHideChildSessions() }) {
                        Icon(
                            imageVector = if (uiState.hideChildSessions) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (uiState.hideChildSessions) s.showChildSessions else s.hideChildSessions,
                        )
                    }
                    IconButton(onClick = { viewModel.loadSessions() }) {
                        Icon(Icons.Default.Refresh, contentDescription = s.refresh)
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { viewModel.createSession(directory) },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text(s.newSession) },
                expanded = projectSessions.isEmpty(),
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                uiState.isLoading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                    )
                }

                projectSessions.isEmpty() -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            imageVector = Icons.Default.ChatBubbleOutline,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = s.noSessions,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = s.noSessionsHint,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                else -> {
                    LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(
                            items = projectSessions,
                            key = { it.id }
                        ) { session ->
                            SessionCard(
                                session = session,
                                usage = uiState.sessionUsageById[session.id],
                                onClick = { onSessionClick(session.id) },
                                onDelete = { viewModel.deleteSession(session.id, directory) },
                                onFork = { viewModel.forkSession(session.id, directory) },
                                onRename = { title, onSuccess -> viewModel.renameSession(session.id, title, directory, onSuccess) },
                                onToggleShare = { onSuccess -> viewModel.toggleShareSession(session.id, session.shared, directory, onSuccess) },
                            )
                        }
                    }
                }
            }

            ErrorSnackbar(
                error = uiState.error,
                onDismiss = viewModel::clearError,
            )
        }
    }
}

@Composable
private fun SessionCard(
    session: SessionInfo,
    usage: SessionUsageSummary?,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onFork: () -> Unit,
    onRename: (String, () -> Unit) -> Unit,
    onToggleShare: ((SessionInfo) -> Unit) -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameText by remember(session.id, session.title, session.slug) {
        mutableStateOf(session.title ?: session.slug ?: "")
    }
    val s = AppLocale.strings
    val context = LocalContext.current

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val isCompleted = session.time?.completed != null && session.time.completed > 0
            Surface(
                modifier = Modifier.size(12.dp),
                shape = MaterialTheme.shapes.small,
                color = if (isCompleted) MaterialTheme.colorScheme.tertiary
                        else MaterialTheme.colorScheme.primary,
            ) {}

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = session.title ?: session.slug ?: "Session ${session.id.take(8)}...",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = if (isCompleted) "COMPLETED" else "ACTIVE",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isCompleted) MaterialTheme.colorScheme.onSurfaceVariant
                                else MaterialTheme.colorScheme.primary,
                    )
                    session.version?.let { ver ->
                        Text(
                            text = "v$ver",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    usage?.let { u ->
                        Text(
                            text = u.usagePercent?.let { "$it%" } ?: "${u.totalTokens} ${s.contextTokens}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = s.moreActions,
                    )
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(s.renameSession) },
                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                        onClick = {
                            showMenu = false
                            renameText = session.title ?: session.slug ?: ""
                            showRenameDialog = true
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(if (session.shared) s.unshareSession else s.shareSession) },
                        leadingIcon = {
                            Icon(
                                if (session.shared) Icons.Default.LinkOff else Icons.Default.Link,
                                contentDescription = null,
                            )
                        },
                        onClick = {
                            showMenu = false
                            onToggleShare { updated ->
                                if (updated.shared) {
                                    val link = updated.share?.url
                                    if (!link.isNullOrBlank()) {
                                        copyToClipboard(context, s.copyShareLink, link)
                                        Toast.makeText(context, s.copiedShareLink, Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, s.sessionShared, Toast.LENGTH_SHORT).show()
                                    }
                                } else {
                                    Toast.makeText(context, s.sessionUnshared, Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                    )
                    if (!session.share?.url.isNullOrBlank()) {
                        DropdownMenuItem(
                            text = { Text(s.copyShareLink) },
                            leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                            onClick = {
                                showMenu = false
                                val link = session.share?.url
                                if (!link.isNullOrBlank()) {
                                    copyToClipboard(context, s.copyShareLink, link)
                                    Toast.makeText(context, s.copiedShareLink, Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, s.errCopyShareLink, Toast.LENGTH_SHORT).show()
                                }
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(s.copySessionId) },
                        leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                        onClick = {
                            showMenu = false
                            copyToClipboard(context, s.copySessionId, session.id)
                            Toast.makeText(context, s.copiedSessionId, Toast.LENGTH_SHORT).show()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(s.forkSession) },
                        leadingIcon = { Icon(Icons.Default.ForkRight, contentDescription = null) },
                        onClick = {
                            showMenu = false
                            onFork()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(s.delete, color = MaterialTheme.colorScheme.error) },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                        },
                        onClick = {
                            showMenu = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }

    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text(s.renameDialogTitle) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    label = { Text(s.sessionTitleLabel) },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRename(renameText) {
                            showRenameDialog = false
                            Toast.makeText(context, s.renameSuccess, Toast.LENGTH_SHORT).show()
                        }
                    },
                    enabled = renameText.trim().isNotEmpty(),
                ) {
                    Text(s.save)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text(s.cancel)
                }
            },
        )
    }
}

private fun copyToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
}
