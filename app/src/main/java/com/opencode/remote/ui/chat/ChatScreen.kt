package com.opencode.remote.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.basicMarquee
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.opencode.remote.ui.components.ErrorSnackbar
import com.opencode.remote.ui.strings.AppLocale
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
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val s = AppLocale.strings
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameText by remember(uiState.sessionTitle) { mutableStateOf(uiState.sessionTitle ?: "") }

    LaunchedEffect(sessionId) {
        viewModel.initialize(sessionId, directory)
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

    var lastTrackedItemCount by remember { mutableIntStateOf(0) }

    // Keep following the bottom only when the user was already on the last item.
    LaunchedEffect(contentRevision) {
        if (totalItems > 0) {
            val lastVisibleIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            val wasAtBottom = lastTrackedItemCount == 0 || lastVisibleIndex >= lastTrackedItemCount - 1
            lastTrackedItemCount = totalItems

            if (wasAtBottom) {
                coroutineScope.launch {
                    listState.scrollToItem(totalItems - 1)
                }
            }
        } else {
            lastTrackedItemCount = 0
        }
    }

    LaunchedEffect(sessionId) {
        lastTrackedItemCount = 0
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
                    }
                    IconButton(onClick = { viewModel.initialize(sessionId, directory) }) {
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
