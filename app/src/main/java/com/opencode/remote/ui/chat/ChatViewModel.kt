package com.opencode.remote.ui.chat

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opencode.remote.data.api.dto.*
import com.opencode.remote.data.datastore.ConnectionPreferences
import com.opencode.remote.data.repository.OConnectorRepository
import com.opencode.remote.data.sse.SseEventBus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.roundToInt

/** A single segment in the streaming or completed assistant response. */
data class ResponseSegment(
    val type: String,  // "thinking", "text", "tool"
    val text: String,
    val isStreaming: Boolean = false,  // true if still receiving content
    val id: String? = null,  // callID for tool segments, null for text/thinking — prevents tool calls from overwriting each other
)

data class ContextUsageState(
    val usagePercent: Int?,
    val totalTokens: Int,
    val totalCost: Double,
    val modelLimit: Int?,
)

data class ContextTokenBreakdown(
    val inputTokens: Int,
    val outputTokens: Int,
    val reasoningTokens: Int,
    val cacheReadTokens: Int,
    val cacheWriteTokens: Int,
)

data class ContextUsageDetailState(
    val usagePercent: Int?,
    val totalTokens: Int,
    val totalCost: Double,
    val latestMessageCost: Double,
    val modelLimit: Int?,
    val providerId: String?,
    val modelId: String?,
    val latestMessageId: String,
    val latestCompletedAt: Long?,
    val breakdown: ContextTokenBreakdown,
)

/** Session metadata — changes infrequently (init, session events). */
data class SessionMetaState(
    val sessionId: String = "",
    val sessionDirectory: String? = null,
    val sessionTitle: String? = null,
    val sessionStatus: String? = null,
    val contextUsage: ContextUsageState? = null,
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
    val showContextDialog: Boolean = false,
    val isContextUsageLoading: Boolean = false,
    val isCompactingContext: Boolean = false,
    val contextUsageError: String? = null,
    val contextUsageMessage: String? = null,
    val contextUsageDetail: ContextUsageDetailState? = null,
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
    val contextUsage get() = sessionMeta.contextUsage

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
    val showContextDialog get() = chatDisplay.showContextDialog
    val isContextUsageLoading get() = chatDisplay.isContextUsageLoading
    val isCompactingContext get() = chatDisplay.isCompactingContext
    val contextUsageError get() = chatDisplay.contextUsageError
    val contextUsageMessage get() = chatDisplay.contextUsageMessage
    val contextUsageDetail get() = chatDisplay.contextUsageDetail
}

