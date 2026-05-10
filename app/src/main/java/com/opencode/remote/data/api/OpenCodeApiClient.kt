package com.opencode.remote.data.api

import android.util.Log
import android.util.Base64
import com.opencode.remote.data.api.dto.*
import io.ktor.client.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.ExperimentalSerializationApi
import io.ktor.client.call.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import java.net.URLEncoder
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import java.security.cert.X509Certificate
import javax.net.ssl.TrustManager
import kotlin.time.Duration.Companion.minutes

/**
 * REST API client for OpenCode server v1.14.x
 *
 * All routes match actual server paths (verified via curl):
 *   GET  /session?list              → List<SessionInfo>
 *   POST /session                   → CreateSessionResponse
 *   GET  /session/{id}              → SessionInfo
 *   DELETE /session/{id}            → 200 OK
 *   PATCH /session/{id}             → SessionInfo
 *   POST /session/{id}/fork         → CreateSessionResponse
 *   POST /session/{id}/abort        → 200 OK
 *   POST /session/{id}/share        → SessionInfo
 *   DELETE /session/{id}/share      → SessionInfo
 *   GET  /session/{id}/message      → List<MessageInfo>
 *   POST /session/{id}/message      → preferred send route on newer OpenCode builds
 *   POST /session/{id}/prompt_async → legacy async send route kept for compatibility
 *   GET  /session/{id}/todo         → List<TodoItem>
 *   GET  /provider                  → ProvidersResponseDto
 *   GET  /project/current           → ProjectInfo
 */
