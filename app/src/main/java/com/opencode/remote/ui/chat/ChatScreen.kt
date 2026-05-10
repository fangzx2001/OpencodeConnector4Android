package com.opencode.remote.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.opencode.remote.ui.components.ErrorSnackbar
import com.opencode.remote.ui.strings.AppLocale
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    sessionId: String,
    directory: String? = null,
    onBack: () -> Unit,
    viewModel: ChatViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    var refreshEpoch by remember(sessionId) { mutableIntStateOf(0) }
    val listState = remember(refreshEpoch, sessionId) { androidx.compose.foundation.lazy.LazyListState() }
    val coroutineScope = rememberCoroutineScope()
    val s = AppLocale.strings
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameText by remember(uiState.sessionTitle) { mutableStateOf(uiState.sessionTitle ?: "") }

    LifecycleResumeEffect(sessionId, directory) {
        viewModel.initialize(sessionId, directory)
        onPauseOrDispose { /* no-op */ }
    }

    // Total items = messages + optional streaming panel + optional waiting indicator + bottom anchor
    val totalItems = uiState.messages.size +
            (if (uiState.isStreaming && uiState.streamingSegments.isNotEmpty()) 1 else 0) +
            (if (uiState.isSending && uiState.streamingSegments.isEmpty()) 1 else 0) +
            1

    val latestMessageRevision = remember(uiState.messages) {
        uiState.messages.lastOrNull()?.let { message ->
            buildString {
                append(message.id)
                message.parts.forEach { part ->
                    append('|')
                    append(part.type)
                    append(':')
                    append(part.text.orEmpty())
                }
            }
        }.orEmpty()
    }

    val streamingRevision = remember(uiState.streamingSegments) {
        buildString {
            uiState.streamingSegments.forEach { segment ->
                append(segment.id.orEmpty())
                append('|')
                append(segment.type)
                append(':')
                append(segment.text)
                append(';')
            }
        }
    }

    val contentRevision = remember(
        totalItems,
        latestMessageRevision,
        streamingRevision,
        uiState.isStreaming,
        uiState.isSending,
    ) {
        listOf(
            totalItems.toString(),
            latestMessageRevision,
            streamingRevision,
            uiState.isStreaming.toString(),
            uiState.isSending.toString(),
        ).joinToString("#")
    }

    val bottomFollow = remember(sessionId, refreshEpoch) { BottomFollowCoordinator() }
    var shouldAutoFollowBottom by bottomFollow.shouldAutoFollowBottomState
    var isProgrammaticScroll by bottomFollow.isProgrammaticScrollState
    var isNearBottom by bottomFollow.isNearBottomState
    var bottomLockMode by bottomFollow.bottomLockModeState
    var suspendAutoScrollUntilGestureEnds by bottomFollow.suspendAutoScrollUntilGestureEndsState
    var scrollRequestGeneration by bottomFollow.scrollRequestGenerationState
    var bottomControlVisible by bottomFollow.bottomControlVisibleState
    var bottomNotice by bottomFollow.bottomNoticeState
    var bottomNoticeVersion by bottomFollow.bottomNoticeVersionState
    val bottomControlAlpha = remember(sessionId, refreshEpoch) { Animatable(1f) }
    var bottomScrollJob by remember(sessionId, refreshEpoch) { mutableStateOf<Job?>(null) }
    val forceBottomMode = bottomFollow.forceBottomMode
    val bottomButtonColor by animateColorAsState(
        targetValue = if (forceBottomMode) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer,
        animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
        label = "bottomButtonColor",
    )
    val bottomButtonContentColor by animateColorAsState(
        targetValue = if (forceBottomMode) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer,
        animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
        label = "bottomButtonContentColor",
    )
    val bottomButtonScale by animateFloatAsState(
        targetValue = if (forceBottomMode) 1.04f else 1f,
        animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing),
        label = "bottomButtonScale",
    )
    val bottomButtonShadow by animateDpAsState(
        targetValue = if (forceBottomMode) 10.dp else 6.dp,
        animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing),
        label = "bottomButtonShadow",
    )

    fun showBottomNotice(message: String) {
        bottomFollow.showBottomNotice(message)
    }

    fun showBottomControl() {
        bottomFollow.showBottomControl()
        coroutineScope.launch {
            bottomControlAlpha.snapTo(1f)
        }
    }

    fun scrollToBottom(force: Boolean = false) {
        if (totalItems <= 0) return
        bottomScrollJob?.cancel()
        if (force) {
            suspendAutoScrollUntilGestureEnds = false
        }
        val requestGeneration = bottomFollow.beginProgrammaticScroll()
        bottomScrollJob = coroutineScope.launch {
            if (!force && suspendAutoScrollUntilGestureEnds) return@launch
            if (requestGeneration != scrollRequestGeneration) return@launch
            if (!force && listState.isScrollInProgress && !isProgrammaticScroll) return@launch
            try {
                if (requestGeneration != scrollRequestGeneration) return@launch
                if (!force && listState.isScrollInProgress && !isProgrammaticScroll) return@launch
                listState.scrollToItem(totalItems - 1)
            } finally {
                bottomFollow.finishProgrammaticScroll(requestGeneration)
            }
        }
    }

    fun enableForceBottomMode() {
        bottomFollow.enableForceBottomMode(
            onAutoScroll = ::scrollToBottom,
            onNotice = {
                if (it == "__force_on__") {
                    showBottomNotice(s.forceBottomEnabled)
                }
            },
        )
    }

    fun disableForceBottomMode(showNotice: Boolean) {
        bottomFollow.disableForceBottomMode(
            showNotice = showNotice,
            onNotice = {
                if (it == "__force_off__") {
                    showBottomNotice(s.forceBottomDisabledByScroll)
                }
            },
        )
        showBottomControl()
    }

    fun cancelForceBottomFromGesture() {
        bottomScrollJob?.cancel()
        bottomScrollJob = null
        bottomFollow.cancelForceBottomFromGesture(
            onNotice = {
                if (it == "__force_off__") {
                    showBottomNotice(s.forceBottomDisabledByScroll)
                }
            },
        )
        showBottomControl()
    }

    val bottomGestureInterceptor = remember(sessionId, forceBottomMode) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.Drag && forceBottomMode && available.y != 0f) {
                    cancelForceBottomFromGesture()
                }
                return Offset.Zero
            }
        }
    }

    LaunchedEffect(listState, sessionId, totalItems) {
        var previousIndex = listState.firstVisibleItemIndex
        var previousOffset = listState.firstVisibleItemScrollOffset
        snapshotFlow {
            Triple(
                listState.firstVisibleItemIndex,
                listState.firstVisibleItemScrollOffset,
                listState.isScrollInProgress,
            )
        }
            .filter { totalItems > 0 }
            .map { (_, _, isScrollInProgress) ->
                val layoutInfo = listState.layoutInfo
                val lastVisibleItem = layoutInfo.visibleItemsInfo.lastOrNull()
                val lastVisibleIndex = lastVisibleItem?.index ?: -1
                val viewportEnd = layoutInfo.viewportEndOffset
                val lastItemBottom = lastVisibleItem?.let { it.offset + it.size } ?: Int.MIN_VALUE
                val isAnchorVisible = lastVisibleIndex >= totalItems - 1
                val nearBottomCandidate = lastVisibleIndex >= totalItems - 2 && (viewportEnd - lastItemBottom) <= 96
                Pair(isScrollInProgress, isAnchorVisible || nearBottomCandidate)
            }
            .distinctUntilChanged()
            .collect { (isScrollInProgress, isNearBottomNow) ->
                if (isProgrammaticScroll) return@collect

                val currentIndex = listState.firstVisibleItemIndex
                val currentOffset = listState.firstVisibleItemScrollOffset
                isNearBottom = isNearBottomNow
                if (isScrollInProgress) {
                    showBottomControl()
                    if (forceBottomMode && !isProgrammaticScroll) {
                        cancelForceBottomFromGesture()
                    } else if (!forceBottomMode) {
                        shouldAutoFollowBottom = isNearBottomNow
                    }
                } else if (isNearBottomNow && !forceBottomMode) {
                    shouldAutoFollowBottom = true
                    suspendAutoScrollUntilGestureEnds = false
                } else if (!isScrollInProgress) {
                    suspendAutoScrollUntilGestureEnds = false
                }

                previousIndex = currentIndex
                previousOffset = currentOffset
            }
    }

    LaunchedEffect(contentRevision, shouldAutoFollowBottom, forceBottomMode, suspendAutoScrollUntilGestureEnds) {
        if (totalItems > 0 && !suspendAutoScrollUntilGestureEnds && (shouldAutoFollowBottom || forceBottomMode)) {
            scrollToBottom()
        }
    }

    LaunchedEffect(listState.isScrollInProgress, isNearBottom, forceBottomMode, bottomControlVisible) {
        if (listState.isScrollInProgress) {
            showBottomControl()
            return@LaunchedEffect
        }

        if (!bottomControlVisible) {
            return@LaunchedEffect
        }

        bottomControlAlpha.snapTo(1f)
        delay(3000)
        if (!listState.isScrollInProgress) {
            bottomControlAlpha.animateTo(0.2f)
        }

        delay(6000)
        if (!listState.isScrollInProgress) {
            bottomControlAlpha.animateTo(0f)
            bottomControlVisible = false
            bottomControlAlpha.snapTo(1f)
        }
    }

    LaunchedEffect(bottomNoticeVersion) {
        if (bottomNotice.isNullOrBlank()) return@LaunchedEffect
        delay(1800)
        bottomNotice = null
    }

    val showBottomControl = totalItems > 1 && bottomControlVisible
    val bottomControlInteractionSource = remember(sessionId) { MutableInteractionSource() }

    fun toggleForceBottomMode() {
        if (forceBottomMode) {
            bottomLockMode = BottomLockMode.Auto
            shouldAutoFollowBottom = false
            showBottomControl()
            showBottomNotice(s.forceBottomDisabled)
        } else {
            enableForceBottomMode()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = uiState.sessionTitle ?: s.sessionFallback,
                            maxLines = 1,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .basicMarquee(iterations = Int.MAX_VALUE)
                                .clickable {
                                    renameText = uiState.sessionTitle ?: ""
                                    showRenameDialog = true
                                },
                        )
                        uiState.contextUsage?.let { usage ->
                            Text(
                                text = usage.usagePercent?.let { "$it%" } ?: "${usage.totalTokens} ${s.contextTokens}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.clickable { viewModel.openContextUsageDialog() },
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
                    ChatSelectionConfigButton(
                        hasExplicitOverrides = uiState.selection.hasExplicitOverrides,
                        onClick = viewModel::openSelectionDialog,
                    )
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
                    }
                    IconButton(onClick = {
                        refreshEpoch++
                        showRenameDialog = false
                        viewModel.refreshSession(sessionId, directory)
                    }) {
                        Icon(Icons.Default.Refresh, contentDescription = s.refresh)
                    }
                },
            )
        },
        bottomBar = {
            ChatInputBar(
                inputText = uiState.inputText,
                onInputChange = viewModel::onInputChange,
                onSend = viewModel::sendMessage,
                isSending = uiState.isSending,
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .nestedScroll(bottomGestureInterceptor),
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
                        items(
                            items = uiState.messages,
                            key = { it.id }
                        ) { message ->
                            if (message.role == "user") {
                                UserMessageItem(message = message)
                            } else {
                                val segments = remember(message.id) { parseMessageSegments(message) }
                                AiResponsePanel(
                                    agentName = message.info.agent ?: "AI",
                                    segments = segments,
                                    isStreaming = false,
                                )
                            }
                        }

                        // Streaming panel — shows text + thinking + tool segments in sequence
                        if (uiState.isStreaming && uiState.streamingSegments.isNotEmpty()) {
                            item(key = "__streaming__") {
                                AiResponsePanel(
                                    agentName = uiState.streamingAgent ?: "AI",
                                    segments = uiState.streamingSegments,
                                    isStreaming = true,
                                )
                            }
                        }

                        // Waiting indicator — no segments yet, agent is thinking
                        if (uiState.isSending && uiState.streamingSegments.isEmpty()) {
                            item(key = "__waiting__") {
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
                        }
                    }
                }

                item(key = "__bottom_anchor__") {
                    Spacer(modifier = Modifier.height(1.dp))
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

            AnimatedVisibility(
                visible = showBottomControl,
                enter = fadeIn(animationSpec = tween(180)),
                exit = fadeOut(animationSpec = tween(120)),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 88.dp),
            ) {
                Surface(
                    shape = CircleShape,
                    color = bottomButtonColor,
                    contentColor = bottomButtonContentColor,
                    shadowElevation = bottomButtonShadow,
                    modifier = Modifier
                        .size(56.dp)
                        .alpha(bottomControlAlpha.value)
                        .graphicsLayer {
                            scaleX = bottomButtonScale
                            scaleY = bottomButtonScale
                        }
                        .clip(CircleShape)
                        .combinedClickable(
                            interactionSource = bottomControlInteractionSource,
                            indication = LocalIndication.current,
                            onClick = {
                                shouldAutoFollowBottom = true
                                suspendAutoScrollUntilGestureEnds = false
                                showBottomControl()
                                scrollToBottom(force = true)
                            },
                            onLongClick = { toggleForceBottomMode() },
                        ),
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Default.VerticalAlignBottom,
                            contentDescription = if (forceBottomMode) s.forceBottomMode else s.jumpToBottom,
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = !bottomNotice.isNullOrBlank(),
                enter = fadeIn(animationSpec = tween(180)),
                exit = fadeOut(animationSpec = tween(120)),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .offset(y = (-84).dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.92f),
                    contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                    shadowElevation = 6.dp,
                ) {
                    Text(
                        text = bottomNotice.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    )
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
                                viewModel.renameCurrentSession(renameText) {
                                    showRenameDialog = false
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

            if (uiState.showSelectionDialog) {
                ChatSelectionConfigDialog(
                    selection = uiState.selection,
                    onDismiss = viewModel::dismissSelectionDialog,
                    onConfirm = viewModel::confirmSelectionDialog,
                    onAgentSelected = viewModel::updateDraftAgent,
                    onModelSelected = viewModel::updateDraftModel,
                    onVariantSelected = viewModel::updateDraftVariant,
                )
            }

            if (uiState.showContextDialog) {
                val usage = uiState.contextUsageDetail
                val canCompact = !uiState.isContextUsageLoading &&
                    !uiState.isCompactingContext &&
                    !uiState.isSending &&
                    !uiState.isStreaming
                AlertDialog(
                    onDismissRequest = viewModel::closeContextUsageDialog,
                    title = { Text(s.contextUsage) },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (uiState.isContextUsageLoading) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                    Text(s.contextRefreshing, style = MaterialTheme.typography.bodyMedium)
                                }
                            }

                            if (uiState.isCompactingContext) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                    Text(s.contextCompacting, style = MaterialTheme.typography.bodyMedium)
                                }
                            }

                            uiState.contextUsageError?.let {
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }

                            uiState.contextUsageMessage?.let {
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }

                            if (usage != null) {
                                Text(
                                    text = usage.usagePercent?.let { percent -> "${s.contextUsage}: $percent%" }
                                        ?: s.contextUnavailable,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text("${s.contextTokens}: ${usage.totalTokens}")
                                Text("${s.contextInputTokens}: ${usage.breakdown.inputTokens}")
                                Text("${s.contextOutputTokens}: ${usage.breakdown.outputTokens}")
                                Text("${s.contextReasoningTokens}: ${usage.breakdown.reasoningTokens}")
                                Text("${s.contextCacheReadTokens}: ${usage.breakdown.cacheReadTokens}")
                                Text("${s.contextCacheWriteTokens}: ${usage.breakdown.cacheWriteTokens}")
                                usage.modelLimit?.let { limit ->
                                    Text("${s.contextLimit}: $limit")
                                }
                                usage.providerId?.let { providerId ->
                                    Text("${s.contextProvider}: $providerId")
                                }
                                usage.modelId?.let { modelId ->
                                    Text("${s.contextModel}: $modelId")
                                }
                                Text("${s.contextLatestMessageCost}: ${"%.4f".format(usage.latestMessageCost)}")
                                Text("${s.contextCost}: ${"%.4f".format(usage.totalCost)}")
                                Text("${s.contextLatestMessageId}: ${usage.latestMessageId.take(12)}")
                            } else if (!uiState.isContextUsageLoading && uiState.contextUsageError == null) {
                                Text(
                                    text = s.contextUnavailable,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = viewModel::closeContextUsageDialog) {
                            Text(s.close)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = viewModel::compactCurrentSession, enabled = canCompact) {
                            Text(if (uiState.isCompactingContext) s.contextCompacting else s.contextCompactNow)
                        }
                    },
                )
            }
        }
    }
}
