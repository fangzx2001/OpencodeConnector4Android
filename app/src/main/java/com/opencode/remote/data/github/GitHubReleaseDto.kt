package com.opencode.remote.data.github

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class GitHubRelease(
    @SerialName("tag_name") val tagName: String,
    val name: String,
    val body: String? = null,
    @SerialName("html_url") val htmlUrl: String,
    val prerelease: Boolean = false,
    val draft: Boolean = false,
    @SerialName("published_at") val publishedAt: String? = null,
    val assets: List<ReleaseAsset> = emptyList()
)

@Serializable
data class ReleaseAsset(
    val name: String,
    @SerialName("browser_download_url") val browserDownloadUrl: String,
    val size: Long = 0
)
