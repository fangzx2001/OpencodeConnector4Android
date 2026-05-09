package com.opencode.remote.ui.chat

import android.app.NotificationManager
import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opencode.remote.OConnectorApp
import com.opencode.remote.R
import com.opencode.remote.data.api.dto.*
import com.opencode.remote.data.repository.OConnectorRepository
import com.opencode.remote.data.sse.SseEventBus
import com.opencode.remote.ui.strings.AppLocale
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A single segment in the streaming or completed assistant response. */
data class ResponseSegment(
    val type: String,  // "thinking", "text", "tool"
    val text: String,
    val isStreaming: Boolean = false,  // true if still receiving content
    val id: String? = null,  // callID for tool segments, null for text/thinking — prevents tool calls from overwriting each other
)

/** Session metadata — changes infrequently (init, session events). */
data class SessionMetaState(
    val sessionId: String = "",
    val sessionDirectory: String? = null,
    val sessionTitle: String? = null,
    val sessionStatus: String? = null,
)

/** Streaming UI state — changes rapidly during AI response (text deltas, agent info). */
data class StreamingDisplayState(
    val isStreaming: Boolean = false,
    val isSending: Boolean = false,
    val streamingSegments: List<ResponseSegment> = emptyList(),
    val streamingAgent: String? = null,
    val pendingAssistantMessageId: String? = null,
)

/** Chat display state — messages, input, panels, agents, errors. */
data class ChatDisplayState(
    val messages: List<MessageInfo> = emptyList(),
    val inputText: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
    val todoItems: List<TodoItem> = emptyList(),
    val showTodoPanel: Boolean = false,
    val availableAgents: List<AgentInfo> = emptyList(),
    val selectedAgent: String? = null,
    val showAgentPicker: Boolean = false,
    // Panel state
    val isPanelOpen: Boolean = false,
    val panelFiles: List<FileNode> = emptyList(),
    val currentFilePath: String = ".",
    val isLoadingFiles: Boolean = false,
    // Model state
    val selectedModel: ModelInfo? = null,
    val availableModels: List<ModelInfo> = emptyList(),
    // Context state
    val contextUsageK: String = "0K",
)

