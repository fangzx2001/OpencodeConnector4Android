package com.opencode.remote.data.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * 实际 API: GET /project/current
 * 服务器返回 flat JSON: {"id":"global","worktree":"/","time":{...},"sandboxes":[...]}
 * sandboxes 在 opencode 1.18+ 返回字符串数组（工作区目录），更早版本可能是对象数组。
 */
@Serializable
data class ProjectInfo(
    val id: String = "",
    val worktree: String? = null,
    val time: ProjectTime? = null,
    val sandboxes: List<String> = emptyList(),
)

@Serializable
data class ProjectTime(
    val created: Long? = null,
)

/**
 * 实际 API: GET /agent → 返回数组
 * 字段: name, mode, description, hidden, ...
 * mode: "primary" | "subagent" | "all"
 * hidden: true 时不在 UI 显示，自定义 agent 可能为 null
 */
@Serializable
data class AgentInfo(
    val name: String = "",
    val mode: String? = null,
    val description: String? = null,
    val hidden: Boolean? = false,
    val model: AgentModel? = null,
)

@Serializable
data class AgentModel(
    @SerialName("modelID")
    val modelID: String? = null,
    @SerialName("providerID")
    val providerID: String? = null,
)

/**
 * 实际 API: GET /file?path=... 返回数组
 * 字段: name, path, absolute, type ("file"|"directory"), ignored
 */
@Serializable
data class FileNode(
    val name: String = "",
    val path: String = "",
    val absolute: String = "",
    val type: String = "file",
    val ignored: Boolean = false,
)

/**
 * API response: GET /file/content?path=...
 * type: "text" | "binary"
 * content: file body (text or base64-encoded binary)
 */
@Serializable
data class FileContent(
    val type: String = "text",
    val content: String = "",
    val encoding: String? = null,
    val mimeType: String? = null,
)

@Serializable
data class ProviderList(
    @SerialName("all")
    val providers: List<ProviderInfo> = emptyList(),
    val default: Map<String, String> = emptyMap(),
    val connected: List<String> = emptyList(),
)

@Serializable
data class ProviderInfo(
    val id: String = "",
    val name: String? = null,
    /** Server returns models as a JSON object (Map keyed by model ID), not an array. */
    val models: Map<String, ProviderModelInfo> = emptyMap(),
)

@Serializable
data class ModelInfo(
    val id: String? = null,
    val name: String? = null,
    val providerID: String? = null,
    val status: String? = null,
)

/** Server returns limit as { context: Int, input?: Int, output: Int }, not a simple integer. */
@Serializable
data class ModelLimitInfo(
    val context: Int = 0,
    val input: Int? = null,
    val output: Int = 0,
)

@Serializable
data class ProviderModelInfo(
    val id: String = "",
    val name: String? = null,
    val limit: ModelLimitInfo? = null,
    val variants: Map<String, JsonElement> = emptyMap(),
)
