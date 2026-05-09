package com.opencode.remote.data.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * 实际 API: GET /session/{id}
 * 字段完全匹配 OpenCode server v1.14.x 响应
 */
@Serializable
data class SessionInfo(
    val id: String,
    val slug: String? = null,
    @SerialName("projectID")
    val projectID: String? = null,
    val directory: String? = null,
    val path: String? = null,
    val title: String? = null,
    val version: String? = null,
    val summary: SessionSummary? = null,
    val permission: List<SessionPermission>? = null,
    @SerialName("parentID")
    val parentID: String? = null,
    val time: SessionTime? = null,
)

@Serializable
data class SessionSummary(
    val additions: Int = 0,
    val deletions: Int = 0,
    val files: Int = 0,
)

@Serializable
data class SessionPermission(
    val permission: String,
    val action: String,
    val pattern: String,
)

@Serializable
data class SessionTime(
    val created: Long? = null,
    val updated: Long? = null,
    val initialized: Long? = null,
    val completed: Long? = null,
)

/**
 * 实际 API: POST /session 返回值
 */
@Serializable
data class CreateSessionResponse(
    val id: String,
    val slug: String? = null,
    val title: String? = null,
    @SerialName("projectID")
    val projectID: String? = null,
    val directory: String? = null,
    val path: String? = null,
    val version: String? = null,
    val time: SessionTime? = null,
)

// session 列表就是 List<SessionInfo>，无需额外包装

/**
 * 实际 API: GET /session/{id}/message 返回的消息
 * 消息有 info + parts 两层结构
 */
@Serializable
data class MessageInfo(
    val info: MessageInfoData,
    val parts: List<MessagePart> = emptyList(),
) {
    /** 兼容属性：从 info 提取 */
    val id: String get() = info.id
    val role: String get() = info.role
}

@Serializable
data class MessageInfoData(
    val id: String,
    val role: String,
    @SerialName("sessionID")
    val sessionID: String? = null,
    @SerialName("parentID")
    val parentID: String? = null,
    val agent: String? = null,
    val mode: String? = null,
    val model: MessageModel? = null,
    val time: MessageTime? = null,
    val finish: String? = null,
    val cost: Double? = null,
    val tokens: MessageTokens? = null,
    /** 服务器可能返回 boolean(true) 或 object({diffs:[...]})，用 JsonElement 兼容 */
    val summary: JsonElement? = null,
)

@Serializable
data class MessageModel(
    @SerialName("providerID")
    val providerID: String? = null,
    @SerialName("modelID")
    val modelID: String? = null,
)

@Serializable
data class MessageTime(
    val created: Long? = null,
    val completed: Long? = null,
)

@Serializable
data class MessageTokens(
    val total: Int? = null,
    val input: Int? = null,
    val output: Int? = null,
    val reasoning: Int? = null,
    val cache: CacheTokens? = null,
)

@Serializable
data class CacheTokens(
    val read: Int? = null,
    val write: Int? = null,
)

/**
 * 消息的 part —— 一个消息包含多个 part
 * type 可以是: "text", "step-start", "step-finish", "reasoning", "tool-call" 等
 */
@Serializable
data class MessagePart(
    val type: String,
    val text: String? = null,
    val id: String? = null,
    @SerialName("sessionID")
    val sessionID: String? = null,
    @SerialName("messageID")
    val messageID: String? = null,
    val time: PartTime? = null,
    val reason: String? = null,
    val tokens: MessageTokens? = null,
    val cost: Double? = null,
    /** Tool name for type="tool" parts (e.g. "edit", "read", "bash", "glob"). */
    val tool: String? = null,
    /** Unique call identifier for tool parts. */
    @SerialName("callID")
    val callID: String? = null,
    /** Tool execution state: status, input, output. */
    val state: ToolState? = null,
)

@Serializable
data class PartTime(
    val start: Long? = null,
    val end: Long? = null,
)

/** Tool execution state from the server (type="tool" parts). */
@Serializable
data class ToolState(
    val status: String? = null,
    /** Input varies by tool: {filePath} for edit/read/write, {command} for bash, etc. */
    val input: JsonElement? = null,
    /** Tool output — can be very long (file contents, command output, etc.). */
    val output: String? = null,
    /** Metadata — can be a JSON object or string. */
    val metadata: JsonElement? = null,
    val title: String? = null,
    /** Time — can be a JSON object {start, end} or string. */
    val time: JsonElement? = null,
)

/**
 * 发送消息的请求体
 * 实际 API: POST /session/{id}/message + {"parts":[{"type":"text","text":"..."}]}
 */
@Serializable
data class SendMessageRequest(
    val parts: List<SendMessagePart>,
    val agent: String? = null,
)

@Serializable
data class SendMessagePart(
    val type: String = "text",
    val text: String,
)

/**
 * 发送消息的响应 —— 通常返回完整的 assistant MessageInfo（包含 info + parts）
 * 但某些情况下服务器可能返回空或不完整响应，所以 info 可选
 */
@Serializable
data class SendMessageResponse(
    val info: MessageInfoData? = null,
    val parts: List<MessagePart> = emptyList(),
)

/**
 * Todo 项目
 * 实际 API: GET /session/{id}/todo 返回 List<TodoItem>
 * Server returns: { content, status, priority }
 * No "id" field — use index as fallback
 */
@Serializable
data class TodoItem(
    val content: String,
    val status: String,
    val priority: String? = null,
)

// ─── Truncation helpers (safety net — main truncation happens in OpenCodeApiClient at raw JSON level) ─────────

private const val MAX_TEXT_LENGTH = 5_000
private const val TRUNCATION_NOTICE = "\n\n… [truncated]"

private fun String?.truncateIfNeeded(): String? {
    if (this == null || length <= MAX_TEXT_LENGTH) return this
    return substring(0, MAX_TEXT_LENGTH) + TRUNCATION_NOTICE
}

fun MessageInfo.truncateLargeText() = copy(
    parts = parts.map { part ->
        when {
            part.state?.output != null -> part.copy(
                state = part.state.copy(output = part.state.output.truncateIfNeeded()),
                text = part.text.truncateIfNeeded(),
            )
            else -> part.copy(text = part.text.truncateIfNeeded())
        }
    }
)
