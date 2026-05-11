package com.opencode.remote.ui.chat

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.opencode.remote.data.api.dto.MessageInfo
import com.opencode.remote.data.api.dto.MessageInfoData
import com.opencode.remote.ui.components.ErrorSnackbar
import com.opencode.remote.ui.strings.AppLocale
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    sessionId: String,
    directory: String? = null,
    onBack: () -> Unit,
    viewModel: ChatViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val s = AppLocale.strings
    val density = LocalDensity.current

    // Panel gesture tracking — cumulative horizontal drag for open/close
    var cumulativeDragX by remember { mutableFloatStateOf(0f) }
    val dragThresholdPx = with(density) { 40.dp.toPx() }

    // Panel dimensions and animations
    val panelWidthDp = 280.dp
    val panelOffset by animateDpAsState(
        targetValue = if (uiState.isPanelOpen) 0.dp else panelWidthDp,
        animationSpec = tween(durationMillis = 350, easing = FastOutSlowInEasing),
        label = "panel_offset",
    )
    val contentOffset by animateDpAsState(
        targetValue = if (uiState.isPanelOpen) -panelWidthDp else 0.dp,
        animationSpec = tween(durationMillis = 350, easing = FastOutSlowInEasing),
        label = "content_offset",
    )

    LaunchedEffect(sessionId) {
        viewModel.initialize(sessionId, directory)
    }

    // Auto-refresh when navigating back to this screen
    LifecycleResumeEffect(sessionId) {
        viewModel.initialize(sessionId, directory)
        onPauseOrDispose { /* no-op */ }
    }

    // Total items = messages + optional active assistant panel
    // The active assistant panel handles three states: waiting, streaming, and disappears
    // when idle — all as a single __active_assistant__ item to avoid add/remove flicker.
    val isActive = uiState.isSending || uiState.isStreaming
    val totalItems = uiState.messages.size + (if (isActive) 1 else 0)

    // Track whether we should auto-scroll (user started a conversation turn)
    var shouldAutoScroll by remember { mutableStateOf(true) }

    // Detect when user scrolls up manually — disable auto-scroll
    LaunchedEffect(Unit) {
        snapshotFlow {
            val layoutInfo = listState.layoutInfo
            val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            val total = layoutInfo.totalItemsCount
            lastVisible >= total - 2
        }.collect { nearBottom ->
            shouldAutoScroll = nearBottom
        }
    }

    // Auto-scroll when content changes and we should be at bottom
    LaunchedEffect(totalItems, uiState.streamingSegments.size, uiState.streamingSegments.lastOrNull()?.text?.length) {
        if (totalItems > 0 && shouldAutoScroll) {
            coroutineScope.launch {
                listState.scrollToItem(totalItems - 1)
            }
        }
    }

    // Re-enable auto-scroll when a new send starts
    LaunchedEffect(uiState.isSending, uiState.isStreaming) {
        if (uiState.isSending || uiState.isStreaming) {
            shouldAutoScroll = true
        }
    }

    // Outer Box with swipe gesture detection to open/close panel
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = {
                        cumulativeDragX = 0f
                    },
                    onHorizontalDrag = { change, dragAmount ->
                        cumulativeDragX += dragAmount
                        if (!uiState.isPanelOpen && cumulativeDragX < -dragThresholdPx) {
                            change.consume()
                            viewModel.setPanelOpen(true)
                        } else if (uiState.isPanelOpen && cumulativeDragX > dragThresholdPx) {
                            change.consume()
                            viewModel.setPanelOpen(false)
                        }
                    },
                    onDragEnd = {
                        cumulativeDragX = 0f
                    },
                    onDragCancel = {
                        cumulativeDragX = 0f
                    },
                )
            }
    ) {
        // Main content (Scaffold) — shifts left when panel opens
        Box(modifier = Modifier.offset(x = contentOffset)) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = {
                            Column {
                                Text(
                                    text = uiState.sessionTitle ?: s.sessionFallback,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                uiState.sessionStatus?.let { status ->
                                    Text(
                                        text = status,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        },
                        navigationIcon = {
                            IconButton(onClick = onBack) {
                                Icon(Icons.Default.ArrowBack, contentDescription = s.helpBack)
                            }
                        },
                        actions = {
                            if (uiState.availableAgents.isNotEmpty()) {
                                AgentPickerButton(
                                    selectedAgent = uiState.selectedAgent,
                                    availableAgents = uiState.availableAgents,
                                    showAgentPicker = uiState.showAgentPicker,
                                    onTogglePicker = viewModel::toggleAgentPicker,
                                    onSelectAgent = viewModel::selectAgent,
                                )
                            }
                            if (uiState.todoItems.isNotEmpty()) {
                                BadgedBox(
                                    badge = { Badge { Text("${uiState.todoItems.size}") } }
                                ) {
                                    IconButton(onClick = viewModel::toggleTodoPanel) {
                                        Icon(Icons.Default.Checklist, contentDescription = "Todo")
                                    }
                                }
                            } else {
                                IconButton(onClick = viewModel::toggleTodoPanel) {
                                    Icon(Icons.Default.Checklist, contentDescription = "Todo")
                                }
                            }
                            if (uiState.isSending || uiState.isStreaming) {
                                IconButton(onClick = viewModel::abortSession) {
                                    Icon(
                                        Icons.Default.Stop,
                                        contentDescription = s.stop,
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                }
                            } else {
                                // Undo button — show when there are user messages to undo
                                val hasUserMessages = uiState.messages.any { it.role == "user" }
                                if (hasUserMessages) {
                                    IconButton(onClick = viewModel::undoLastMessage) {
                                        Icon(
                                            Icons.Default.Undo,
                                            contentDescription = s.undo,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                // Redo button — show when there's an active revert
                                if (uiState.revertMessageId != null) {
                                    IconButton(onClick = viewModel::redoLastUndo) {
                                        Icon(
                                            Icons.Default.Redo,
                                            contentDescription = s.redo,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                            IconButton(onClick = { viewModel.initialize(sessionId, directory) }) {
                                Icon(Icons.Default.Refresh, contentDescription = s.refresh)
                            }
                        },
                    )
                },
                bottomBar = {
                    Column {
                        // Permission/Question bubble (between messages and input)
                        uiState.pendingPermission?.let { request ->
                            PermissionConfirmBubble(
                                permission = request.permission,
                                patterns = request.patterns,
                                alwaysPatterns = request.always,
                                onReply = { reply, message -> viewModel.replyPermission(reply, message) },
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            )
                        }

                        uiState.pendingQuestion?.let { request ->
                            QuestionAskBubble(
                                questions = request.questions,
                                onReply = { answers -> viewModel.replyQuestion(answers) },
                                onReject = { viewModel.rejectQuestion() },
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            )
                        }

                        // AI waiting indicator when blocked
                        if (uiState.isBlocked) {
                            Surface(
                                tonalElevation = 2.dp,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(14.dp),
                                        strokeWidth = 2.dp,
                                    )
                                    Text(
                                        s.aiWaiting,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }

                        ChatInputBar(
                            inputText = uiState.inputText,
                            onInputChange = viewModel::onInputChange,
                            onSend = viewModel::sendMessage,
                            isSending = uiState.isSending || uiState.isBlocked,
                            selectedModel = uiState.selectedModel,
                            availableModels = uiState.availableModels,
                            onSelectModel = viewModel::selectModel,
                            isLoadingModels = uiState.isLoadingModels,
                            contextUsageK = uiState.contextUsageK,
                        )
                    }
                },
            ) { padding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        when {
                            uiState.isLoading -> {
                                item(key = "__loading__") {
                                    Box(
                                        modifier = Modifier.fillMaxWidth(),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.padding(vertical = 64.dp),
                                        )
                                    }
                                }
                            }

                            uiState.messages.isEmpty() && !uiState.isSending -> {
                                item(key = "__empty__") {
                                    Box(
                                        modifier = Modifier.fillMaxWidth(),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(vertical = 64.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Send,
                                                contentDescription = null,
                                                modifier = Modifier.size(64.dp),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                            Spacer(modifier = Modifier.height(16.dp))
                                            Text(
                                                text = s.sendFirstMsg,
                                                style = MaterialTheme.typography.titleMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                            }

                            else -> {
                                // Build display list: real messages + optional streaming placeholder.
                                // The placeholder uses the real message id (from pendingAssistantMessageId)
                                // so that when getMessages() returns the actual assistant message with
                                // the same id, LazyColumn sees the same key → no flicker.
                                val activeId = if (isActive) {
                                    uiState.pendingAssistantMessageId ?: "__placeholder__"
                                } else null
                                val displayMessages = if (activeId != null && uiState.messages.none { it.id == activeId }) {
                                    uiState.messages + MessageInfo(
                                        info = MessageInfoData(id = activeId, role = "assistant", agent = uiState.streamingAgent),
                                    )
                                } else {
                                    uiState.messages
                                }
                                val streamingId = uiState.pendingAssistantMessageId

                                items(
                                    items = displayMessages,
                                    key = { it.id }
                                ) { message ->
                                    if (message.role == "user") {
                                        UserMessageItem(message = message)
                                    } else if (isActive && message.id == streamingId) {
                                        // Active streaming assistant — show streaming content or waiting spinner
                                        if (uiState.isStreaming && uiState.streamingSegments.isNotEmpty()) {
                                            AiResponsePanel(
                                                agentName = uiState.streamingAgent ?: "AI",
                                                segments = uiState.streamingSegments,
                                                isStreaming = true,
                                            )
                                        } else {
                                            // Waiting / thinking spinner
                                            Surface(
                                                shape = RoundedCornerShape(8.dp),
                                                color = MaterialTheme.colorScheme.surface,
                                                modifier = Modifier.fillMaxWidth(),
                                            ) {
                                                Row(
                                                    modifier = Modifier.padding(12.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                ) {
                                                    Text(
                                                        uiState.streamingAgent ?: "AI",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        fontWeight = FontWeight.Bold,
                                                        color = MaterialTheme.colorScheme.primary,
                                                    )
                                                    CircularProgressIndicator(
                                                        modifier = Modifier.size(14.dp),
                                                        strokeWidth = 2.dp,
                                                    )
                                                    Text(
                                                        s.thinkingActive,
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    )
                                                }
                                            }
                                        }
                                    } else {
                                        // Completed message — parse and render normally
                                        val segments = remember(message.id) { parseMessageSegments(message) }
                                        AiResponsePanel(
                                            agentName = message.info.agent ?: "AI",
                                            segments = segments,
                                            isStreaming = false,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Todo panel overlay
                    if (uiState.showTodoPanel) {
                        TodoPanel(
                            todos = uiState.todoItems,
                            onDismiss = viewModel::toggleTodoPanel,
                            modifier = Modifier.align(Alignment.TopEnd),
                        )
                    }

                    // Error snackbar
                    ErrorSnackbar(
                        error = uiState.error,
                        onDismiss = viewModel::clearError,
                    )
                }
            }
        }

        // Right panel — slides in from right edge
        // Condition: show when open OR still animating closed (panelOffset > 0 means not fully hidden)
        if (uiState.isPanelOpen || panelOffset < panelWidthDp) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .offset(x = panelOffset)
            ) {
                RightPanel(
                    files = uiState.panelFiles,
                    currentPath = uiState.currentFilePath,
                    onNavigateToDirectory = viewModel::navigateToDirectory,
                    isLoadingFiles = uiState.isLoadingFiles,
                    onNavigateUp = viewModel::navigateUp,
                    expandedFilePath = uiState.expandedFilePath,
                    expandedFileContent = uiState.expandedFileContent,
                    isLoadingFileContent = uiState.isLoadingFileContent,
                    onToggleFilePreview = viewModel::toggleFilePreview,
                )
            }
        }
    }
}
