package com.opencode.remote.data.api.dto

import kotlinx.serialization.Serializable

/**
 * OpenCode v1.14.x SSE 事件格式 (captured from live server):
 *
 * Top-level JSON:
 *   {"directory":"D:\\path\\to\\project","project":"global","payload":{...}}
 *
 * Event types observed (verified from captured SSE data):
 *   server.connected     — initial handshake
 *   session.updated      — session metadata changed (properties.info)
 *   session.status       — session status transition (properties.status.type = "busy"/"idle")
 *   session.idle         — session became idle
 *   session.diff         — code diff generated
 *   message.updated      — message created/updated (properties.info.role = "user"/"assistant")
 *   message.completed    — message fully generated (reload list, clear streaming)
 *   message.part.updated — part state change (properties.part.text = full accumulated text)
 *   message.part.delta   — streaming text delta (properties.delta = incremental chunk)
 *   message.part.completed — single part finished
 *   sync                 — internal synchronization (syncEvent at payload level, ignore)
 *   session.error        — server error (properties.error)
 *
 * Key streaming fields:
 *   message.part.delta  → properties.delta = incremental text chunk (APPEND)
 *   message.part.updated → properties.part.text = full text so far (can use for state recovery)
 *   message.updated     → properties.info.role = "assistant" signals thinking started
 */

@Serializable
data class ServerEvent(
    val directory: String? = null,
    val project: String? = null,
    val payload: EventPayload,
)

@Serializable
data class EventPayload(
    val type: String,
    val properties: EventProperties = EventProperties(),
)

@Serializable
data class EventProperties(
    val sessionID: String? = null,
    val messageID: String? = null,
    val partID: String? = null,
    val text: String? = null,
    val delta: String? = null,
    val field: String? = null,
    val error: String? = null,
    val part: MessagePart? = null,
    val info: MessageInfoData? = null,
)
