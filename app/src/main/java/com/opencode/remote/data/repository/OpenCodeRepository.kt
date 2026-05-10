package com.opencode.remote.data.repository

import android.content.Context
import com.opencode.remote.data.api.OConnectorApiClient
import com.opencode.remote.data.api.OConnectorSseClient
import com.opencode.remote.data.api.dto.*
import com.opencode.remote.data.datastore.ConnectionConfig
import com.opencode.remote.service.SseForegroundService
import com.opencode.remote.ui.chat.ResponseSegment
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds the current streaming state: which session is streaming,
 * the accumulated response segments, and the agent being used.
 */
data class StreamingState(
    val sessionId: String?,
    val segments: List<ResponseSegment>,
    val agent: String?,
)

/**
 * Interface for the OpenCode repository.
 * All methods map to actual OpenCode server v1.14.x API routes.
 */
interface OConnectorRepository {
    val isConnected: Boolean

    fun connect(config: ConnectionConfig)
    fun disconnect()

    // ─── Session Operations ──────────────────────────────────────────

    suspend fun listSessions(directory: String? = null): List<SessionInfo>
    /** Fetch sessions from ALL known projects (multi-directory query). */
    suspend fun listAllSessions(): List<SessionInfo>
    suspend fun createSession(directory: String? = null): CreateSessionResponse
    suspend fun getSession(sessionId: String, directory: String? = null): SessionInfo
    suspend fun deleteSession(sessionId: String, directory: String? = null)
    suspend fun forkSession(sessionId: String, directory: String? = null): CreateSessionResponse
    suspend fun abortSession(sessionId: String, directory: String? = null)

    // ─── Message Operations ──────────────────────────────────────────

    suspend fun getMessages(sessionId: String, directory: String? = null): List<MessageInfo>
    suspend fun sendMessage(sessionId: String, message: String, agent: String? = null, directory: String? = null)

    // ─── Permission / Question Replies ──────────────────────────────

    suspend fun replyPermission(requestId: String, reply: String, message: String? = null, directory: String? = null)
    suspend fun replyQuestion(requestId: String, answers: List<List<String>>, directory: String? = null)
    suspend fun rejectQuestion(requestId: String, directory: String? = null)

    // ─── Todo ────────────────────────────────────────────────────────

    suspend fun getTodoList(sessionId: String, directory: String? = null): List<TodoItem>

    // ─── Project ─────────────────────────────────────────────────────

    suspend fun getCurrentProject(): ProjectInfo
    suspend fun listProjects(): List<ProjectInfo>

    // ─── Test Connection ─────────────────────────────────────────────

    suspend fun testConnection(): Boolean

    // ─── Agents ─────────────────────────────────────────────────────────

    suspend fun listAgents(): List<AgentInfo>
    fun getCachedAgents(): List<AgentInfo>

    // ─── Files ──────────────────────────────────────────────────────────

    suspend fun listFiles(path: String, directory: String? = null): List<FileNode>

    // ─── Config / Providers ─────────────────────────────────────────────

    suspend fun listProviders(): ProviderList
    fun getCachedModels(): List<ModelInfo>

    // ─── Active Session (notification deep link) ─────────────────────

    var activeSessionId: String?
    var activeSessionDirectory: String?

    // ─── SSE Events ──────────────────────────────────────────────────

    fun subscribeToEvents(): Flow<ServerEvent>

    // ─── Streaming State Persistence ─────────────────────────────────

    fun beginStreaming(sessionId: String, agent: String?)
    fun setStreamingBlocks(segments: List<ResponseSegment>)
    fun setStreamingPendingMsgId(msgId: String?)
    fun getStreamingBlocksState(): StreamingState
    fun getStreamingPendingMsgId(): String?
    fun clearStreaming()
}

/**
 * Concrete implementation that manages the API client lifecycle and exposes
 * all OpenCode server operations through clean suspend functions.
 */
