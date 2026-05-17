package com.opencode.remote.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.opencode.remote.data.api.OConnectorApiClient
import com.opencode.remote.data.api.OConnectorSseClient
import com.opencode.remote.data.api.dto.*
import com.opencode.remote.data.datastore.ConnectionConfig
import com.opencode.remote.service.SseForegroundService
import com.opencode.remote.ui.chat.ResponseSegment
import com.opencode.remote.ui.chat.PermissionRequestData
import com.opencode.remote.ui.chat.QuestionRequestData
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
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
    fun switchToServer(serverId: String, config: ConnectionConfig)
    fun getActiveServerId(): String?

    // ─── Session Operations ──────────────────────────────────────────

    suspend fun listSessions(directory: String? = null): List<SessionInfo>
    /** Fetch sessions from ALL known projects (multi-directory query). */
    suspend fun listAllSessions(): List<SessionInfo>
    suspend fun createSession(directory: String? = null): CreateSessionResponse
    suspend fun getSession(sessionId: String, directory: String? = null): SessionInfo
    suspend fun deleteSession(sessionId: String, directory: String? = null)
    suspend fun forkSession(sessionId: String, directory: String? = null): CreateSessionResponse
    suspend fun abortSession(sessionId: String, directory: String? = null)
    suspend fun revertSession(sessionId: String, messageID: String, directory: String? = null): SessionInfo
    suspend fun unrevertSession(sessionId: String, directory: String? = null): SessionInfo

    // ─── Message Operations ──────────────────────────────────────────

    suspend fun getMessages(sessionId: String, directory: String? = null, limit: Int? = null): List<MessageInfo>
    suspend fun sendMessage(sessionId: String, message: String, agent: String? = null, providerID: String? = null, modelID: String? = null, directory: String? = null)

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
    suspend fun readFileContent(path: String, directory: String? = null): FileContent

    // ─── Config / Providers ─────────────────────────────────────────────

    suspend fun listProviders(): ProviderList
    fun getCachedModels(): List<ModelInfo>

    // ─── Active Session (notification deep link) ─────────────────────

    var activeSessionId: String?
    var activeSessionDirectory: String?

    // ─── Server Name ─────────────────────────────────────────────────

    fun setServerName(name: String?)
    fun getCurrentServerName(): String?

    // ─── SSE Events ──────────────────────────────────────────────────

    fun subscribeToEvents(): Flow<ServerEvent>

    // ─── Streaming State Persistence ─────────────────────────────────

    fun beginStreaming(sessionId: String, agent: String?)
    fun setStreamingBlocks(segments: List<ResponseSegment>)
    fun setStreamingPendingMsgId(msgId: String?)
    fun getStreamingBlocksState(): StreamingState
    fun getStreamingPendingMsgId(): String?
    fun clearStreaming()

    // ─── Blocking State Cache (survives ViewModel recreation) ───────

    /** Persist blocking state for a session (question/permission data). */
    fun saveBlockingState(sessionId: String, permission: PermissionRequestData?, question: QuestionRequestData?)
    /** Retrieve persisted blocking state for a session. Returns null if none. */
    fun getBlockingState(sessionId: String): BlockingStateCache?
    /** Clear persisted blocking state for a session. */
    fun clearBlockingState(sessionId: String)
}

/** Cache entry for blocking state, stored by session ID. */
@Serializable
data class BlockingStateCache(
    val permission: PermissionRequestData? = null,
    val question: QuestionRequestData? = null,
)

/**
 * Concrete implementation that manages the API client lifecycle and exposes
 * all OpenCode server operations through clean suspend functions.
 */
