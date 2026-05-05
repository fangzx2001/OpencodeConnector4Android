package com.opencode.remote.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.opencode.remote.ui.components.ErrorSnackbar
import com.opencode.remote.ui.strings.AppLocale
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

    LaunchedEffect(sessionId) {
        viewModel.initialize(sessionId, directory)
    }

    // Total items = messages + optional streaming panel + optional waiting indicator
    val totalItems = uiState.messages.size +
            (if (uiState.isStreaming && uiState.streamingSegments.isNotEmpty()) 1 else 0) +
            (if (uiState.isSending && uiState.streamingSegments.isEmpty()) 1 else 0)

    // Auto-scroll to bottom when new items appear (not on every text delta — avoids lag)
    LaunchedEffect(totalItems) {
        if (totalItems > 0) {
            coroutineScope.launch {
                listState.scrollToItem(totalItems - 1)
            }
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
