package com.opencode.remote.data.api.dto

import kotlinx.serialization.Serializable

/** A single memo entry, scoped to a project by directory. */
@Serializable
data class MemoEntry(
    val id: String,
    val directory: String,
    val title: String,
    val content: String = "",
    val isDone: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
)
