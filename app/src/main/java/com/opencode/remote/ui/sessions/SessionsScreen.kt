package com.opencode.remote.ui.sessions

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
        onPauseOrDispose { /* no-op */ }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(s.projects)
                        val subtitle = uiState.currentServerName
                            ?: uiState.projectName?.let { "${s.serverPath}: $it" }
                        subtitle?.let {
                            Text(
                                text = it,
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
                    IconButton(onClick = onDisconnected) {
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
    val density = LocalDensity.current

    // ── Memo panel gesture + animation (same pattern as ChatScreen file panel) ──
    var cumulativeDragX by remember { mutableFloatStateOf(0f) }
    val dragThresholdPx = with(density) { 40.dp.toPx() }
    val panelWidthDp = 280.dp
    val memoPanelOffset by animateDpAsState(
        targetValue = if (uiState.isMemoPanelOpen) 0.dp else panelWidthDp,
        animationSpec = tween(durationMillis = 350, easing = FastOutSlowInEasing),
        label = "memo_panel_offset",
    )
    val memoContentOffset by animateDpAsState(
        targetValue = if (uiState.isMemoPanelOpen) -panelWidthDp else 0.dp,
        animationSpec = tween(durationMillis = 350, easing = FastOutSlowInEasing),
        label = "memo_content_offset",
    )

    // Auto-refresh when navigating back to this screen
    LifecycleResumeEffect(Unit) {
        viewModel.loadSessions()
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

    // Outer Box with swipe gesture detection for memo panel
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { cumulativeDragX = 0f },
                    onHorizontalDrag = { change, dragAmount ->
                        cumulativeDragX += dragAmount
                        if (!uiState.isMemoPanelOpen && cumulativeDragX < -dragThresholdPx) {
                            change.consume()
                            viewModel.setMemoPanelOpen(true, directory)
                        } else if (uiState.isMemoPanelOpen && cumulativeDragX > dragThresholdPx) {
                            change.consume()
                            viewModel.closeMemoPanel()
                        }
                    },
                    onDragEnd = { cumulativeDragX = 0f },
                    onDragCancel = { cumulativeDragX = 0f },
                )
            }
    ) {
        // Main content — shifts left when memo panel opens
        Box(modifier = Modifier.offset(x = memoContentOffset)) {
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
                            IconButton(onClick = {
                                if (uiState.isMemoPanelOpen) viewModel.closeMemoPanel() else onBack()
                            }) {
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
                                        onClick = { onSessionClick(session.id) },
                                        onDelete = { viewModel.deleteSession(session.id, directory) },
                                        onFork = { viewModel.forkSession(session.id, directory) },
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

        // Memo panel — slides in from right edge
        if (uiState.isMemoPanelOpen || memoPanelOffset < panelWidthDp) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .offset(x = memoPanelOffset)
            ) {
                MemoPanel(
                    projectName = folderName,
                    memos = uiState.memos,
                    onAdd = { viewModel.addMemo(directory) },
                    onUpdate = { viewModel.updateMemo(directory, it) },
                    onDelete = { viewModel.deleteMemo(directory, it) },
                    onClose = { viewModel.closeMemoPanel() },
                )
            }
        }
    }
}

@Composable
private fun SessionCard(
    session: SessionInfo,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onFork: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    val s = AppLocale.strings

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
}
