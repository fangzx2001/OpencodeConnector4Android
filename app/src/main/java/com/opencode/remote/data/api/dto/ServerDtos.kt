package com.opencode.remote.data.api.dto

import kotlinx.serialization.Serializable

/**
 * 服务器信息，用于多服务器支持
 */
@Serializable
data class ServerInfo(
    val id: String = "",
    val name: String = "",
    val host: String = "",
    val port: Int = 4096,
    val username: String = "",
    val useTls: Boolean = false,
    val insecureTrust: Boolean = false,
)
