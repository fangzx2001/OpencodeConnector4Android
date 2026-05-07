package com.opencode.remote.data.github

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.http.*
import javax.inject.Inject

class GitHubReleaseService @Inject constructor() {
    private val client = HttpClient(OkHttp) {
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            requestTimeoutMillis = 15_000
        }
    }

    suspend fun checkLatestRelease(owner: String, repo: String): Result<GitHubRelease> {
        return try {
            val release = client.get("https://api.github.com/repos/$owner/$repo/releases/latest") {
                header(HttpHeaders.UserAgent, "OConnector")
                header(HttpHeaders.Accept, "application/vnd.github+json")
            }.body<GitHubRelease>()
            Result.success(release)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