@Singleton
class OConnectorRepositoryImpl @Inject constructor(
    private val apiClient: OConnectorApiClient,
    private val sseClient: OConnectorSseClient,
    @ApplicationContext private val context: Context,
    private val json: Json,
) : OConnectorRepository {

    companion object {
        private const val TAG = "OConnectorRepository"
    }

    private var connected = false
    private var cachedAgents: List<AgentInfo>? = null
    private var agentsCacheTime: Long = 0
    private var cachedModels: List<ModelInfo>? = null
    private var modelsCacheTime: Long = 0
    private var activeServerId: String? = null
    private var activeServerName: String? = null

    override var activeSessionId: String? = null
    override var activeSessionDirectory: String? = null

    // ─── Disk persistence for state that must survive process death ───────
    private val statePrefs: SharedPreferences by lazy {
        context.getSharedPreferences("opencode_state_cache", Context.MODE_PRIVATE)
    }

    private fun persistBlockingState(sessionId: String, cache: BlockingStateCache?) {
        try {
            if (cache != null && (cache.permission != null || cache.question != null)) {
                val encoded = json.encodeToString(BlockingStateCache.serializer(), cache)
                statePrefs.edit().putString("block_$sessionId", encoded).apply()
            } else {
                statePrefs.edit().remove("block_$sessionId").apply()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist blocking state for $sessionId", e)
        }
    }

    private fun restoreBlockingState(sessionId: String): BlockingStateCache? {
        return try {
            val encoded = statePrefs.getString("block_$sessionId", null)
            if (encoded != null) json.decodeFromString(BlockingStateCache.serializer(), encoded) else null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore blocking state for $sessionId", e)
            null
        }
    }

    private fun clearBlockingStateDisk(sessionId: String) {
        statePrefs.edit().remove("block_$sessionId").apply()
    }

    private fun persistStreamingState() {
        try {
            val sessionId = _streamingSessionId
            if (sessionId != null) {
                val segmentsJson = json.encodeToString(ListSerializer(ResponseSegment.serializer()), _streamingBlocks)
                statePrefs.edit()
                    .putString("stream_session", sessionId)
                    .putString("stream_segments", segmentsJson)
                    .putString("stream_agent", _streamingAgent)
                    .putString("stream_pending_msg", _streamingPendingMsgId)
                    .apply()
            } else {
                statePrefs.edit()
                    .remove("stream_session")
                    .remove("stream_segments")
                    .remove("stream_agent")
                    .remove("stream_pending_msg")
                    .apply()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist streaming state", e)
        }
    }

    private fun clearStreamingDisk() {
        statePrefs.edit()
            .remove("stream_session")
            .remove("stream_segments")
            .remove("stream_agent")
            .remove("stream_pending_msg")
            .apply()
    }

    private fun restoreStreamingStateFromDisk(): StreamingState? {
        return try {
            val sessionId = statePrefs.getString("stream_session", null) ?: return null
            val segmentsJson = statePrefs.getString("stream_segments", null) ?: return null
            val segments = json.decodeFromString(ListSerializer(ResponseSegment.serializer()), segmentsJson)
            val agent = statePrefs.getString("stream_agent", null)
            StreamingState(sessionId, segments, agent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore streaming state from disk", e)
            null
        }
    }

    private fun restoreStreamingPendingMsgId(): String? {
        return try {
            statePrefs.getString("stream_pending_msg", null)
        } catch (e: Exception) {
            null
        }
    }

    override val isConnected: Boolean
        get() = connected

    override fun setServerName(name: String?) {
        activeServerName = name
    }

    override fun getCurrentServerName(): String? = activeServerName

    override fun switchToServer(serverId: String, config: ConnectionConfig) {
        if (activeServerId == serverId && connected) return  // already connected to this server
        if (connected) disconnect()
        connect(config)
        activeServerId = serverId
    }

    override fun getActiveServerId(): String? = activeServerId

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
        agentsCacheTime = 0
        modelsCacheTime = 0
        activeSessionId = null
        activeSessionDirectory = null
        activeServerId = null
        activeServerName = null
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

    override suspend fun revertSession(sessionId: String, messageID: String, directory: String?): SessionInfo =
        requireClient().revertSession(sessionId, messageID, directory)

    override suspend fun unrevertSession(sessionId: String, directory: String?): SessionInfo =
        requireClient().unrevertSession(sessionId, directory)

    // ─── Message Operations ──────────────────────────────────────────

    override suspend fun getMessages(sessionId: String, directory: String?, limit: Int?): List<MessageInfo> =
        requireClient().getMessages(sessionId, directory, limit)

    override suspend fun sendMessage(sessionId: String, message: String, agent: String?, providerID: String?, modelID: String?, directory: String?) =
        requireClient().sendMessage(sessionId, message, agent, providerID, modelID, directory)

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
        // Return cache if valid (within 30s TTL)
        if (cachedAgents != null && System.currentTimeMillis() - agentsCacheTime < 30_000) {
            return cachedAgents!!
        }
        val agents = requireClient().listAgents()
            .filter { it.mode != "subagent" && !it.hidden }
        cachedAgents = agents
        agentsCacheTime = System.currentTimeMillis()
        return agents
    }

    /** Get cached agents (returns empty list if not loaded yet) */
    override fun getCachedAgents(): List<AgentInfo> = cachedAgents ?: emptyList()

    // ─── Files ──────────────────────────────────────────────────────────

    override suspend fun listFiles(path: String, directory: String?): List<FileNode> =
        requireClient().listFiles(path, directory)

    override suspend fun readFileContent(path: String, directory: String?): FileContent =
        requireClient().readFileContent(path, directory)

    // ─── Config / Providers ─────────────────────────────────────────────

    override suspend fun listProviders(): ProviderList {
        // Return cache if valid (within 30s TTL)
        if (cachedModels != null && System.currentTimeMillis() - modelsCacheTime < 30_000) {
            return ProviderList()
        }
        val providers = requireClient().listProviders()
        cachedModels = providers.providers.flatMap { it.models.values }
        modelsCacheTime = System.currentTimeMillis()
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
        persistStreamingState()
    }

    override fun setStreamingBlocks(segments: List<ResponseSegment>) {
        _streamingBlocks = segments
        persistStreamingState()
    }

    override fun setStreamingPendingMsgId(msgId: String?) {
        _streamingPendingMsgId = msgId
        persistStreamingState()
    }

    override fun getStreamingBlocksState(): StreamingState {
        // Memory cache first
        if (_streamingSessionId != null) {
            return StreamingState(_streamingSessionId, _streamingBlocks, _streamingAgent)
        }
        // Fall back to disk (survives process death)
        val fromDisk = restoreStreamingStateFromDisk()
        if (fromDisk != null && fromDisk.sessionId != null) {
            _streamingSessionId = fromDisk.sessionId
            _streamingBlocks = fromDisk.segments
            _streamingAgent = fromDisk.agent
            return fromDisk
        }
        return StreamingState(null, emptyList(), null)
    }

    override fun getStreamingPendingMsgId(): String? {
        if (_streamingPendingMsgId != null) return _streamingPendingMsgId
        return restoreStreamingPendingMsgId()
    }

    override fun clearStreaming() {
        _streamingSessionId = null
        _streamingBlocks = emptyList()
        _streamingAgent = null
        _streamingPendingMsgId = null
        clearStreamingDisk()
    }

    // ─── Blocking State Cache ──────────────────────────────────────

    private val _blockingStateCache = mutableMapOf<String, BlockingStateCache>()

    override fun saveBlockingState(sessionId: String, permission: PermissionRequestData?, question: QuestionRequestData?) {
        if (permission != null || question != null) {
            val cache = BlockingStateCache(permission, question)
            _blockingStateCache[sessionId] = cache
            persistBlockingState(sessionId, cache)
        } else {
            _blockingStateCache.remove(sessionId)
            persistBlockingState(sessionId, null)
        }
    }

    override fun getBlockingState(sessionId: String): BlockingStateCache? {
        // Memory cache first, then fall back to disk (survives process death)
        val cached = _blockingStateCache[sessionId]
        if (cached != null) return cached
        val fromDisk = restoreBlockingState(sessionId)
        if (fromDisk != null) {
            _blockingStateCache[sessionId] = fromDisk  // warm memory cache
        }
        return fromDisk
    }

    override fun clearBlockingState(sessionId: String) {
        _blockingStateCache.remove(sessionId)
        clearBlockingStateDisk(sessionId)
    }
}
