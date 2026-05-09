package com.opencode.remote.data.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 实际 API: GET /project/current
 * 服务器返回 flat JSON: {"id":"global","worktree":"/","time":{...},"sandboxes":[...]}
 */
@Serializable
data class ProjectInfo(
    val id: String,
    val worktree: String? = null,
    val time: ProjectTime? = null,
    val sandboxes: List<ProjectSandbox> = emptyList(),
)

@Serializable
data class ProjectTime(
    val created: Long? = null,
)

@Serializable
data class ProjectSandbox(
    val id: String? = null,
    val name: String? = null,
    val directory: String? = null,
)

/**
 * 实际 API: GET /agent → 返回数组
 * 字段: name, mode, description, hidden, ...
 * mode: "primary" | "subagent"
 * hidden: true 时不在 UI 显示
 */
@Serializable
data class AgentInfo(
    val name: String,
    val mode: String? = null,
    val description: String? = null,
    val hidden: Boolean = false,
)

@Serializable
data class ProvidersResponseDto(
    val all: List<ProviderInfo> = emptyList(),
    val providers: List<ProviderInfo> = emptyList(),
    val default: Map<String, String> = emptyMap(),
    val connected: List<String> = emptyList(),
) {
    val items: List<ProviderInfo>
        get() = if (all.isNotEmpty()) all else providers
}

@Serializable
data class ProviderInfo(
    val id: String,
    val name: String? = null,
    val models: Map<String, ProviderModelInfo> = emptyMap(),
)

@Serializable
data class ProviderModelInfo(
    val id: String? = null,
    val name: String? = null,
    val limit: ModelLimitInfo? = null,
)

@Serializable
data class ModelLimitInfo(
    val context: Int? = null,
    val output: Int? = null,
)