class OConnectorApiClient @Inject constructor(
    private val json: Json,
) {

    private var authHeader: String? = null
    private var insecureTrust: Boolean = false

    @OptIn(ExperimentalSerializationApi::class)
    private var client: HttpClient = createClient()

    private fun createClient(insecureTrust: Boolean = false): HttpClient = HttpClient(OkHttp) {
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 30_000
        }
        defaultRequest {
            contentType(ContentType.Application.Json)
            authHeader?.let { header(HttpHeaders.Authorization, it) }
        }
        engine {
            if (insecureTrust) {
                val trustManager = object : X509TrustManager {
                    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
                    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
                }
                val sslContext = SSLContext.getInstance("TLS")
                sslContext.init(null, arrayOf<TrustManager>(trustManager), java.security.SecureRandom())
                config {
                    sslSocketFactory(sslContext.socketFactory, trustManager)
                    hostnameVerifier { _, _ -> true }
                }
            }
        }
    }

    companion object {
        private const val TAG = "OConnectorApiClient"
        /** Maximum number of messages to load from server. Prevents OOM on long sessions. */
        private const val MAX_MESSAGES = 50
        /** Message send may legitimately stay open while the server starts processing. */
        private val SEND_MESSAGE_TIMEOUT_MS = 3.minutes.inWholeMilliseconds
    }

    /**
     * Configure (or reconfigure) the client with connection parameters.
     * Called by the repository when a new connection is established.
     */
    fun configure(baseUrl: String, username: String = "", password: String = "", insecureTrust: Boolean = false) {
        close()
        this.insecureTrust = insecureTrust
        authHeader = if (password.isNotEmpty()) {
            "Basic " + Base64.encodeToString(
                "${username.ifEmpty { "opencode" }}:$password".toByteArray(),
                Base64.NO_WRAP
            )
        } else null
        client = HttpClient(OkHttp) {
            install(ContentNegotiation) { json(json) }
            install(HttpTimeout) {
                requestTimeoutMillis = 30_000
                connectTimeoutMillis = 10_000
                socketTimeoutMillis = 30_000
            }
            defaultRequest {
                url(baseUrl)
                contentType(ContentType.Application.Json)
                authHeader?.let { header(HttpHeaders.Authorization, it) }
            }
            engine {
                if (insecureTrust) {
                    val trustManager = object : X509TrustManager {
                        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
                        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
                    }
                    val sslContext = SSLContext.getInstance("TLS")
                    sslContext.init(null, arrayOf<TrustManager>(trustManager), java.security.SecureRandom())
                    config {
                        sslSocketFactory(sslContext.socketFactory, trustManager)
                        hostnameVerifier { _, _ -> true }
                    }
                }
            }
        }
    }

    /** Encode directory path for HTTP header (RFC 7230: headers are ASCII-only). */
    private fun encDir(path: String): String =
        URLEncoder.encode(path, "UTF-8")

    // ─── Sessions ──────────────────────────────────────────────────────

    /** GET /session?list → returns array directly. Optional directory/scope filter. */
    suspend fun listSessions(directory: String? = null, scope: String? = null): List<SessionInfo> {
        val sessions = client.get("/session") {
            parameter("list", "")
            directory?.let {
                parameter("directory", it)
                header("x-opencode-directory", encDir(it))
            }
            scope?.let { parameter("scope", it) }
        }.body<List<SessionInfo>>()
        Log.d(TAG, "Loaded ${sessions.size} sessions for dir=$directory scope=$scope")
        return sessions
    }

    /** POST /session with empty body → returns new session. Optional directory to set project. */
    suspend fun createSession(directory: String? = null): CreateSessionResponse =
        client.post("/session") {
            setBody("{}")
            directory?.let {
                parameter("directory", it)
                header("x-opencode-directory", encDir(it))
            }
        }.body<CreateSessionResponse>()

    /** GET /session/{id} */
    suspend fun getSession(id: String, directory: String? = null): SessionInfo =
        client.get("/session/$id") {
            directory?.let {
                parameter("directory", it)
                header("x-opencode-directory", encDir(it))
            }
        }.body<SessionInfo>()

    /** DELETE /session/{id} */
    suspend fun deleteSession(id: String, directory: String? = null) {
        client.delete("/session/$id") {
            directory?.let {
                parameter("directory", it)
                header("x-opencode-directory", encDir(it))
            }
        }
    }

    /** PATCH /session/{id} */
    suspend fun updateSession(id: String, title: String, directory: String? = null): SessionInfo =
        client.patch("/session/$id") {
            setBody(UpdateSessionRequest(title = title))
            directory?.let {
                parameter("directory", it)
                header("x-opencode-directory", encDir(it))
            }
        }.body<SessionInfo>()

    /** POST /session/{id}/fork with empty body */
    suspend fun forkSession(id: String, directory: String? = null): CreateSessionResponse =
        client.post("/session/$id/fork") {
            setBody("{}")
            directory?.let {
                parameter("directory", it)
                header("x-opencode-directory", encDir(it))
            }
        }.body<CreateSessionResponse>()

    /** POST /session/{id}/abort */
    suspend fun abortSession(id: String, directory: String? = null) {
        client.post("/session/$id/abort") {
            directory?.let {
                parameter("directory", it)
                header("x-opencode-directory", encDir(it))
            }
        }
    }

    /** POST /session/{id}/summarize */
    suspend fun summarizeSession(
        id: String,
        providerID: String,
        modelID: String,
        directory: String? = null,
    ) {
        client.post("/session/$id/summarize") {
            setBody(SummarizeSessionRequest(providerID = providerID, modelID = modelID))
            directory?.let {
                parameter("directory", it)
                header("x-opencode-directory", encDir(it))
            }
        }
    }

    /** POST /session/{id}/share */
    suspend fun shareSession(id: String, directory: String? = null): SessionInfo =
        client.post("/session/$id/share") {
            directory?.let {
                parameter("directory", it)
                header("x-opencode-directory", encDir(it))
            }
        }.body<SessionInfo>()

    /** DELETE /session/{id}/share */
    suspend fun unshareSession(id: String, directory: String? = null): SessionInfo =
        client.delete("/session/$id/share") {
            directory?.let {
                parameter("directory", it)
                header("x-opencode-directory", encDir(it))
            }
        }.body<SessionInfo>()

    // ─── Messages ──────────────────────────────────────────────────────

    /**
     * GET /session/{id}/message → returns array directly.
     *
     * Memory optimization:
     * 1. Server-side: passes ?limit=N to only fetch recent messages (prevents huge response)
     * 2. Client-side: caps to [MAX_MESSAGES] as safety net
     *
     * The OpenCode server supports ?limit=N (cursor-based pagination).
     * Without it, ALL messages including huge tool outputs are returned → OOM.
     */
    suspend fun getMessages(id: String, directory: String? = null): List<MessageInfo> {
        val messages = client.get("/session/$id/message") {
            parameter("limit", MAX_MESSAGES)
            directory?.let {
                parameter("directory", it)
                header("x-opencode-directory", encDir(it))
            }
        }.body<List<MessageInfo>>()

        // Safety net: if server ignores limit or returns more
        return if (messages.size > MAX_MESSAGES) {
            Log.w(TAG, "Server returned ${messages.size} messages despite limit=$MAX_MESSAGES, truncating")
            messages.takeLast(MAX_MESSAGES)
        } else {
            messages
        }
    }

    /**
     * Send a user message using the newest known route first, then fall back
     * to the legacy async endpoint when talking to older servers.
     *
     * Newer OpenCode builds prefer POST /session/{id}/message with a parts[] body.
     * Older builds may still only support POST /session/{id}/prompt_async.
     *
     * In both cases the Android client still treats SSE as the source of truth
     * for streaming assistant output and turn completion.
     */
    suspend fun sendMessage(
        sessionId: String,
        text: String,
        agent: String? = null,
        model: SendMessageModelRef? = null,
        variant: String? = null,
        directory: String? = null,
    ) {
        val request = SendMessageRequest(
            parts = listOf(SendMessagePart(text = text)),
            agent = agent,
            model = model,
            variant = variant,
        )

        try {
            client.post("/session/$sessionId/message") {
                applyMessageSendRequestOptions(directory)
                setBody(request)
            }
            Log.d(TAG, "Sent message via /session/$sessionId/message")
            return
        } catch (e: ResponseException) {
            if (!shouldFallbackToLegacyPromptAsync(e.response.status)) throw e
            Log.w(
                TAG,
                "Send via /session/$sessionId/message not supported (${e.response.status}), falling back to /prompt_async",
            )
        }

        client.post("/session/$sessionId/prompt_async") {
            applyMessageSendRequestOptions(directory)
            setBody(request)
        }
        Log.d(TAG, "Sent message via legacy /session/$sessionId/prompt_async")
    }

    private fun HttpRequestBuilder.applyMessageSendRequestOptions(directory: String?) {
        timeout {
            requestTimeoutMillis = SEND_MESSAGE_TIMEOUT_MS
            socketTimeoutMillis = SEND_MESSAGE_TIMEOUT_MS
        }
        directory?.let {
            parameter("directory", it)
            header("x-opencode-directory", encDir(it))
        }
    }

    private fun shouldFallbackToLegacyPromptAsync(status: HttpStatusCode): Boolean =
        status == HttpStatusCode.NotFound ||
            status == HttpStatusCode.MethodNotAllowed ||
            status == HttpStatusCode.NotImplemented

    // ─── Todo ──────────────────────────────────────────────────────────

    /** GET /session/{id}/todo → returns array directly */
    suspend fun getTodoList(id: String, directory: String? = null): List<TodoItem> =
        client.get("/session/$id/todo") {
            directory?.let {
                parameter("directory", it)
                header("x-opencode-directory", encDir(it))
            }
        }.body<List<TodoItem>>()

    // ─── Project ───────────────────────────────────────────────────────

    /** GET /project/current → flat ProjectInfo (no wrapper) */
    suspend fun getCurrentProject(): ProjectInfo =
        client.get("/project/current").body<ProjectInfo>()

    /** GET /project → list all known projects */
    suspend fun listProjects(): List<ProjectInfo> =
        client.get("/project").body<List<ProjectInfo>>()

    /**
     * Fetch sessions from ALL known projects.
     *
     * OpenCode scopes sessions by project (determined by directory).
     * A single server call only returns sessions for one project.
     *
     * Strategy:
     *   1. GET /project → discover all known projects
     *   2. For normal projects (real worktree): GET /session?list&directory=<worktree>
     *   3. For global project (worktree="/"): GET /session?list&directory=/&scope=project
     *      — scope=project skips directory matching, returns ALL sessions for that project_id
     *   4. Merge and deduplicate all results
     *
     * Falls back to a single unfiltered request if project list fails.
     */
    suspend fun listAllSessions(): List<SessionInfo> {
        val allSessions = mutableListOf<SessionInfo>()
        val seenIds = mutableSetOf<String>()

        try {
            val projects = listProjects()
            Log.d(TAG, "Discovered ${projects.size} projects: ${projects.map { "${it.id}=${it.worktree}" }}")

            // Query sessions for each project in parallel
            val results = coroutineScope {
                projects.map { project ->
                    async {
                        try {
                            val isGlobal = project.worktree == "/" || project.worktree == null
                            if (isGlobal) {
                                // scope=project skips directory filter, returns all sessions for this project_id
                                listSessions(directory = project.worktree ?: "/", scope = "project")
                            } else {
                                listSessions(directory = project.worktree)
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to load sessions for project ${project.id} (${project.worktree}): ${e.message}")
                            emptyList<SessionInfo>()
                        }
                    }
                }.awaitAll()
            }

            for (sessions in results) {
                for (session in sessions) {
                    if (seenIds.add(session.id)) {
                        allSessions.add(session)
                    }
                }
            }

            Log.d(TAG, "Merged ${allSessions.size} unique sessions from ${projects.size} projects")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to list projects, falling back to single query: ${e.message}")
            return listSessions(null)
        }

        return allSessions
    }

    /** Test connectivity by hitting a lightweight endpoint */
    suspend fun testConnection(): Boolean = try {
        getCurrentProject()
        true
    } catch (e: Exception) {
        Log.w(TAG, "Test connection failed: ${e.javaClass.simpleName}: ${e.message}")
        false
    }

    // ─── Agents ─────────────────────────────────────────────────────────

    /** GET /agent → returns available agents; scope by directory when provided */
    suspend fun listAgents(directory: String? = null): List<AgentInfo> {
        val raw = client.get("/agent") {
            directory?.let {
                parameter("directory", it)
                header("x-opencode-directory", encDir(it))
            }
        }.body<String>()
        return parseAgentsResponse(raw)
    }

    private fun parseAgentsResponse(raw: String): List<AgentInfo> {
        val element = json.decodeFromString<JsonElement>(raw)
        return when (element) {
            is JsonArray -> element.mapNotNull(::parseAgentInfo)
            is JsonObject -> parseAgentList(element["agents"])
                .ifEmpty { parseAgentList(element["items"]) }
                .ifEmpty { parseAgentList(element["data"]) }
            else -> emptyList()
        }
    }

    private fun parseAgentList(element: JsonElement?): List<AgentInfo> =
        (element as? JsonArray)?.mapNotNull(::parseAgentInfo).orEmpty()

    private fun parseAgentInfo(element: JsonElement): AgentInfo? {
        val obj = element as? JsonObject ?: return null
        val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return null
        return AgentInfo(
            name = name,
            mode = obj["mode"]?.jsonPrimitive?.contentOrNull,
            description = obj["description"]?.jsonPrimitive?.contentOrNull,
            hidden = obj["hidden"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false,
        )
    }

    /** GET /provider → returns providers and model limits */
    suspend fun listProviders(): ProvidersResponseDto {
        val raw = client.get("/provider").body<String>()
        return parseProvidersResponse(raw)
    }

    private fun parseProvidersResponse(raw: String): ProvidersResponseDto {
        val element = json.decodeFromString<JsonElement>(raw)
        return when (element) {
            is JsonArray -> ProvidersResponseDto(providers = element.mapNotNull(::parseProviderInfo))
            is JsonObject -> ProvidersResponseDto(
                all = parseProviderList(element["all"]),
                providers = parseProviderList(element["providers"]).ifEmpty { parseProviderList(element["items"]) },
                default = parseStringMap(element["default"]),
                connected = parseStringList(element["connected"]),
            )
            else -> ProvidersResponseDto()
        }
    }

    private fun parseProviderList(element: JsonElement?): List<ProviderInfo> =
        (element as? JsonArray)?.mapNotNull(::parseProviderInfo).orEmpty()

    private fun parseProviderInfo(element: JsonElement): ProviderInfo? {
        val obj = element as? JsonObject ?: return null
        val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return null
        val name = obj["name"]?.jsonPrimitive?.contentOrNull
        return ProviderInfo(
            id = id,
            name = name,
            models = parseProviderModels(obj["models"]),
        )
    }

    private fun parseProviderModels(element: JsonElement?): Map<String, ProviderModelInfo> = when (element) {
        is JsonObject -> element.mapNotNull { (key, value) ->
            runCatching { key to json.decodeFromJsonElement<ProviderModelInfo>(value) }.getOrNull()
        }.toMap()
        is JsonArray -> element.mapIndexedNotNull { index, value ->
            runCatching {
                val model = json.decodeFromJsonElement<ProviderModelInfo>(value)
                val key = model.id ?: model.name ?: index.toString()
                key to model
            }.getOrNull()
        }.toMap()
        else -> emptyMap()
    }

    private fun parseStringMap(element: JsonElement?): Map<String, String> {
        val obj = element as? JsonObject ?: return emptyMap()
        return obj.mapNotNull { (key, value) ->
            value.jsonPrimitive.contentOrNull?.let { key to it }
        }.toMap()
    }

    private fun parseStringList(element: JsonElement?): List<String> =
        (element as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()

    fun close() {
        try { client.close() } catch (_: Exception) {}
    }
}