@Singleton
class OConnectorRepositoryImpl @Inject constructor(
    private val apiClient: OConnectorApiClient,
    private val sseClient: OConnectorSseClient,
    @ApplicationContext private val context: Context,
) : OConnectorRepository {

    private var connected = false
    private var cachedAgents: List<AgentInfo>? = null
    private var cachedModels: List<ModelInfo>? = null

    override var activeSessionId: String? = null
    override var activeSessionDirectory: String? = null

    override val isConnected: Boolean
        get() = connected

    /**
     * Initialize connection to the OpenCode server.
     * Clients are provided by Hilt; this configures them with connection parameters.
     */
    override fun connect(config: ConnectionConfig) {
        val scheme = if (config.useTls) "https" else "http"
        val baseUrl = "$scheme://${config.host}:${config.port}"

        apiClient.configure(baseUrl, config.username, config.password, config.insecureTrust)
        sseClient.configure(baseUrl, config.username, config.password, config.autoReconnect, config.insecureTrust)
        connected = true
        SseForegroundService.start(context)
    }

    /**
     * Disconnect from the server.
     */
    override fun disconnect() {
        apiClient.close()
        sseClient.close()
        SseForegroundService.stop(context)
        connected = false
        cachedAgents = null
        cachedModels = null
        activeSessionId = null
        activeSessionDirectory = null
    }

    private fun requireClient(): OConnectorApiClient {
        check(connected) { "Not connected to server" }
        return apiClient
    }

    // ─── Session Operations ──────────────────────────────────────────

    override suspend fun listSessions(directory: String?): List<SessionInfo> =
        requireClient().listSessions(directory)

    override suspend fun listAllSessions(): List<SessionInfo> =
        requireClient().listAllSessions()

    override suspend fun createSession(directory: String?): CreateSessionResponse =
        requireClient().createSession(directory)

    override suspend fun getSession(sessionId: String, directory: String?): SessionInfo =
        requireClient().getSession(sessionId, directory)

    override suspend fun deleteSession(sessionId: String, directory: String?) =
        requireClient().deleteSession(sessionId, directory)

    override suspend fun forkSession(sessionId: String, directory: String?): CreateSessionResponse =
        requireClient().forkSession(sessionId, directory)

    override suspend fun abortSession(sessionId: String, directory: String?) =
        requireClient().abortSession(sessionId, directory)

    // ─── Message Operations ──────────────────────────────────────────

    override suspend fun getMessages(sessionId: String, directory: String?): List<MessageInfo> =
        requireClient().getMessages(sessionId, directory)

    override suspend fun sendMessage(sessionId: String, message: String, agent: String?, directory: String?) =
        requireClient().sendMessage(sessionId, message, agent, directory)

    // ─── Permission / Question Replies ──────────────────────────────

    override suspend fun replyPermission(requestId: String, reply: String, message: String?, directory: String?) =
        requireClient().replyPermission(requestId, reply, message, directory)

    override suspend fun replyQuestion(requestId: String, answers: List<List<String>>, directory: String?) =
        requireClient().replyQuestion(requestId, answers, directory)

    override suspend fun rejectQuestion(requestId: String, directory: String?) =
        requireClient().rejectQuestion(requestId, directory)

    // ─── Todo ────────────────────────────────────────────────────────

    override suspend fun getTodoList(sessionId: String, directory: String?): List<TodoItem> =
        requireClient().getTodoList(sessionId, directory)

    // ─── Project ─────────────────────────────────────────────────────

    override suspend fun getCurrentProject(): ProjectInfo =
        requireClient().getCurrentProject()

    override suspend fun listProjects(): List<ProjectInfo> =
        requireClient().listProjects()

    // ─── Test Connection ─────────────────────────────────────────────

    override suspend fun testConnection(): Boolean =
        requireClient().testConnection()

    // ─── Agents ─────────────────────────────────────────────────────────

    override suspend fun listAgents(): List<AgentInfo> {
        cachedAgents?.let { return it }
        val agents = requireClient().listAgents()
            .filter { it.mode != "subagent" && !it.hidden }
        cachedAgents = agents
        return agents
    }

    /** Get cached agents (returns empty list if not loaded yet) */
    override fun getCachedAgents(): List<AgentInfo> = cachedAgents ?: emptyList()

    // ─── Files ──────────────────────────────────────────────────────────

    override suspend fun listFiles(path: String, directory: String?): List<FileNode> =
        requireClient().listFiles(path, directory)

    // ─── Config / Providers ─────────────────────────────────────────────

    override suspend fun listProviders(): ProviderList {
        cachedModels?.let { return ProviderList() }
        val providers = requireClient().listProviders()
        cachedModels = providers.providers.flatMap { it.models }
        return providers
    }

    override fun getCachedModels(): List<ModelInfo> = cachedModels ?: emptyList()

    // ─── SSE Events ──────────────────────────────────────────────────

    override fun subscribeToEvents(): Flow<ServerEvent> {
        check(connected) { "Not connected to server" }
        return sseClient.subscribeToEvents()
    }

    // ─── Streaming State Persistence ─────────────────────────────────
    // Survives ViewModel recreation when navigating away and back.
    // Stored in @Singleton Repository so state lives as long as the app process.
    //
    // Thread safety: All streaming state fields are accessed only from the
    // main thread via viewModelScope coroutines. The Repository is @Singleton
    // and all callers dispatch on Dispatchers.Main, so no synchronization
    // primitives are needed.

    /** Currently streaming session ID, or null if no active stream. */
    private var _streamingSessionId: String? = null

    /** Accumulated response segments for the current streaming message. */
    private var _streamingBlocks: List<ResponseSegment> = emptyList()

    /** Agent name used for the current streaming session, or null. */
    private var _streamingAgent: String? = null

    /** Pending message ID used to correlate SSE events after send. */
    private var _streamingPendingMsgId: String? = null

    override fun beginStreaming(sessionId: String, agent: String?) {
        _streamingSessionId = sessionId
        _streamingBlocks = emptyList()
        _streamingAgent = agent
        _streamingPendingMsgId = null
    }

    override fun setStreamingBlocks(segments: List<ResponseSegment>) {
        _streamingBlocks = segments
    }

    override fun setStreamingPendingMsgId(msgId: String?) {
        _streamingPendingMsgId = msgId
    }

    override fun getStreamingBlocksState(): StreamingState =
        StreamingState(_streamingSessionId, _streamingBlocks, _streamingAgent)

    override fun getStreamingPendingMsgId(): String? = _streamingPendingMsgId

    override fun clearStreaming() {
        _streamingSessionId = null
        _streamingBlocks = emptyList()
        _streamingAgent = null
        _streamingPendingMsgId = null
    }
}