data class ChatUiState(
    val sessionMeta: SessionMetaState = SessionMetaState(),
    val streaming: StreamingDisplayState = StreamingDisplayState(),
    val chatDisplay: ChatDisplayState = ChatDisplayState(),
) {
    // ── Convenience delegation properties for backward-compatible reads ──
    val sessionId get() = sessionMeta.sessionId
    val sessionDirectory get() = sessionMeta.sessionDirectory
    val sessionTitle get() = sessionMeta.sessionTitle
    val sessionStatus get() = sessionMeta.sessionStatus

    val isStreaming get() = streaming.isStreaming
    val isSending get() = streaming.isSending
    val streamingSegments get() = streaming.streamingSegments
    val streamingAgent get() = streaming.streamingAgent
    val pendingAssistantMessageId get() = streaming.pendingAssistantMessageId

    val messages get() = chatDisplay.messages
    val inputText get() = chatDisplay.inputText
    val isLoading get() = chatDisplay.isLoading
    val error get() = chatDisplay.error
    val todoItems get() = chatDisplay.todoItems
    val showTodoPanel get() = chatDisplay.showTodoPanel
    val availableAgents get() = chatDisplay.availableAgents
    val selectedAgent get() = chatDisplay.selectedAgent
    val showAgentPicker get() = chatDisplay.showAgentPicker
    val isPanelOpen get() = chatDisplay.isPanelOpen
    val panelFiles get() = chatDisplay.panelFiles
    val currentFilePath get() = chatDisplay.currentFilePath
    val isLoadingFiles get() = chatDisplay.isLoadingFiles
    val selectedModel get() = chatDisplay.selectedModel
    val availableModels get() = chatDisplay.availableModels
    val contextUsageK get() = chatDisplay.contextUsageK
}

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val repository: OConnectorRepository,
    private val sseEventBus: SseEventBus,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var sseJob: Job? = null

    /**
     * IDs of messages that already completed — guards against late [message.updated] re-triggering streaming.
     *
     * This is a [mutableSetOf] (not thread-safe) by design:
     * - All reads and writes happen inside [viewModelScope.launch] coroutines,
     *   which run on [Dispatchers.Main] by default.
     * - The set is accessed from [initialize], [sendMessage], and [handleEvent] —
     *   all of which are invoked on the main dispatcher.
     * - Therefore no synchronization (e.g. [ConcurrentHashMap.newKeySet]) is needed.
     */
    private val completedMessageIds = mutableSetOf<String>()
    /** Cache partID → confirmed segType from message.part.updated. Used to correctly classify
     *  subsequent message.part.delta events that may arrive with field=null. */
    private val partTypeMap = mutableMapOf<String?, String>()
    private var deltaLogCounter = 0

    companion object {
        private const val TAG = "ChatViewModel"
        private const val MAX_STREAMING_TEXT = 10_000
        private const val TODO_COMPLETED_NOTIFICATION_ID = 2001
    }

    /**
     * Initialize (or re-initialize) the chat session.
     *
     * CRITICAL ORDERING:
     * 1. Load messages from server (await synchronously)
     * 2. Pre-populate completedMessageIds from loaded messages (prevents stale events)
     * 3. Check if streaming state should be restored vs turn completed while away
     * 4. Subscribe to SSE events AFTER state is fully set (prevents race conditions)
     *
     * This ordering ensures the UI never flashes between empty/streaming/idle states.
     */
    fun initialize(sessionId: String, directory: String? = null) {
        _uiState.update { it.copy(sessionMeta = it.sessionMeta.copy(sessionId = sessionId, sessionDirectory = directory)) }

        // Track active session for notification deep link
        repository.activeSessionId = sessionId
        repository.activeSessionDirectory = directory

        // These can run in parallel — they don't affect streaming state
        loadSessionInfo()
        loadTodoList()
        loadAgents()
        loadModels()

        // Core init: load messages → check state → subscribe to SSE (sequential)
        viewModelScope.launch {
            // Cache sessionId at launch time to detect stale coroutines
            val initSessionId = sessionId

            // ── Step 1: Load messages from server (await synchronously) ──
            val hasData = _uiState.value.messages.isNotEmpty()
            if (!hasData) {
                _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(isLoading = true)) }
            }
            val messages = try {
                repository.getMessages(sessionId, directory)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load messages", e)
                val s = com.opencode.remote.ui.strings.AppLocale.strings
                _uiState.update {
                    it.copy(chatDisplay = it.chatDisplay.copy(isLoading = false, error = s.errLoadMessages.replace("%s", e.localizedMessage ?: e.javaClass.simpleName)))
                }
                subscribeToEvents()
                return@launch
            }

            // Guard: if initialize() was called again with a different sessionId,
            // discard this stale coroutine's results to avoid overwriting new session state.
            if (_uiState.value.sessionId != initSessionId) {
                Log.d(TAG, "Stale initialize for $initSessionId, current is ${_uiState.value.sessionId}, aborting after message load")
                return@launch
            }

            // ── Step 2: Pre-populate completedMessageIds from loaded messages ──
            // This prevents stale message.updated events from re-triggering streaming
            // for messages that already have a completed timestamp from the server.
            completedMessageIds.clear()
            messages.filter { msg ->
                msg.role == "assistant" && msg.info.time?.completed != null && msg.info.time.completed > 0
            }.forEach { msg ->
                completedMessageIds.add(msg.id)
            }

            // Guard: re-check before streaming state restoration — the expensive
            // getStreamingBlocksState() call may have given a stale coroutine enough
            // time for a second initialize() to update the sessionId.
            if (_uiState.value.sessionId != initSessionId) {
                Log.d(TAG, "Stale initialize for $initSessionId, current is ${_uiState.value.sessionId}, aborting before streaming check")
                return@launch
            }

            // ── Step 3: Check if streaming state should be restored ──
            val streamingState = repository.getStreamingBlocksState()
            val streamingSid = streamingState.sessionId
            val segments = streamingState.segments
            val agent = streamingState.agent
            val pendingMsgId = repository.getStreamingPendingMsgId()
            val shouldRestore = streamingSid == sessionId

            if (shouldRestore) {
                // Check if the turn completed while we were away:
                // The loaded messages include the assistant response with completed timestamp.
                val turnCompleted = pendingMsgId != null && messages.any { msg ->
                    msg.role == "assistant" && msg.id == pendingMsgId &&
                        msg.info.time?.completed != null && msg.info.time.completed > 0
                }

                if (turnCompleted) {
                    // Turn finished while user was away — clear stale streaming state
                    Log.d(TAG, "Turn completed while away, clearing streaming state")
                    repository.clearStreaming()
                    _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(messages = messages, isLoading = false)) }
                } else {
                    // Turn still in progress (or AI hasn't started responding yet)
                    // Restore full streaming state so the UI shows segments + stop button
                    Log.d(TAG, "Restoring streaming state: segs=${segments.size} pending=${pendingMsgId?.take(8)}")
                    _uiState.update {
                        it.copy(
                            chatDisplay = it.chatDisplay.copy(messages = messages, isLoading = false),
                            streaming = it.streaming.copy(
                                isSending = true,
                                isStreaming = segments.isNotEmpty(),
                                streamingSegments = segments,
                                streamingAgent = agent,
                                pendingAssistantMessageId = pendingMsgId,
                            ),
                        )
                    }
                }
            } else {
                // No streaming state for this session — normal load
                _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(messages = messages, isLoading = false)) }
            }

            // Compute context usage from loaded messages
            updateContextUsage()

            // ── Step 4: Subscribe to SSE AFTER state is fully restored ──
            // This eliminates race conditions where stale SSE events arrive
            // before streaming state is set, causing premature clearing.
            subscribeToEvents()
        }
    }

    private fun loadSessionInfo() {
        viewModelScope.launch {
            try {
                val session = repository.getSession(_uiState.value.sessionId, _uiState.value.sessionDirectory)
                val isCompleted = session.time?.completed != null && session.time.completed > 0
                // If we don't have directory yet, store it from session info
                val dir = _uiState.value.sessionDirectory ?: session.directory
                _uiState.update {
                    it.copy(
                        sessionMeta = it.sessionMeta.copy(
                            sessionTitle = session.title ?: session.slug ?: "${com.opencode.remote.ui.strings.AppLocale.strings.sessionFallback} ${session.id.take(8)}...",
                            sessionStatus = if (isCompleted) "completed" else "active",
                            sessionDirectory = dir,
                        ),
                    )
                }
            } catch (e: Exception) { Log.w(TAG, "Failed to load session info", e) }
        }
    }

    private fun loadTodoList() {
        viewModelScope.launch {
            try {
                val todos = repository.getTodoList(_uiState.value.sessionId, _uiState.value.sessionDirectory)
                val hadActiveTodos = _uiState.value.todoItems.any { it.status != "completed" && it.status != "cancelled" }
                val activeTodos = todos.filter { it.status != "completed" && it.status != "cancelled" }
                val allDone = todos.isNotEmpty() && activeTodos.isEmpty()

                _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(
                    todoItems = activeTodos,
                    showTodoPanel = if (allDone) false else it.chatDisplay.showTodoPanel,
                ))}

                if (hadActiveTodos && allDone) {
                    showTodoCompletionNotification()
                }
            } catch (e: Exception) { Log.w(TAG, "Failed to load todo list", e) }
        }
    }

    private fun loadAgents() {
        viewModelScope.launch {
            try {
                val agents = repository.listAgents()
                _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(availableAgents = agents)) }
            } catch (e: Exception) { Log.w(TAG, "Failed to load agents", e) }
        }
    }

    private fun subscribeToEvents() {
        sseJob?.cancel()
        sseJob = viewModelScope.launch {
            try {
                sseEventBus.events.collect { event ->
                    handleEvent(event)
                }
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    Log.e(TAG, "SSE event stream error", e)
                    repository.clearStreaming()
                    val s = com.opencode.remote.ui.strings.AppLocale.strings
                    _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(error = s.errStreamInterrupted.replace("%s", e.localizedMessage ?: e.javaClass.simpleName))) }
                }
            }
        }
    }

    private fun handleEvent(event: ServerEvent) {
        val props = event.payload.properties
        val currentSessionId = _uiState.value.sessionId

        // Filter: only process events for current session (or global events without sessionID)
        if (props.sessionID != null && props.sessionID != currentSessionId) return

        Log.d(TAG, "SSE event: ${event.payload.type} session=${props.sessionID?.take(8)} msg=${props.messageID?.take(8)}")

        when (event.payload.type) {
            // ── Streaming text delta (incremental) ──
            "message.part.delta" -> {
                val chunk = props.delta ?: return
                val cachedType = partTypeMap[props.partID]
                val segType = when {
                    cachedType != null -> cachedType
                    props.field == "reasoning" -> "thinking"
                    else -> "text"
                }
                val callId = props.callID
                val segments = _uiState.value.streamingSegments
                val updated = appendToLastSegment(segments, segType, chunk, id = callId)
                repository.setStreamingBlocks(updated)  // stores segments now
                deltaLogCounter++
                if (deltaLogCounter % 50 == 0) {
                    Log.d(TAG, "delta #$deltaLogCounter field=${props.field} type=$segType segs=${updated.size}")
                }
                _uiState.update {
                    it.copy(streaming = it.streaming.copy(isStreaming = true, isSending = false, streamingSegments = updated))
                }
            }

            // ── Part state update (full text so far) ──
            "message.part.updated" -> {
                val part = props.part ?: return
                val partMessageId = part.messageID ?: return
                val pendingId = _uiState.value.pendingAssistantMessageId ?: return
                if (partMessageId != pendingId) return

                // Refresh todo list when todowrite tool completes
                if (part.tool == "todowrite" && part.state?.status == "completed") {
                    loadTodoList()
                }

                val segType = when (part.type) {
                    "reasoning" -> "thinking"
                    "text" -> "text"
                    "tool-invocation", "tool-call", "tool" -> "tool"
                    else -> return
                }

                // For tool parts: use tool name + state info if text is empty
                val partText = if (part.type == "tool" && part.text.isNullOrBlank()) {
                    ToolSummarizer.summarize(part)
                } else {
                    part.text ?: return
                }
                if (partText.isBlank()) return

                val callId = part.callID
                Log.d(TAG, "part.updated type=${part.type} -> segType=$segType callId=${callId?.take(8)} msg=${partMessageId.take(8)}")

                val segments = _uiState.value.streamingSegments
                val updated = putSegment(segments, segType, partText, id = callId)

                // Cache the confirmed type for future deltas on this partID
                partTypeMap[props.partID] = segType

                // Dedup: when "thinking" is confirmed, remove misclassified "text" segments before it
                val deduped = if (segType == "thinking") dedupMisclassifiedText(updated) else updated

                repository.setStreamingBlocks(deduped)
                _uiState.update {
                    it.copy(streaming = it.streaming.copy(isStreaming = true, isSending = false, streamingSegments = deduped))
                }
            }

            // ── Message created/updated ──
            "message.updated" -> {
                val info = props.info ?: return
                if (info.role == "assistant") {
                    val currentPending = _uiState.value.pendingAssistantMessageId
                    if (currentPending == info.id) return
                    if (completedMessageIds.contains(info.id)) {
                        Log.d(TAG, "Skipping late message.updated for completed ${info.id?.take(8)}")
                        return
                    }
                    Log.d(TAG, "New assistant msg: ${info.id?.take(8)} agent=${info.agent} segs=${_uiState.value.streamingSegments.size}")
                    val agentName = info.agent ?: info.mode ?: _uiState.value.streamingAgent
                    repository.setStreamingPendingMsgId(info.id)
                    _uiState.update {
                        it.copy(
                            streaming = it.streaming.copy(
                                isSending = true,
                                streamingAgent = agentName,
                                pendingAssistantMessageId = info.id,
                                // DO NOT clear streamingSegments or change isStreaming
                            ),
                        )
                    }
                }
            }

            // ── Message completed ──
            "message.completed" -> {
                val msgId = props.messageID
                if (msgId != null) completedMessageIds.add(msgId)
                Log.d(TAG, "Message completed: ${msgId?.take(8)} segs=${_uiState.value.streamingSegments.size} streaming=${_uiState.value.isStreaming}")
                viewModelScope.launch { loadTodoList() }
            }

            // ── Session idle = PRIMARY completion signal ──
            "session.idle" -> {
                val state = _uiState.value
                // Only finalize if we're streaming AND the AI has actually started responding
                // (pendingAssistantMessageId set by message.updated). Prevents premature clearing
                // from stale idle events that arrive before the AI begins generating.
                if ((state.isStreaming || state.isSending) && state.pendingAssistantMessageId != null) {
                    val msgId = state.pendingAssistantMessageId
                    if (msgId != null) completedMessageIds.add(msgId)
                    Log.d(TAG, "session.idle — turn complete, clearing streaming")

                    _uiState.update {
                        it.copy(
                            streaming = it.streaming.copy(
                                isStreaming = false,
                                streamingSegments = emptyList(),
                                streamingAgent = null,
                                isSending = false,
                            ),
                        )
                    }
                    deltaLogCounter = 0
                    partTypeMap.clear()
                    repository.clearStreaming()

                    viewModelScope.launch {
                        try {
                            val messages = repository.getMessages(_uiState.value.sessionId, _uiState.value.sessionDirectory)
                            _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(messages = messages)) }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to reload messages after session.idle", e)
                        }
                        loadTodoList()
                        updateContextUsage()
                    }
                }
                loadSessionInfo()
            }

            // ── Session compacted — context reset ──
            "session.compacted" -> {
                Log.d(TAG, "Session compacted — resetting context usage")
                viewModelScope.launch {
                    try {
                        val messages = repository.getMessages(_uiState.value.sessionId, _uiState.value.sessionDirectory)
                        _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(messages = messages)) }
                        updateContextUsage()
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to reload messages after compaction", e)
                    }
                }
            }

            // ── Session events ──
            "session.updated", "session.status" -> {
                loadSessionInfo()
            }

            // ── Todo events ──
            "todo.updated" -> {
                loadTodoList()
            }

            // ── Internal sync (ignore) ──
            "sync" -> { /* silently ignore */ }

            // ── Errors ──
            "session.error" -> {
                val errorMsg = props.error ?: com.opencode.remote.ui.strings.AppLocale.strings.errUnknown
                Log.e(TAG, "Session error: $errorMsg")
                repository.clearStreaming()
                _uiState.update {
                    it.copy(
                        chatDisplay = it.chatDisplay.copy(error = errorMsg),
                        streaming = it.streaming.copy(isSending = false, isStreaming = false, streamingSegments = emptyList()),
                    )
                }
            }
        }
    }

    /** Remove "text" segments that appear before the first "thinking" segment.
     *  These were created by message.part.delta events with field=null that misclassified
     *  reasoning content as "text". Once message.part.updated confirms type="reasoning",
     *  those stale "text" segments should be removed to prevent duplicate display. */
    private fun dedupMisclassifiedText(segments: List<ResponseSegment>): List<ResponseSegment> {
        val firstThinkingIdx = segments.indexOfFirst { it.type == "thinking" }
        if (firstThinkingIdx <= 0) return segments  // No thinking segment, or thinking is already first
        // Remove "text" segments before the first "thinking" — they were misclassified reasoning deltas
        return segments.filterIndexed { idx, seg ->
            idx >= firstThinkingIdx || seg.type != "text"
        }
    }

    /** Append incremental chunk. If last segment has same type AND id, append to it. Otherwise create new segment.
     *  Truncates if accumulated text exceeds limit to prevent OOM. */
    private fun appendToLastSegment(segments: List<ResponseSegment>, type: String, chunk: String, id: String? = null): List<ResponseSegment> {
        if (segments.isNotEmpty() && segments.last().type == type && segments.last().id == id) {
            val combined = segments.last().text + chunk
            val truncated = if (combined.length > MAX_STREAMING_TEXT) {
                combined.substring(0, MAX_STREAMING_TEXT) + "\n\n… [truncated]"
            } else combined
            return segments.dropLast(1) + segments.last().copy(text = truncated)
        }
        // Also truncate if a single chunk exceeds limit (e.g. large tool output)
        val safeChunk = if (chunk.length > MAX_STREAMING_TEXT) {
            chunk.take(MAX_STREAMING_TEXT) + "\n\n… [truncated]"
        } else chunk
        return segments + ResponseSegment(type = type, text = safeChunk, isStreaming = true, id = id)
    }

    /** Update or create a segment with full text (from part.updated). Truncates if text exceeds limit.
     *  Matches by both type AND id to prevent tool calls from overwriting each other. */
    private fun putSegment(segments: List<ResponseSegment>, type: String, fullText: String, id: String? = null): List<ResponseSegment> {
        val truncatedText = if (fullText.length > MAX_STREAMING_TEXT) {
            fullText.take(MAX_STREAMING_TEXT) + "\n\n… [truncated]"
        } else fullText
        val lastIdx = segments.indexOfLast { it.type == type && it.id == id }
        return if (lastIdx >= 0) {
            segments.subList(0, lastIdx) + ResponseSegment(type, truncatedText, isStreaming = true, id = id) + segments.subList(lastIdx + 1, segments.size)
        } else {
            segments + ResponseSegment(type = type, text = truncatedText, isStreaming = true, id = id)
        }
    }

    fun onInputChange(text: String) {
        _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(inputText = text)) }
    }

    fun sendMessage() {
        val text = _uiState.value.inputText.trim()
        if (text.isEmpty()) return

        // When user hasn't explicitly picked an agent, send null to let the server
        // use its configured default (from opencode.json). DO NOT try to guess via
        // mode=="primary" — only show main agents (mode != subagent && !hidden),
        // matching TUI tab-switching behavior.
        val agentName = _uiState.value.selectedAgent

        // Clear completed IDs from previous turn — new conversation turn starting
        completedMessageIds.clear()
        partTypeMap.clear()
        deltaLogCounter = 0

        // 1. Immediately add user message to local list (optimistic update)
        val localUserMsg = MessageInfo(
            info = MessageInfoData(
                id = "local_${System.currentTimeMillis()}",
                role = "user",
            ),
            parts = listOf(MessagePart(type = "text", text = text)),
        )
        _uiState.update {
            it.copy(
                chatDisplay = it.chatDisplay.copy(
                    inputText = "",
                    messages = it.messages + localUserMsg,
                    error = null,
                ),
                streaming = it.streaming.copy(
                    isSending = true,
                    isStreaming = false,
                    streamingSegments = emptyList(),
                    streamingAgent = agentName,
                    pendingAssistantMessageId = null,
                ),
            )
        }

        // 2. Fire-and-forget: send async, don't block UI
        viewModelScope.launch {
            try {
                repository.beginStreaming(_uiState.value.sessionId, agentName)
                repository.sendMessage(_uiState.value.sessionId, text, _uiState.value.selectedAgent, _uiState.value.sessionDirectory)
                // prompt_async returns 204 immediately — SSE events drive the rest
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send message", e)
                repository.clearStreaming()
                val s = com.opencode.remote.ui.strings.AppLocale.strings
                _uiState.update {
                    it.copy(
                        streaming = it.streaming.copy(isSending = false, isStreaming = false),
                        chatDisplay = it.chatDisplay.copy(error = s.errSendFailed.replace("%s", e.localizedMessage ?: e.javaClass.simpleName)),
                    )
                }
            }
        }
    }

    fun abortSession() {
        viewModelScope.launch {
            try {
                repository.abortSession(_uiState.value.sessionId, _uiState.value.sessionDirectory)
                repository.clearStreaming()
                _uiState.update {
                    it.copy(streaming = it.streaming.copy(isSending = false, isStreaming = false, streamingAgent = null, streamingSegments = emptyList()))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to abort session", e)
                val s = com.opencode.remote.ui.strings.AppLocale.strings
                _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(error = s.errAbortFailed.replace("%s", e.localizedMessage ?: e.javaClass.simpleName))) }
            }
        }
    }

    fun toggleTodoPanel() {
        _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(showTodoPanel = !it.chatDisplay.showTodoPanel)) }
    }

    fun toggleAgentPicker() {
        _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(showAgentPicker = !it.chatDisplay.showAgentPicker)) }
    }

    // ── Panel Methods ──────────────────────────────────────────────────

    fun togglePanel() {
        _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(isPanelOpen = !it.chatDisplay.isPanelOpen)) }
        if (!_uiState.value.isPanelOpen) return
        if (_uiState.value.panelFiles.isEmpty()) {
            navigateToDirectory(".")
        }
    }

    fun setPanelOpen(open: Boolean) {
        _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(isPanelOpen = open)) }
        if (open && _uiState.value.panelFiles.isEmpty()) {
            navigateToDirectory(".")
        }
    }

    fun navigateToDirectory(path: String) {
        _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(isLoadingFiles = true, currentFilePath = path)) }
        viewModelScope.launch {
            try {
                val files = repository.listFiles(path, _uiState.value.sessionDirectory)
                _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(panelFiles = files, isLoadingFiles = false)) }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to list files", e)
                _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(isLoadingFiles = false)) }
            }
        }
    }

    fun navigateUp() {
        val current = _uiState.value.currentFilePath
        if (current == "." || current.isEmpty()) return
        val parent = current.substringBeforeLast("/", ".")
        navigateToDirectory(parent)
    }

    fun refreshFiles() {
        navigateToDirectory(_uiState.value.currentFilePath)
    }

    // ── Model Methods ──────────────────────────────────────────────────

    fun loadModels() {
        viewModelScope.launch {
            try {
                repository.listProviders()
                val models = repository.getCachedModels()
                _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(availableModels = models)) }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load models", e)
            }
        }
    }

    fun selectModel(model: ModelInfo) {
        _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(selectedModel = model)) }
    }

    fun syncModelWithAgent(agentName: String?) {
        if (agentName == null) {
            _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(selectedModel = null)) }
            return
        }
        val agents = repository.getCachedAgents()
        val agent = agents.find { it.name == agentName }
        val agentModel = agent?.model
        if (agentModel != null) {
            val matchedModel = _uiState.value.availableModels.find {
                it.id == agentModel.modelID
            }
            _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(selectedModel = matchedModel)) }
        }
    }

    fun updateContextUsage() {
        val messages = _uiState.value.messages
        val totalTokens = messages
            .filter { it.role == "assistant" }
            .sumOf { msg ->
                val input = msg.info.tokens?.input ?: 0
                val cacheRead = msg.info.tokens?.cache?.read ?: 0
                (input + cacheRead).toLong()
            }
        val usageK = if (totalTokens > 0) "${totalTokens / 1000}K" else "0K"
        _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(contextUsageK = usageK)) }
    }

    fun selectAgent(agentName: String?) {
        _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(selectedAgent = agentName, showAgentPicker = false)) }
        syncModelWithAgent(agentName)
    }

    fun clearError() {
        _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(error = null)) }
    }

    private fun showTodoCompletionNotification() {
        val notificationManager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val strings = AppLocale.strings
        val title = _uiState.value.sessionTitle ?: strings.sessionFallback

        val notification = android.app.Notification.Builder(appContext, OConnectorApp.CHANNEL_ID)
            .setContentTitle(strings.todoCompleted)
            .setContentText(strings.todoCompletedDesc.format(title))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setAutoCancel(true)
            .setTimeoutAfter(5000)
            .build()

        notificationManager.notify(TODO_COMPLETED_NOTIFICATION_ID, notification)
    }

    override fun onCleared() {
        super.onCleared()
        sseJob?.cancel()
    }
}
