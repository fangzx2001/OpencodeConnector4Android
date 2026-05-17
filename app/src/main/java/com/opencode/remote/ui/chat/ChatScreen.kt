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
import kotlinx.coroutines.flow.first
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

    // ── Scroll System ─────────────────────────────────────────────────────
    // Design: open-at-bottom, auto-follow during streaming, unlock on user
    // scroll-up, re-lock when user scrolls back to bottom.
    //
    // KEY INSIGHT: We do NOT use isLoading as a scroll trigger. isLoading
    // changes during the Composition phase, but LazyColumn's totalItemsCount
    // only updates during the Layout phase (after composition). Using
    // !isLoading as a condition causes a race: snapshotFlow re-evaluates
    // when isLoading changes (before layout), sees stale totalItemsCount=1
    // (from the __loading__ placeholder), and scrolls to the wrong position.
    // Fix: wait for totalItemsCount to match the expected data count, which
    // can only happen after layout completes.
    var shouldAutoScroll by remember { mutableStateOf(true) }
    var initialScrollDone by remember(sessionId) { mutableStateOf(false) }
    var resumeKey by remember { mutableIntStateOf(0) }
    val coroutineScope = rememberCoroutineScope()

    // Session initialization
    LaunchedEffect(sessionId) {
        shouldAutoScroll = true
        initialScrollDone = false
        viewModel.initialize(sessionId, directory)
    }

    // Resume: re-init + trigger re-scroll
    LifecycleResumeEffect(sessionId) {
        viewModel.initialize(sessionId, directory)
        shouldAutoScroll = true
        initialScrollDone = false
        resumeKey++
        onPauseOrDispose { /* no-op */ }
    }

    // Total items = messages + optional active assistant panel
    val isActive = uiState.isSending || uiState.isStreaming
    val totalItems = uiState.messages.size + (if (isActive) 1 else 0)

    // 1. Initial scroll: wait for LazyColumn layout to reflect actual data.
    //    Re-fires on resume (via resumeKey) to re-scroll after re-init.
    //
    //    RACE FIX: We compare layout-derived totalItemsCount against data-derived
    //    expected count. Even if isLoading triggers a snapshotFlow re-evaluation
    //    before layout updates, the stale totalItemsCount won't match the expected
    //    data count, so the condition can't pass prematurely.
    LaunchedEffect(sessionId, resumeKey) {
        snapshotFlow {
            val layoutCount = listState.layoutInfo.totalItemsCount
            val dataCount = uiState.messages.size + (if (uiState.isSending || uiState.isStreaming) 1 else 0)
            layoutCount to dataCount
        }.first { (layout, data) -> data > 0 && layout >= data }
        listState.scrollToItem(listState.layoutInfo.totalItemsCount - 1)
        initialScrollDone = true
    }

    // 2. User scroll tracking: detect near-bottom vs away-from-bottom.
    //    Only active after initial scroll completes — prevents the race where
    //    LazyColumn starts at top (before initial scroll) and incorrectly
    //    sets shouldAutoScroll = false.
    LaunchedEffect(Unit) {
        snapshotFlow {
            val layoutInfo = listState.layoutInfo
            if (layoutInfo.totalItemsCount == 0) return@snapshotFlow null
            val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            val total = layoutInfo.totalItemsCount
            lastVisible >= total - 2
        }.collect { atBottom ->
            if (atBottom == null || !initialScrollDone) return@collect
            shouldAutoScroll = atBottom
        }
    }

    // 3. Auto-scroll on content changes (new messages, streaming text updates)
    //    Uses scrollToItem + forward scroll to ensure the BOTTOM of the last item
    //    is visible, not just the top. Without the forward scroll, tall streaming
    //    items (agent name + thinking bubble + text) can anchor the viewport at the
    //    agent name, making content below invisible and blocking user scroll.
    LaunchedEffect(totalItems, uiState.streamingSegments.size, uiState.streamingSegments.lastOrNull()?.text?.length) {
        if (!initialScrollDone || totalItems <= 0) return@LaunchedEffect
        if (shouldAutoScroll) {
            listState.scrollToItem(totalItems - 1)
            // If the last item is taller than the viewport, scrollToItem only shows
            // its top (agent name). Scroll forward to the absolute bottom so the
            // latest streaming content is visible.
            if (listState.canScrollForward) {
                listState.scroll { scrollBy(100_000f) }
            }
        }
    }

    // 4. Re-enable auto-follow when user sends a new message
    LaunchedEffect(uiState.isSending) {
        if (uiState.isSending) {
            shouldAutoScroll = true
        }
    }

    // 5. Scroll to bottom when keyboard opens (viewport shrinks).
    //    Without this, the keyboard pushes the input bar up but the chat content
    //    stays in place, hiding the bottom messages behind the keyboard area.
    LaunchedEffect(listState.layoutInfo.viewportSize) {
        if (!initialScrollDone || totalItems <= 0) return@LaunchedEffect
        if (shouldAutoScroll) {
            listState.scrollToItem(totalItems - 1)
            if (listState.canScrollForward) {
                listState.scroll { scrollBy(100_000f) }
            }
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

                        // Recovery bubble — heuristic detected interrupted blocking state
                        if (uiState.isBlocked && uiState.pendingPermission == null && uiState.pendingQuestion == null && uiState.recoveryPending) {
                            RecoveryBubble(
                                onCheckStatus = { viewModel.recheckBlockingState() },
                                onDismiss = { viewModel.dismissBlocking() },
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
                            isSending = uiState.isSending || (uiState.isBlocked && !uiState.recoveryPending),
                            selectedModel = uiState.selectedModel,
                            availableModels = uiState.availableModels,
                            onSelectModel = viewModel::selectModel,
                            isLoadingModels = uiState.isLoadingModels,
                            contextUsageK = uiState.contextUsageK,
                            onScrollToBottom = {
                                shouldAutoScroll = true
                                coroutineScope.launch {
                                    val total = listState.layoutInfo.totalItemsCount
                                    if (total > 0) {
                                        listState.scrollToItem(total - 1)
                                        if (listState.canScrollForward) {
                                            listState.scroll { scrollBy(100_000f) }
                                        }
                                    }
                                }
                            },
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

@Composable
internal fun RecoveryBubble(
    onCheckStatus: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = AppLocale.strings
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f),
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header with info icon
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    s.recoveryBubbleTitle,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }

            Spacer(Modifier.height(8.dp))

            Text(
                s.recoveryBubbleMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )

            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onCheckStatus,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(s.recoveryCheckStatus)
                }
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(s.recoveryDismiss)
                }
            }
        }
    }
}