private data class ResolvedContextUsage(
    val summary: ContextUsageState,
    val detail: ContextUsageDetailState,
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val repository: OConnectorRepository,
    private val prefs: ConnectionPreferences,
    private val sseEventBus: SseEventBus,
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
    private var deltaLogCounter = 0

    companion object {
        private const val TAG = "ChatViewModel"
        private const val MAX_STREAMING_TEXT = 10_000
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

        viewModelScope.launch {
            val savedAgent = prefs.getSelectedAgent(sessionId)
            if (!savedAgent.isNullOrBlank()) {
                _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(selectedAgent = savedAgent)) }
            }
        }

        viewModelScope.launch {
            val draft = prefs.getSessionDraft(sessionId)
            if (!draft.isNullOrBlank()) {
                _uiState.update { current ->
                    if (current.chatDisplay.inputText.isBlank()) {
                        current.copy(chatDisplay = current.chatDisplay.copy(inputText = draft))
                    } else {
                        current
                    }
                }
            }
        }

        // These can run in parallel — they don't affect streaming state
        loadSessionInfo()
        loadTodoList()
        loadAgents()
        loadProviders()

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
                    syncSelectedAgentFromMessages(messages)
                    refreshContextUsage(messages)
                } else {
                    // Turn still in progress (or AI hasn't started responding yet)
                    // Restore full streaming state so the UI shows segments + stop button
                    Log.d(TAG, "Restoring streaming state: segs=${segments.size} pending=${pendingMsgId?.take(8)}")
                    _uiState.update {
                        it.copy(
                            chatDisplay = it.chatDisplay.copy(
                                messages = messages,
                                isLoading = false,
                                selectedAgent = agent ?: it.chatDisplay.selectedAgent,
                            ),
                            streaming = it.streaming.copy(
                                isSending = true,
                                isStreaming = segments.isNotEmpty(),
                                streamingSegments = segments,
                                streamingAgent = agent,
                                pendingAssistantMessageId = pendingMsgId,
                            ),
                        )
                    }
                    persistSelectedAgent(agent)
                }
            } else {
                // No streaming state for this session — normal load
                _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(messages = messages, isLoading = false)) }
                syncSelectedAgentFromMessages(messages)
                refreshContextUsage(messages)
            }

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
                _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(todoItems = todos)) }
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

    private fun loadProviders() {
        viewModelScope.launch {
            try {
                repository.listProviders()
                refreshContextUsage(_uiState.value.messages)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load providers", e)
            }
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
                val segType = if (props.field == "reasoning") "thinking" else "text"
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
                repository.setStreamingBlocks(updated)
                _uiState.update {
                    it.copy(streaming = it.streaming.copy(isStreaming = true, isSending = false, streamingSegments = updated))
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
                    if (!agentName.isNullOrBlank()) {
                        _uiState.update {
                            it.copy(chatDisplay = it.chatDisplay.copy(selectedAgent = agentName))
                        }
                        persistSelectedAgent(agentName)
                    }
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
                    repository.clearStreaming()

                    viewModelScope.launch {
                        try {
                            val messages = repository.getMessages(_uiState.value.sessionId, _uiState.value.sessionDirectory)
                            _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(messages = messages)) }
                            refreshContextUsage(messages)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to reload messages after session.idle", e)
                        }
                        loadTodoList()
                    }
                }
                loadSessionInfo()
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
        persistSessionDraft(text)
    }

    fun sendMessage() {
        val text = _uiState.value.inputText.trim()
        if (text.isEmpty()) return

        // When user hasn't explicitly picked an agent, send null to let the server
        // use its configured default (from opencode.json). DO NOT try to guess via
        // mode=="primary" — all visible agents are mode=subagent, and hidden ones
        // like "build"/"compaction" are mode=primary but are utility agents.
        val agentName = _uiState.value.selectedAgent

        // Clear completed IDs from previous turn — new conversation turn starting
        completedMessageIds.clear()
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
                persistSessionDraft("")
                // prompt_async returns 204 immediately — SSE events drive the rest
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send message", e)
                repository.clearStreaming()
                val s = com.opencode.remote.ui.strings.AppLocale.strings
                _uiState.update {
                    it.copy(
                        streaming = it.streaming.copy(isSending = false, isStreaming = false),
                        chatDisplay = it.chatDisplay.copy(
                            inputText = text,
                            error = s.errSendFailed.replace("%s", e.localizedMessage ?: e.javaClass.simpleName),
                        ),
                    )
                }
                persistSessionDraft(text)
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

    fun selectAgent(agentName: String?) {
        _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(selectedAgent = agentName, showAgentPicker = false)) }
        persistSelectedAgent(agentName)
    }

    fun renameCurrentSession(title: String, onSuccess: (() -> Unit)? = null) {
        viewModelScope.launch {
            val trimmed = title.trim()
            if (trimmed.isEmpty()) return@launch
            try {
                val updated = repository.updateSessionTitle(_uiState.value.sessionId, trimmed, _uiState.value.sessionDirectory)
                _uiState.update {
                    it.copy(
                        sessionMeta = it.sessionMeta.copy(
                            sessionTitle = updated.title ?: updated.slug ?: it.sessionTitle,
                        ),
                    )
                }
                onSuccess?.invoke()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to rename current session", e)
                val s = com.opencode.remote.ui.strings.AppLocale.strings
                _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(error = s.errRenameSession.replace("%s", e.localizedMessage ?: e.javaClass.simpleName))) }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(error = null)) }
    }

    fun openContextUsageDialog() {
        _uiState.update {
            it.copy(
                chatDisplay = it.chatDisplay.copy(
                    showContextDialog = true,
                    isContextUsageLoading = true,
                    isCompactingContext = false,
                    contextUsageError = null,
                    contextUsageMessage = null,
                ),
            )
        }

        viewModelScope.launch {
            try {
                val providers = repository.listProviders()
                val messages = repository.getMessages(_uiState.value.sessionId, _uiState.value.sessionDirectory)
                val resolved = resolveContextUsage(messages, providers)
                _uiState.update {
                    it.copy(
                        sessionMeta = it.sessionMeta.copy(contextUsage = resolved?.summary),
                        chatDisplay = it.chatDisplay.copy(
                            isContextUsageLoading = false,
                            isCompactingContext = false,
                            contextUsageError = null,
                            contextUsageMessage = null,
                            contextUsageDetail = resolved?.detail,
                        ),
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to refresh context usage details", e)
                val s = com.opencode.remote.ui.strings.AppLocale.strings
                _uiState.update {
                    it.copy(
                        chatDisplay = it.chatDisplay.copy(
                            isContextUsageLoading = false,
                            isCompactingContext = false,
                            contextUsageError = s.errLoadMessages.replace("%s", e.localizedMessage ?: e.javaClass.simpleName),
                            contextUsageMessage = null,
                        ),
                    )
                }
            }
        }
    }

    fun compactCurrentSession() {
        val s = com.opencode.remote.ui.strings.AppLocale.strings
        val state = _uiState.value

        if (state.isSending || state.isStreaming) {
            _uiState.update {
                it.copy(chatDisplay = it.chatDisplay.copy(contextUsageError = s.contextCompactWhileStreaming, contextUsageMessage = null))
            }
            return
        }

        viewModelScope.launch {
            try {
                val providers = repository.listProviders()
                val target = resolveCompactionTarget(
                    messages = state.messages,
                    providers = providers,
                    defaults = repository.getCachedProviderDefaults(),
                    connectedProviderIds = repository.getCachedConnectedProviderIds(),
                )
                if (target == null) {
                    _uiState.update {
                        it.copy(chatDisplay = it.chatDisplay.copy(contextUsageError = s.contextCompactUnavailable, contextUsageMessage = null))
                    }
                    return@launch
                }

                _uiState.update {
                    it.copy(
                        chatDisplay = it.chatDisplay.copy(
                            isCompactingContext = true,
                            contextUsageError = null,
                            contextUsageMessage = null,
                        ),
                    )
                }

                repository.summarizeSession(
                    sessionId = state.sessionId,
                    providerID = target.providerId,
                    modelID = target.modelId,
                    directory = state.sessionDirectory,
                )

                loadSessionInfo()
                val messages = repository.getMessages(state.sessionId, state.sessionDirectory)
                val resolved = resolveContextUsage(messages, providers)

                _uiState.update {
                    it.copy(
                        sessionMeta = it.sessionMeta.copy(contextUsage = resolved?.summary),
                        chatDisplay = it.chatDisplay.copy(
                            messages = messages,
                            isCompactingContext = false,
                            contextUsageError = null,
                            contextUsageMessage = s.contextCompacted,
                            contextUsageDetail = resolved?.detail,
                        ),
                    )
                }
                loadTodoList()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to compact session context", e)
                _uiState.update {
                    it.copy(
                        chatDisplay = it.chatDisplay.copy(
                            isCompactingContext = false,
                            contextUsageError = e.localizedMessage ?: s.contextRefreshFailed,
                            contextUsageMessage = null,
                        ),
                    )
                }
            }
        }
    }

    fun closeContextUsageDialog() {
        _uiState.update {
            it.copy(
                chatDisplay = it.chatDisplay.copy(
                    showContextDialog = false,
                    isContextUsageLoading = false,
                    isCompactingContext = false,
                    contextUsageError = null,
                    contextUsageMessage = null,
                ),
            )
        }
    }

    private data class CompactionTarget(
        val providerId: String,
        val modelId: String,
    )

    private fun resolveCompactionTarget(
        messages: List<MessageInfo>,
        providers: List<ProviderInfo>,
        defaults: Map<String, String>,
        connectedProviderIds: List<String>,
    ): CompactionTarget? {
        val recentProviderId = messages.asReversed()
            .firstNotNullOfOrNull { message ->
                if (message.role != "assistant") return@firstNotNullOfOrNull null
                message.info.resolvedProviderID
            }
        val recentModelId = messages.asReversed()
            .firstNotNullOfOrNull { message ->
                if (message.role != "assistant") return@firstNotNullOfOrNull null
                message.info.resolvedModelID
            }

        val provider = providers.firstOrNull { it.id == recentProviderId }
            ?: connectedProviderIds.firstNotNullOfOrNull { connectedId ->
                providers.firstOrNull { it.id == connectedId }
            }
            ?: providers.firstOrNull()
            ?: return null

        val modelId = when {
            recentProviderId == provider.id && !recentModelId.isNullOrBlank() && provider.models.containsKey(recentModelId) -> recentModelId
            defaults[provider.id]?.let(provider.models::containsKey) == true -> defaults[provider.id]
            provider.models.isNotEmpty() -> provider.models.keys.first()
            else -> null
        } ?: return null

        return CompactionTarget(provider.id, modelId)
    }

    private fun refreshContextUsage(messages: List<MessageInfo>) {
        val resolved = resolveContextUsage(messages, repository.getCachedProviders())
        _uiState.update {
            it.copy(
                sessionMeta = it.sessionMeta.copy(contextUsage = resolved?.summary),
                chatDisplay = it.chatDisplay.copy(
                    contextUsageDetail = resolved?.detail ?: it.chatDisplay.contextUsageDetail,
                ),
            )
        }
    }

    private fun resolveContextUsage(messages: List<MessageInfo>, providers: List<ProviderInfo>): ResolvedContextUsage? {
        val latestAssistantWithTokens = messages.asReversed().firstOrNull { message ->
            message.role == "assistant" && message.tokenTotal() > 0
        } ?: return null

        val providerId = latestAssistantWithTokens.info.resolvedProviderID ?: messages.asReversed()
            .firstNotNullOfOrNull { message ->
                if (message.role != "assistant") return@firstNotNullOfOrNull null
                message.info.resolvedProviderID
            }
        val modelId = latestAssistantWithTokens.info.resolvedModelID ?: messages.asReversed()
            .firstNotNullOfOrNull { message ->
                if (message.role != "assistant") return@firstNotNullOfOrNull null
                message.info.resolvedModelID
            }
        val contextLimit = findContextLimit(providers = providers, providerId = providerId, modelId = modelId)
        val totalTokens = latestAssistantWithTokens.tokenTotal()
        val totalCost = messages.asSequence()
            .filter { it.role == "assistant" }
            .sumOf { it.info.cost ?: 0.0 }
        val usagePercent = if (contextLimit != null && contextLimit > 0) {
            ((totalTokens.toDouble() / contextLimit.toDouble()) * 100).roundToInt().coerceIn(0, 999)
        } else {
            null
        }
        val breakdown = latestAssistantWithTokens.tokenBreakdown()

        return ResolvedContextUsage(
            summary = ContextUsageState(
                usagePercent = usagePercent,
                totalTokens = totalTokens,
                totalCost = totalCost,
                modelLimit = contextLimit,
            ),
            detail = ContextUsageDetailState(
                usagePercent = usagePercent,
                totalTokens = totalTokens,
                totalCost = totalCost,
                latestMessageCost = latestAssistantWithTokens.info.cost ?: 0.0,
                modelLimit = contextLimit,
                providerId = providerId,
                modelId = modelId,
                latestMessageId = latestAssistantWithTokens.id,
                latestCompletedAt = latestAssistantWithTokens.info.time?.completed,
                breakdown = breakdown,
            ),
        )
    }

    private fun MessageTokens?.tokenTotal(): Int {
        if (this == null) return 0
        return total ?: (
            (input ?: 0) +
                (output ?: 0) +
                (reasoning ?: 0) +
                (cacheRead ?: cache?.read ?: 0) +
                (cacheWrite ?: cache?.write ?: 0)
            )
    }

    private fun MessageInfo.tokenTotal(): Int {
        val infoTotal = info.tokens.tokenTotal()
        if (infoTotal > 0) return infoTotal
        return parts.sumOf { it.tokens.tokenTotal() }
    }

    private fun MessageTokens?.toBreakdown(): ContextTokenBreakdown {
        if (this == null) {
            return ContextTokenBreakdown(0, 0, 0, 0, 0)
        }
        return ContextTokenBreakdown(
            inputTokens = input ?: 0,
            outputTokens = output ?: 0,
            reasoningTokens = reasoning ?: 0,
            cacheReadTokens = cacheRead ?: cache?.read ?: 0,
            cacheWriteTokens = cacheWrite ?: cache?.write ?: 0,
        )
    }

    private fun MessageInfo.tokenBreakdown(): ContextTokenBreakdown {
        val infoTotal = info.tokens.tokenTotal()
        if (infoTotal > 0) return info.tokens.toBreakdown()
        return parts.fold(ContextTokenBreakdown(0, 0, 0, 0, 0)) { acc, part ->
            val next = part.tokens.toBreakdown()
            ContextTokenBreakdown(
                inputTokens = acc.inputTokens + next.inputTokens,
                outputTokens = acc.outputTokens + next.outputTokens,
                reasoningTokens = acc.reasoningTokens + next.reasoningTokens,
                cacheReadTokens = acc.cacheReadTokens + next.cacheReadTokens,
                cacheWriteTokens = acc.cacheWriteTokens + next.cacheWriteTokens,
            )
        }
    }

    private fun findContextLimit(providers: List<ProviderInfo>, providerId: String?, modelId: String?): Int? {
        if (providerId.isNullOrBlank() || modelId.isNullOrBlank()) return null

        val provider = providers.firstOrNull { it.id == providerId } ?: return null
        return provider.models[modelId]?.limit?.context
            ?: provider.models.values.firstOrNull { model ->
                model.id == modelId || model.name == modelId
            }?.limit?.context
    }

    private fun syncSelectedAgentFromMessages(messages: List<MessageInfo>) {
        val actualAgent = messages.asReversed()
            .firstNotNullOfOrNull { message ->
                if (message.role != "assistant") return@firstNotNullOfOrNull null
                message.info.agent?.takeIf { it.isNotBlank() } ?: message.info.mode?.takeIf { it.isNotBlank() }
            }
        if (!actualAgent.isNullOrBlank()) {
            _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(selectedAgent = actualAgent)) }
            persistSelectedAgent(actualAgent)
        }
    }

    private fun persistSelectedAgent(agentName: String?) {
        val sessionId = _uiState.value.sessionId
        if (sessionId.isBlank()) return
        viewModelScope.launch {
            prefs.saveSelectedAgent(sessionId, agentName)
        }
    }

    private fun persistSessionDraft(draft: String?) {
        val sessionId = _uiState.value.sessionId
        if (sessionId.isBlank()) return
        viewModelScope.launch {
            prefs.saveSessionDraft(sessionId, draft)
        }
    }

    override fun onCleared() {
        super.onCleared()
        sseJob?.cancel()
    }
}
