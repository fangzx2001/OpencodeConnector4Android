package com.opencode.remote.data.github

sealed class UpdateInfo {
    data class Available(
        val version: String,
        val changelog: String?,
        val downloadUrl: String?,
        val releaseUrl: String
    ) : UpdateInfo()
    data object UpToDate : UpdateInfo()
}
