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
import kotlinx.serialization.json.Json
import javax.inject.Inject
import java.net.URLEncoder
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import java.security.cert.X509Certificate
import javax.net.ssl.TrustManager

/**
 * REST API client for OpenCode server v1.14.x
 *
 * All routes match actual server paths (verified via curl):
 *   GET  /session?list              → List<SessionInfo>
 *   POST /session                   → CreateSessionResponse
 *   GET  /session/{id}              → SessionInfo
 *   DELETE /session/{id}            → 200 OK
 *   POST /session/{id}/fork         → CreateSessionResponse
 *   POST /session/{id}/abort        → 200 OK
 *   GET  /session/{id}/message      → List<MessageInfo>
 *   POST /session/{id}/prompt_async → 204 No Content (async, AI output via SSE)
 *   GET  /session/{id}/todo         → List<TodoItem>
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
     * POST /session/{id}/prompt_async — 异步发送（HTTP 204, 立即返回）
     * Body: {"parts":[{"type":"text","text":"user message"}],"agent":"optional"}
     * AI 生成通过 SSE 事件流实时推送（message.part.delta, message.completed)
     */
    suspend fun sendMessage(sessionId: String, text: String, agent: String? = null, directory: String? = null) {
        client.post("/session/$sessionId/prompt_async") {
            setBody(SendMessageRequest(parts = listOf(SendMessagePart(text = text)), agent = agent))
            directory?.let {
                parameter("directory", it)
                header("x-opencode-directory", encDir(it))
            }
        }
    }

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

    /** GET /agent → returns array of available agents */
    suspend fun listAgents(): List<AgentInfo> =
        client.get("/agent").body<List<AgentInfo>>()

    fun close() {
        try { client.close() } catch (_: Exception) {}
    }
}
