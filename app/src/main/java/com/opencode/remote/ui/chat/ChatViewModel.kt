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
import kotlinx.serialization.Serializable
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A single segment in the streaming or completed assistant response. */
@Serializable
data class ResponseSegment(
    val type: String,  // "thinking", "text", "tool"
    val text: String,
    val isStreaming: Boolean = false,  // true if still receiving content
    val id: String? = null,  // callID for tool segments, null for text/thinking — prevents tool calls from overwriting each other
)

/** Permission request data extracted from permission.asked SSE event. */
@Serializable
data class PermissionRequestData(
    val id: String,
    val sessionID: String,
    val permission: String,
    val patterns: List<String> = emptyList(),
    val always: List<String> = emptyList(),
    val tool: ToolRef? = null,
)

/** Question request data extracted from question.asked SSE event. */
@Serializable
data class QuestionRequestData(
    val id: String,
    val sessionID: String,
    val questions: List<QuestionInfoDto>,
    val tool: ToolRef? = null,
)

/** Session metadata — changes infrequently (init, session events). */
data class SessionMetaState(
    val sessionId: String = "",
    val sessionDirectory: String? = null,
    val sessionTitle: String? = null,
    val sessionStatus: String? = null,
    /** Non-null when session has an active revert (undo) — used to filter messages and show redo button. */
    val revertMessageId: String? = null,
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
    // File preview state
    val expandedFilePath: String? = null,
    val expandedFileContent: String? = null,
    val isLoadingFileContent: Boolean = false,
    // Model state
    val selectedModel: ModelInfo? = null,
    val availableModels: List<ModelInfo> = emptyList(),
    val isLoadingModels: Boolean = false,
    // Context state
    val contextUsageK: String = "0K",
    // Blocking interaction state (permission/question bubbles)
    val pendingPermission: PermissionRequestData? = null,
    val pendingQuestion: QuestionRequestData? = null,
    val isBlocked: Boolean = false,  // true when AI is waiting for user response
    val recoveryPending: Boolean = false,  // true when heuristic detected possible interrupted blocking state
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
    val revertMessageId get() = sessionMeta.revertMessageId

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
    val expandedFilePath get() = chatDisplay.expandedFilePath
    val expandedFileContent get() = chatDisplay.expandedFileContent
    val isLoadingFileContent get() = chatDisplay.isLoadingFileContent
    val selectedModel get() = chatDisplay.selectedModel
    val availableModels get() = chatDisplay.availableModels
    val isLoadingModels get() = chatDisplay.isLoadingModels
    val contextUsageK get() = chatDisplay.contextUsageK
    val pendingPermission get() = chatDisplay.pendingPermission
    val pendingQuestion get() = chatDisplay.pendingQuestion
    val isBlocked get() = chatDisplay.isBlocked
    val recoveryPending get() = chatDisplay.recoveryPending
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
    private var pollingJob: Job? = null
    private var streamingWatchdogJob: Job? = null
    private var blockingWatchdogJob: Job? = null
    private var lastSseEventTime = 0L  // Timestamp of last SSE event — used for fallback polling

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
    private var pendingDeltas = mutableListOf<ServerEvent>()  // accumulated delta events for 16ms batching
    private var batchFlushJob: Job? = null  // debounce job for 16ms coalescing window
    /** Queue of permission requests that arrived while another permission is being handled. */
    private val permissionQueue = mutableListOf<PermissionRequestData>()

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
        pollingJob?.cancel()
        blockingWatchdogJob?.cancel()
        lastSseEventTime = System.currentTimeMillis()
        _uiState.update {
            it.copy(
                sessionMeta = it.sessionMeta.copy(sessionId = sessionId, sessionDirectory = directory),
                // Reset streaming + display state to prevent stale data from previous session
                streaming = StreamingDisplayState(),
                // NOTE: .copy() only overrides listed fields — blocking state is preserved
                // when the same ViewModel instance calls initialize() again. For cross-ViewModel
                // recovery (new NavBackStackEntry), the repository cache is used (Step 5).
                chatDisplay = it.chatDisplay.copy(
                    isLoading = true,
                    contextUsageK = "0K",
                    expandedFilePath = null,
                    expandedFileContent = null,
                    isLoadingFileContent = false,
                    error = null,
                ),
            )
        }
        Log.d(TAG, "initialize() preserving blocking state: permission=${_uiState.value.pendingPermission != null}, question=${_uiState.value.pendingQuestion != null}, blocked=${_uiState.value.isBlocked}")

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

                // Also check: if the last assistant message has completed timestamp but
                // no pendingMsgId was stored (e.g. ViewModel killed before message.updated),
                // the session is idle. Force-clear stale streaming state.
                val lastAssistantCompleted = !turnCompleted && messages.lastOrNull()?.let { msg ->
                    msg.role == "assistant" &&
                        msg.info.time?.completed != null && msg.info.time.completed > 0
                } == true

                if (turnCompleted || lastAssistantCompleted) {
                    // Turn finished while user was away — clear stale streaming state
                    Log.d(TAG, "Turn completed while away, clearing streaming state (turnCompleted=$turnCompleted, lastAssistantCompleted=$lastAssistantCompleted)")
                    batchFlushJob?.cancel()
                    streamingWatchdogJob?.cancel()
                    synchronized(pendingDeltas) { pendingDeltas.clear() }
                    repository.clearStreaming()
                    _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(messages = messages.applyMessageFilters(it.sessionMeta.revertMessageId), isLoading = false)) }
                } else {
                    // Turn still in progress (or AI hasn't started responding yet)
                    // Restore full streaming state so the UI shows segments + stop button
                    Log.d(TAG, "Restoring streaming state: segs=${segments.size} pending=${pendingMsgId?.take(8)}")
                    _uiState.update {
                        it.copy(
                            chatDisplay = it.chatDisplay.copy(messages = messages.applyMessageFilters(it.sessionMeta.revertMessageId), isLoading = false),
                            streaming = it.streaming.copy(
                                isSending = true,
                                isStreaming = segments.isNotEmpty(),
                                streamingSegments = segments,
                                streamingAgent = agent,
                                pendingAssistantMessageId = pendingMsgId,
                            ),
                        )
                    }
                    // Start watchdog for restored streaming — if SSE never delivers
                    // session.idle (server already finished), force-clear after timeout.
                    startStreamingWatchdog()
                }
            } else {
                // No streaming state for this session — normal load
                _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(messages = messages.applyMessageFilters(it.sessionMeta.revertMessageId), isLoading = false)) }
            }

            // Compute context usage from loaded messages
            updateContextUsage()

            // ── Step 4: Subscribe to SSE AFTER state is fully restored ──
            // This eliminates race conditions where stale SSE events arrive
            // before streaming state is set, causing premature clearing.
            subscribeToEvents()

            // ── Step 5: Restore blocking state from cache or message check ──
            // First try to restore from repository cache (survives ViewModel recreation).
            // If no cached state, fall back to checking message completion.
            val cached = repository.getBlockingState(initSessionId)
            val currentDisplay = _uiState.value.chatDisplay
            if (cached != null && !currentDisplay.isBlocked) {
                val perm = cached.permission
                val qst = cached.question
                if (perm != null || qst != null) {
                    Log.d(TAG, "Restoring blocking state from cache: perm=${perm != null} qst=${qst != null}")
                    _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(
                        pendingPermission = perm,
                        pendingQuestion = qst,
                        isBlocked = true,
                    ))}
                    startBlockingWatchdog()
                }
            } else {
                checkSessionBlocking(messages)
            }

            // Start fallback polling (only when SSE stalls)
            startFallbackPolling(initSessionId)
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
                            revertMessageId = session.revert?.messageID,
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
                    lastSseEventTime = System.currentTimeMillis()
                    handleEvent(event)
                }
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    Log.e(TAG, "SSE event stream error", e)
                    batchFlushJob?.cancel()
                    synchronized(pendingDeltas) { pendingDeltas.clear() }
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
        lastSseEventTime = System.currentTimeMillis()

        when (event.payload.type) {
            // ── Streaming text delta (incremental) ──
            "message.part.delta" -> {
                // Accumulate delta events for batch processing (16ms coalescing window)
                synchronized(pendingDeltas) {
                    pendingDeltas.add(event)
                }
                // Start batch flush if not already scheduled
                if (batchFlushJob?.isActive != true) {
                    batchFlushJob = viewModelScope.launch {
                        delay(16)  // Coalesce window: ~1 frame at 60fps
                        flushPendingDeltas()
                    }
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
                    // If this update carries token data, refresh context usage immediately.
                    // This helps when TUI operations cause the server to re-report tokens
                    // or when the user re-enters a session with streaming in progress.
                    if (info.tokens?.tokenTotal() != null && info.tokens.tokenTotal() > 0) {
                        updateContextUsage()
                    }
                } else {
                    // Non-assistant roles (e.g. user) — add to local list incrementally via SSE
                    val msgId = info.id ?: return
                    val currentMessages = _uiState.value.messages
                    // Skip if message already exists (by ID)
                    if (currentMessages.any { it.id == msgId }) return
                    // Also skip optimistic local messages (prefixed with "local_")
                    if (msgId.startsWith("local_")) return
                    Log.d(TAG, "Incremental SSE: adding ${info.role} message ${msgId.take(8)}")
                    // Fetch messages to replace the optimistic local placeholder with real data
                    viewModelScope.launch {
                        try {
                            val allMessages = repository.getMessages(_uiState.value.sessionId, _uiState.value.sessionDirectory)
                            _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(messages = allMessages.applyMessageFilters(it.sessionMeta.revertMessageId))) }
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to fetch messages after user message.updated", e)
                        }
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
                    Log.d(TAG, "session.idle — turn complete, reloading messages")

                    // DO NOT clear streaming state yet — keep streaming segments visible
                    // until we've confirmed the replacement message is in the list.
                    // This prevents the "response disappears" flash.
                    viewModelScope.launch {
                        try {
                            // Load messages with retry: the server may not have fully
                            // persisted the assistant message when session.idle fires.
                            var freshMessages = repository.getMessages(state.sessionId, state.sessionDirectory)
                            val expectedId = state.pendingAssistantMessageId
                            if (expectedId != null) {
                                var attempts = 0
                                while (attempts < 3 && !freshMessages.any { it.id == expectedId }) {
                                    attempts++
                                    Log.d(TAG, "session.idle: assistant msg not found, retry $attempts/3")
                                    delay(300L * attempts)
                                    freshMessages = repository.getMessages(state.sessionId, state.sessionDirectory)
                                }
                                // Final check: does the assistant message have actual content?
                                val assistantMsg = freshMessages.find { it.id == expectedId }
                                val hasContent = assistantMsg != null && assistantMsg.parts.any { p ->
                                    p.type in listOf("text", "reasoning") && !p.text.isNullOrBlank()
                                }
                                if (!hasContent) {
                                    Log.w(TAG, "session.idle: assistant message still has no content after retries, keeping streaming visible")
                                    // Update messages but do NOT clear streaming — fallback polling
                                    // or next idle event will handle it.
                                    _uiState.update {
                                        it.copy(chatDisplay = it.chatDisplay.copy(
                                            messages = freshMessages.applyMessageFilters(it.sessionMeta.revertMessageId),
                                        ))
                                    }
                                    return@launch
                                }
                            }

                            // Content confirmed — now safe to clear everything atomically.
                            deltaLogCounter = 0
                            partTypeMap.clear()
                            batchFlushJob?.cancel()
                            streamingWatchdogJob?.cancel()
                            synchronized(pendingDeltas) { pendingDeltas.clear() }
                            repository.clearStreaming()

                            _uiState.update {
                                it.copy(
                                    chatDisplay = it.chatDisplay.copy(messages = freshMessages.applyMessageFilters(it.sessionMeta.revertMessageId)),
                                    streaming = it.streaming.copy(
                                        isStreaming = false,
                                        streamingSegments = emptyList(),
                                        streamingAgent = null,
                                        isSending = false,
                                    ),
                                )
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to reload messages after session.idle", e)
                            // On network error, still clear streaming to un-stuck the UI
                            deltaLogCounter = 0
                            partTypeMap.clear()
                            batchFlushJob?.cancel()
                            streamingWatchdogJob?.cancel()
                            synchronized(pendingDeltas) { pendingDeltas.clear() }
                            repository.clearStreaming()
                            _uiState.update {
                                it.copy(streaming = it.streaming.copy(
                                    isStreaming = false,
                                    streamingSegments = emptyList(),
                                    streamingAgent = null,
                                    isSending = false,
                                ))
                            }
                        }
                        loadTodoList()
                        updateContextUsage()
                    }
                }
                // Always reload session info on idle — TUI may have caused state changes
                // (undo/redo, compaction, etc.) that we need to reflect.
                loadSessionInfo()
                // If we weren't streaming (TUI drove the conversation), still refresh
                // messages + context since TUI activity changed the message list.
                if (!_uiState.value.isStreaming && !_uiState.value.isSending) {
                    viewModelScope.launch {
                        try {
                            val fresh = repository.getMessages(_uiState.value.sessionId, _uiState.value.sessionDirectory)
                            _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(messages = fresh.applyMessageFilters(it.sessionMeta.revertMessageId))) }
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to refresh messages on idle", e)
                        }
                        updateContextUsage()
                    }
                }
                // session.idle means the AI turn is complete — clear blocking state
                // ONLY when this idle corresponds to the current streaming turn.
                // Do NOT clear blocking state during reconnection (isStreaming=false, isSending=false)
                // because the session may still be waiting for question/permission answer.
                val blockedState = _uiState.value.chatDisplay
                if ((state.isStreaming || state.isSending) &&
                    (blockedState.pendingPermission != null || blockedState.pendingQuestion != null || blockedState.isBlocked)) {
                    Log.d(TAG, "session.idle — clearing blocking state after streaming turn completed")
                    clearBlockingState()
                    permissionQueue.clear()
                }
            }

            // ── Session compacted — context reset ──
            "session.compacted" -> {
                Log.d(TAG, "Session compacted — resetting context usage")
                viewModelScope.launch {
                    try {
                        val messages = repository.getMessages(_uiState.value.sessionId, _uiState.value.sessionDirectory)
                        _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(messages = messages.applyMessageFilters(it.sessionMeta.revertMessageId))) }
                        updateContextUsage()
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to reload messages after compaction", e)
                    }
                }
            }

            // ── Session events ──
            "session.updated" -> {
                loadSessionInfo()
            }

            // ── Session status (busy/idle transition) ──
            // NOTE: The server emits session.status { type: "idle" } AND session.idle
            // at the same time. We handle the completion logic ONLY in session.idle
            // to avoid duplicate message reloads racing against each other.
            // session.status is used only for metadata refresh.
            "session.status" -> {
                loadSessionInfo()
            }

            // ── Todo events ──
            "todo.updated" -> {
                loadTodoList()
            }

            // ── Permission asked (tool confirmation) ──
            "permission.asked" -> {
                val requestId = props.id ?: return
                val permType = props.permission ?: return
                val sessionId = props.sessionID ?: currentSessionId
                Log.d(TAG, "Permission asked: id=$requestId perm=$permType patterns=${props.patterns}")
                val request = PermissionRequestData(
                    id = requestId,
                    sessionID = sessionId,
                    permission = permType,
                    patterns = props.patterns ?: emptyList(),
                    always = props.always ?: emptyList(),
                    tool = props.tool,
                )
                if (_uiState.value.pendingPermission != null) {
                    // Queue the request — current one is still being handled by user
                    permissionQueue.add(request)
                    Log.d(TAG, "Permission queued: ${request.id}, queue size: ${permissionQueue.size}")
                } else {
                    _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(
                        pendingPermission = request, isBlocked = true,
                    ))}
                    repository.saveBlockingState(sessionId, request, _uiState.value.pendingQuestion)
                    startBlockingWatchdog()
                }
            }

            // ── Question asked (AI question) ──
            "question.asked" -> {
                val requestId = props.id ?: return
                val questions = props.questions ?: return
                val sessionId = props.sessionID ?: currentSessionId
                if (questions.isEmpty()) return
                Log.d(TAG, "Question asked: id=$requestId questions=${questions.size}")
                val request = QuestionRequestData(
                    id = requestId,
                    sessionID = sessionId,
                    questions = questions,
                    tool = props.tool,
                )
                _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(
                    pendingQuestion = request, isBlocked = true,
                ))}
                repository.saveBlockingState(currentSessionId, _uiState.value.pendingPermission, request)
                startBlockingWatchdog()
            }

            // ── Internal sync (ignore) ──
            "sync" -> { /* silently ignore */ }

            // ── Errors ──
            "session.error" -> {
                val errorMsg = props.error ?: com.opencode.remote.ui.strings.AppLocale.strings.errUnknown
                Log.e(TAG, "Session error: $errorMsg")
                batchFlushJob?.cancel()
                synchronized(pendingDeltas) { pendingDeltas.clear() }
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

    /** Flush all accumulated delta events in a single batch UI update. */
    private fun flushPendingDeltas() {
        val batch: List<ServerEvent>
        synchronized(pendingDeltas) {
            batch = pendingDeltas.toList()
            pendingDeltas.clear()
        }
        if (batch.isEmpty()) return

        // Process all accumulated deltas in a single UI update
        for (deltaEvent in batch) {
            val p = deltaEvent.payload.properties
            val chunk = p.delta ?: continue
            val cachedType = partTypeMap[p.partID]
            val segType = when {
                cachedType != null -> cachedType
                p.field == "reasoning" -> "thinking"
                else -> "text"
            }
            val callId = p.callID
            val segments = _uiState.value.streamingSegments
            val updated = appendToLastSegment(segments, segType, chunk, id = callId)
            repository.setStreamingBlocks(updated)
            deltaLogCounter++
            if (deltaLogCounter % 50 == 0) {
                Log.d(TAG, "delta #$deltaLogCounter field=${p.field} type=$segType segs=${updated.size}")
            }
            _uiState.update {
                it.copy(streaming = it.streaming.copy(isStreaming = true, isSending = false, streamingSegments = updated))
            }
        }
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
        // Allow sending during recoveryPending — user is resuming an interrupted conversation
        if (_uiState.value.isBlocked && !_uiState.value.recoveryPending) return

        val state = _uiState.value

        // When user hasn't explicitly picked an agent, send null to let the server
        // use its configured default (from opencode.json). DO NOT try to guess via
        // mode=="primary" — only show main agents (mode != subagent && !hidden),
        // matching TUI tab-switching behavior.
        val agentName = _uiState.value.selectedAgent

        // If stuck in stale streaming state (e.g. app killed during generation,
        // missed session.idle), abort the server-side generation and clear local state
        // before sending a new message. The server coalesces concurrent prompt_async
        // calls, so a stale session would swallow our new prompt without feedback.
        if (state.isStreaming || state.isSending) {
            Log.w(TAG, "sendMessage() while streaming — aborting stale state before new send")
            viewModelScope.launch {
                try { repository.abortSession(state.sessionId, state.sessionDirectory) } catch (_: Exception) {}
            }
            batchFlushJob?.cancel()
            synchronized(pendingDeltas) { pendingDeltas.clear() }
            repository.clearStreaming()
            streamingWatchdogJob?.cancel()
        }

        // Clear completed IDs from previous turn — new conversation turn starting
        completedMessageIds.clear()
        partTypeMap.clear()
        deltaLogCounter = 0
        // Clear recovery state if present — user is sending a new message to resume
        if (_uiState.value.recoveryPending) {
            blockingWatchdogJob?.cancel()
            _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(
                isBlocked = false, recoveryPending = false,
            ))}
        }

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
                val model = _uiState.value.selectedModel
                repository.sendMessage(
                    _uiState.value.sessionId, text, _uiState.value.selectedAgent,
                    model?.providerID, model?.id,
                    _uiState.value.sessionDirectory,
                )
                // prompt_async returns 204 immediately — SSE events drive the rest
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send message", e)
                batchFlushJob?.cancel()
                synchronized(pendingDeltas) { pendingDeltas.clear() }
                repository.clearStreaming()
                streamingWatchdogJob?.cancel()
                val s = com.opencode.remote.ui.strings.AppLocale.strings
                _uiState.update {
                    it.copy(
                        streaming = it.streaming.copy(isSending = false, isStreaming = false),
                        chatDisplay = it.chatDisplay.copy(error = s.errSendFailed.replace("%s", e.localizedMessage ?: e.javaClass.simpleName)),
                    )
                }
            }
        }

        // Start timeout watchdog — if no SSE events arrive within 120s, assume
        // the server is stuck (missed session.idle) and force-clear streaming state.
        startStreamingWatchdog()
    }

    fun abortSession() {
        viewModelScope.launch {
            try {
                repository.abortSession(_uiState.value.sessionId, _uiState.value.sessionDirectory)
                batchFlushJob?.cancel()
                synchronized(pendingDeltas) { pendingDeltas.clear() }
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

    /**
     * Undo the last user message.
     * 1. Abort any in-progress generation
     * 2. Find last user message
     * 3. Call POST /session/{id}/revert with that messageID
     * 4. Reload messages (server soft-hides reverted messages)
     * 5. Restore the undone text to the input box
     */
    fun undoLastMessage() {
        viewModelScope.launch {
            try {
                val state = _uiState.value

                // 1. Abort if streaming
                if (state.isStreaming || state.isSending) {
                    try { repository.abortSession(state.sessionId, state.sessionDirectory) } catch (_: Exception) {}
                    batchFlushJob?.cancel()
                    synchronized(pendingDeltas) { pendingDeltas.clear() }
                    repository.clearStreaming()
                    _uiState.update {
                        it.copy(streaming = it.streaming.copy(isSending = false, isStreaming = false, streamingAgent = null, streamingSegments = emptyList()))
                    }
                }

                // 2. Find last user message (filter out already-reverted ones)
                val revertId = state.revertMessageId
                val messages = state.messages
                val lastUserMsg = messages
                    .filter { it.role == "user" }
                    .filter { revertId == null || it.id < revertId }
                    .lastOrNull()
                if (lastUserMsg == null) {
                    Log.w(TAG, "No user message to undo")
                    return@launch
                }

                // 3. Call revert
                val updatedSession = repository.revertSession(state.sessionId, lastUserMsg.id, state.sessionDirectory)

                // 4. Extract the user's text to restore into input
                val userText = lastUserMsg.parts
                    .filter { it.type == "text" }
                    .mapNotNull { it.text }
                    .joinToString("\n")
                    .ifBlank { lastUserMsg.parts.firstOrNull()?.text ?: "" }

                // 5. Reload messages (server filters by revert marker)
                val freshMessages = repository.getMessages(state.sessionId, state.sessionDirectory)

                _uiState.update {
                    it.copy(
                        sessionMeta = it.sessionMeta.copy(
                            revertMessageId = updatedSession.revert?.messageID,
                        ),
                        chatDisplay = it.chatDisplay.copy(
                            messages = freshMessages.filterReverted(updatedSession.revert?.messageID).trimToLatest(),
                            inputText = userText,
                        ),
                    )
                }
                Log.d(TAG, "Undo: reverted message ${lastUserMsg.id.take(8)}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to undo message", e)
                val s = com.opencode.remote.ui.strings.AppLocale.strings
                _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(error = s.errUndoFailed.replace("%s", e.localizedMessage ?: e.javaClass.simpleName))) }
            }
        }
    }

    /**
     * Redo — restore all reverted messages.
     * Calls POST /session/{id}/unrevert and reloads.
     */
    fun redoLastUndo() {
        viewModelScope.launch {
            try {
                val state = _uiState.value
                if (state.revertMessageId == null) return@launch

                val updatedSession = repository.unrevertSession(state.sessionId, state.sessionDirectory)
                val freshMessages = repository.getMessages(state.sessionId, state.sessionDirectory)

                _uiState.update {
                    it.copy(
                        sessionMeta = it.sessionMeta.copy(
                            revertMessageId = updatedSession.revert?.messageID,
                        ),
                        chatDisplay = it.chatDisplay.copy(
                            messages = freshMessages.filterReverted(updatedSession.revert?.messageID).trimToLatest(),
                            inputText = "",
                        ),
                    )
                }
                Log.d(TAG, "Redo: restored reverted messages")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to redo", e)
                val s = com.opencode.remote.ui.strings.AppLocale.strings
                _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(error = s.errRedoFailed.replace("%s", e.localizedMessage ?: e.javaClass.simpleName))) }
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
        if (open) {
            if (_uiState.value.panelFiles.isEmpty()) {
                navigateToDirectory(".")
            }
            if (_uiState.value.availableModels.isEmpty()) {
                loadModels()
            }
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

    fun toggleFilePreview(file: FileNode) {
        val current = _uiState.value.expandedFilePath
        if (current == file.path) {
            // Collapse
            _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(
                expandedFilePath = null,
                expandedFileContent = null,
            )) }
        } else {
            // Expand — load content
            _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(
                expandedFilePath = file.path,
                expandedFileContent = null,
                isLoadingFileContent = true,
            )) }
            viewModelScope.launch {
                try {
                    val result = repository.readFileContent(file.path, _uiState.value.sessionDirectory)
                    _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(
                        expandedFileContent = result.content,
                        isLoadingFileContent = false,
                    )) }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to read file: ${file.path}", e)
                    _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(
                        expandedFileContent = "Error: ${e.message}",
                        isLoadingFileContent = false,
                    )) }
                }
            }
        }
    }

    fun refreshFiles() {
        navigateToDirectory(_uiState.value.currentFilePath)
    }

    // ── Model Methods ──────────────────────────────────────────────────

    fun loadModels() {
        if (_uiState.value.isLoadingModels) return  // already loading
        _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(isLoadingModels = true)) }
        viewModelScope.launch {
            try {
                repository.listProviders()
                val models = repository.getCachedModels()
                _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(availableModels = models, isLoadingModels = false)) }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load models", e)
                _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(isLoadingModels = false)) }
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
        // Walk backwards to find the last assistant message WITH actual token data.
        // This handles edge cases where the most recent assistant message has no tokens
        // (e.g., TUI triggered operations, provider didn't report usage, etc.)
        val messages = _uiState.value.messages
        val lastAssistant = messages.lastOrNull { it.role == "assistant" }
        val tokenCount = lastAssistant?.let { msg ->
            val infoTotal = msg.info.tokens?.tokenTotal() ?: 0
            if (infoTotal > 0) infoTotal
            else msg.parts.sumOf { it.tokens?.tokenTotal() ?: 0 }
        } ?: 0

        // If the last assistant has zero tokens, walk backwards to find one with data
        val resolvedCount = if (tokenCount > 0) tokenCount else {
            var found = 0
            for (msg in messages.reversed()) {
                if (msg.role != "assistant") continue
                val total = msg.info.tokens?.tokenTotal() ?: 0
                val partsTotal = if (total > 0) total else msg.parts.sumOf { it.tokens?.tokenTotal() ?: 0 }
                if (partsTotal > 0) {
                    found = partsTotal
                    break
                }
            }
            found
        }

        val usageK = if (resolvedCount > 0) "${resolvedCount / 1000}K" else "0K"
        _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(contextUsageK = usageK)) }
    }

    fun selectAgent(agentName: String?) {
        _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(selectedAgent = agentName, showAgentPicker = false)) }
        syncModelWithAgent(agentName)
    }

    fun clearError() {
        _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(error = null)) }
    }

    // ── Permission / Question Reply Methods ────────────────────────────

    fun replyPermission(reply: String, message: String? = null) {
        val request = _uiState.value.pendingPermission ?: return
        viewModelScope.launch {
            try {
                repository.replyPermission(request.id, reply, message, _uiState.value.sessionDirectory)
                Log.d(TAG, "Permission replied: $reply for ${request.id}")
                advancePermission()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to reply permission", e)
                val s = com.opencode.remote.ui.strings.AppLocale.strings
                _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(
                    error = s.errSendFailed.replace("%s", e.localizedMessage ?: e.javaClass.simpleName),
                ))}
            }
        }
    }

    fun replyQuestion(answers: List<List<String>>) {
        val request = _uiState.value.pendingQuestion ?: return
        viewModelScope.launch {
            try {
                repository.replyQuestion(request.id, answers, _uiState.value.sessionDirectory)
                Log.d(TAG, "Question replied for ${request.id}")
                clearBlockingState()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to reply question", e)
                val s = com.opencode.remote.ui.strings.AppLocale.strings
                _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(
                    error = s.errSendFailed.replace("%s", e.localizedMessage ?: e.javaClass.simpleName),
                ))}
            }
        }
    }

    fun rejectQuestion() {
        val request = _uiState.value.pendingQuestion ?: return
        viewModelScope.launch {
            try {
                repository.rejectQuestion(request.id, _uiState.value.sessionDirectory)
                Log.d(TAG, "Question rejected for ${request.id}")
                clearBlockingState()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to reject question", e)
                val s = com.opencode.remote.ui.strings.AppLocale.strings
                _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(
                    error = s.errSendFailed.replace("%s", e.localizedMessage ?: e.javaClass.simpleName),
                ))}
            }
        }
    }

    /** Advance to the next queued permission, or clear blocked state if queue is empty. */
    private fun advancePermission() {
        if (permissionQueue.isNotEmpty()) {
            val next = permissionQueue.removeAt(0)
            Log.d(TAG, "Advancing to queued permission: ${next.id}, remaining: ${permissionQueue.size}")
            _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(
                pendingPermission = next, isBlocked = true,
            ))}
            repository.saveBlockingState(_uiState.value.sessionId, next, _uiState.value.pendingQuestion)
            startBlockingWatchdog()  // Start timeout for queued permission
        } else {
            blockingWatchdogJob?.cancel()  // Cancel orphaned watchdog
            repository.clearBlockingState(_uiState.value.sessionId)
            _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(
                pendingPermission = null, isBlocked = false,
            ))}
        }
    }

    private fun clearBlockingState() {
        blockingWatchdogJob?.cancel()
        repository.clearBlockingState(_uiState.value.sessionId)
        _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(
            pendingPermission = null, pendingQuestion = null, isBlocked = false, recoveryPending = false,
        ))}
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

    /** Trim message list to the latest [maxCount] messages to prevent memory bloat on long conversations. */
    private fun List<MessageInfo>.trimToLatest(maxCount: Int = 150): List<MessageInfo> {
        return if (size > maxCount) takeLast(maxCount) else this
    }

    /** Filter out messages at or after the revert point (undo hides them). */
    private fun List<MessageInfo>.filterReverted(revertMessageId: String?): List<MessageInfo> {
        if (revertMessageId == null) return this
        return filter { it.id < revertMessageId }
    }

    /** Apply both revert filtering and trim in one call. */
    private fun List<MessageInfo>.applyMessageFilters(revertMessageId: String?): List<MessageInfo> =
        filterReverted(revertMessageId).trimToLatest()

    /**
     * Fallback polling: only checks for new messages when SSE appears to have stalled
     * (no events received for 15 seconds). This avoids redundant API calls while SSE
     * is actively streaming, reducing server load and battery usage.
     */
    private fun startFallbackPolling(expectedSessionId: String) {
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            while (isActive) {
                delay(5000)  // Check every 5 seconds
                if (_uiState.value.sessionId != expectedSessionId) break
                if (_uiState.value.isStreaming) continue  // Still streaming via SSE, skip

                // Only poll if no SSE events received in last 15 seconds
                val timeSinceLastEvent = System.currentTimeMillis() - lastSseEventTime
                if (timeSinceLastEvent < 15_000) continue

                try {
                    val freshMessages = repository.getMessages(
                        _uiState.value.sessionId,
                        _uiState.value.sessionDirectory,
                        limit = 5,
                    )
                    val currentMessages = _uiState.value.messages
                    val currentLatestId = currentMessages.lastOrNull()?.id
                    val freshLatestId = freshMessages.lastOrNull()?.id
                    if (freshLatestId != null && freshLatestId != currentLatestId) {
                        Log.d(TAG, "Fallback polling detected new message: $freshLatestId")
                        val fullMessages = repository.getMessages(_uiState.value.sessionId, _uiState.value.sessionDirectory)
                        _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(messages = fullMessages.applyMessageFilters(it.sessionMeta.revertMessageId))) }
                        updateContextUsage()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Fallback polling error", e)
                }
            }
        }
    }

    /**
     * Watchdog that force-clears streaming state if no SSE events arrive for 120s.
     * Prevents permanent UI freeze when session.idle is missed (e.g. app killed
     * during generation, SSE reconnection gap, server crash).
     */
    private fun startStreamingWatchdog() {
        streamingWatchdogJob?.cancel()
        streamingWatchdogJob = viewModelScope.launch {
            while (isActive) {
                delay(15_000)  // Check every 15s
                val state = _uiState.value
                if (!state.isStreaming && !state.isSending) {
                    // Not streaming — watchdog not needed
                    break
                }
                // Reset if any SSE event arrived recently
                val timeSinceEvent = System.currentTimeMillis() - lastSseEventTime
                if (timeSinceEvent < 120_000) continue  // SSE is still active

                // 120s with no SSE events while stuck in sending/streaming — force clear
                Log.w(TAG, "Streaming watchdog: no SSE events for 120s, force-clearing stuck state")
                try { repository.abortSession(state.sessionId, state.sessionDirectory) } catch (_: Exception) {}
                batchFlushJob?.cancel()
                synchronized(pendingDeltas) { pendingDeltas.clear() }
                repository.clearStreaming()
                // Reload messages to get the latest state from server
                try {
                    val fresh = repository.getMessages(state.sessionId, state.sessionDirectory)
                    _uiState.update {
                        it.copy(
                            streaming = it.streaming.copy(
                                isStreaming = false,
                                isSending = false,
                                streamingSegments = emptyList(),
                                streamingAgent = null,
                                pendingAssistantMessageId = null,
                            ),
                            chatDisplay = it.chatDisplay.copy(
                                messages = fresh.applyMessageFilters(it.sessionMeta.revertMessageId),
                            ),
                        )
                    }
                } catch (e: Exception) {
                    // Even reload failed — just clear streaming state
                    _uiState.update {
                        it.copy(streaming = it.streaming.copy(
                            isStreaming = false, isSending = false,
                            streamingSegments = emptyList(), streamingAgent = null,
                            pendingAssistantMessageId = null,
                        ))
                    }
                }
                updateContextUsage()
                break
            }
        }
    }

    /**
     * Watchdog that auto-clears stale blocking state after 120 seconds.
     * If the AI is waiting for permission/question reply but no response is given
     * within 120s, the server has likely timed out and the blocking state is stale.
     */
    private fun startBlockingWatchdog() {
        blockingWatchdogJob?.cancel()
        blockingWatchdogJob = viewModelScope.launch {
            delay(120_000)  // 120 seconds
            val state = _uiState.value.chatDisplay
            if (state.pendingPermission != null || state.pendingQuestion != null || state.isBlocked) {
                Log.w(TAG, "Blocking watchdog: stale state after 120s, auto-clearing")
                clearBlockingState()
                permissionQueue.clear()
                // Notify user via Toast
                android.widget.Toast.makeText(
                    appContext,
                    com.opencode.remote.ui.strings.AppLocale.strings.blockingStateExpired,
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /**
     * Immediate recovery check: runs right after message loading in initialize(). Detects if the AI was interrupted
     * mid-work by checking if the last assistant message has no completed timestamp. This covers question, permission,
     * and any other blocking state uniformly. No delay — runs synchronously.
     */
    private fun checkSessionBlocking(messages: List<MessageInfo>) {
        val state = _uiState.value.chatDisplay
        // If session is actively streaming, don't trigger recovery
        val streaming = _uiState.value.streaming
        if (streaming.isStreaming || streaming.isSending) {
            Log.d(TAG, "checkSessionBlocking: session is streaming, skipping heuristic")
            return
        }
        // If recovery already pending from a previous check, skip redundant heuristic
        if (state.recoveryPending) {
            Log.d(TAG, "checkSessionBlocking: recovery already pending, skipping heuristic")
            return
        }
        // If blocking state is already set (preserved from T1 or SSE re-delivery), skip heuristic
        if (state.pendingPermission != null || state.pendingQuestion != null) {
            Log.d(TAG, "checkSessionBlocking: blocking state already set, skipping heuristic")
            return
        }

        // Find the last assistant message
        val lastAssistant = messages.lastOrNull { it.role == "assistant" } ?: return
        val hasCompletedTimestamp = lastAssistant.info.time?.completed != null && lastAssistant.info.time.completed > 0

        if (hasCompletedTimestamp) {
            // Last assistant message completed normally — not blocked
            Log.d(TAG, "checkSessionBlocking: last assistant completed, session not blocked")
            return
        }

        // Last assistant has no completed timestamp = AI was interrupted mid-work
        // (question or permission or any blocking state — all show as incomplete message)
        Log.d(TAG, "checkSessionBlocking: last assistant incomplete (no completed timestamp), triggering recovery")
        _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(
            isBlocked = true,
            recoveryPending = true,
        ))}
        startBlockingWatchdog()
    }

    /** Dismiss recovery/heuristic blocking state — user chose to ignore. */
    fun dismissBlocking() {
        blockingWatchdogJob?.cancel()
        permissionQueue.clear()
        repository.clearBlockingState(_uiState.value.sessionId)
        _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(
            pendingPermission = null, pendingQuestion = null, isBlocked = false, recoveryPending = false,
        ))}
    }

    /**
     * Re-check blocking state when the user taps "Check Status" on the RecoveryBubble.
     * This clears the heuristic-only state and tries to recover real data from
     * cache (memory + disk), falling back to a fresh server message check.
     */
    fun recheckBlockingState() {
        val sessionId = _uiState.value.sessionId
        if (sessionId.isBlank()) return

        // Clear current recovery-only state so guards don't block re-check
        _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(
            pendingPermission = null, pendingQuestion = null, isBlocked = false, recoveryPending = false,
        ))}
        blockingWatchdogJob?.cancel()

        // Step 1: Try cache (memory → disk)
        val cached = repository.getBlockingState(sessionId)
        if (cached != null && (cached.permission != null || cached.question != null)) {
            Log.d(TAG, "recheckBlockingState: restored from cache perm=${cached.permission != null} qst=${cached.question != null}")
            _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(
                pendingPermission = cached.permission,
                pendingQuestion = cached.question,
                isBlocked = true,
            ))}
            startBlockingWatchdog()
            return
        }

        // Step 2: Load messages from server and run heuristic
        viewModelScope.launch {
            try {
                val messages = repository.getMessages(sessionId, _uiState.value.sessionDirectory)
                Log.d(TAG, "recheckBlockingState: loaded ${messages.size} messages, running heuristic")
                checkSessionBlocking(messages)
            } catch (e: Exception) {
                Log.e(TAG, "recheckBlockingState: failed to load messages", e)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        batchFlushJob?.cancel()
        pollingJob?.cancel()
        sseJob?.cancel()
        streamingWatchdogJob?.cancel()
        blockingWatchdogJob?.cancel()
    }
}
