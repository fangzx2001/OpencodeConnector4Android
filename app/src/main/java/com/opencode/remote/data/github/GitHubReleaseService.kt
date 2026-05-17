package com.opencode.remote.data.github

import android.util.Log
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import okhttp3.OkHttpClient
import java.net.ProxySelector
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class GitHubReleaseService @Inject constructor() {
    private val client = HttpClient(OkHttp) {
        install(HttpTimeout) {
            connectTimeoutMillis = 15_000
            requestTimeoutMillis = 30_000
        }
        engine {
            // Explicitly use the system ProxySelector so that VPN/proxy apps
            // (Clash, V2Ray, etc.) are respected. Without this, OkHttp may
            // bypass the system proxy on some Android configurations.
            config {
                proxySelector(ProxySelector.getDefault())
                followRedirects(true)
                followSslRedirects(true)
                retryOnConnectionFailure(true)
                connectTimeout(15, TimeUnit.SECONDS)
                readTimeout(30, TimeUnit.SECONDS)
            }
        }
    }

    companion object {
        private const val TAG = "GitHubReleaseService"

        // Update sources: GitHub (original) + domestic reverse proxies.
        // All sources are queried in parallel; the first success wins.
        // These proxies mirror the GitHub API so no separate repo is needed.
        // Each entry: (display name, API base URL)
        private val SOURCES = listOf(
            "gh-proxy.com" to "https://gh-proxy.com/https://api.github.com",
            "GitHub"       to "https://api.github.com",
        )

        // Proxies for GitHub file downloads (APK etc.).
        // Used when the browser_download_url points to github.com which is blocked in China.
        private val DOWNLOAD_PROXIES = listOf(
            "https://gh-proxy.com",
        )

        /**
         * Rewrite a github.com download URL through a proxy so it's accessible in China.
         * Returns the original URL if it doesn't point to github.com.
         */
        fun proxiedDownloadUrl(originalUrl: String): String {
            if (!originalUrl.contains("github.com")) return originalUrl
            // Use the first proxy — if it fails the user can retry
            return "${DOWNLOAD_PROXIES.first()}/$originalUrl"
        }
    }

    /**
     * Fetch releases from all sources in parallel (race).
     * Returns [Result.success] from the first source that responds successfully.
     * If all sources fail, returns [Result.failure] with aggregated error messages.
     */
    suspend fun listReleases(owner: String, repo: String): Result<List<GitHubRelease>> {
        return coroutineScope {
            // Channel receives results as soon as any source completes
            val channel = Channel<Pair<String, Result<List<GitHubRelease>>>>(capacity = SOURCES.size)

            val jobs = SOURCES.map { (name, baseUrl) ->
                async {
                    val result = try {
                        Result.success(fetchReleases(baseUrl, owner, repo))
                    } catch (e: Exception) {
                        Result.failure(e)
                    }
                    channel.send(name to result)
                }
            }

            // Collect results — return on first success, or after all have reported failure
            var failures = mutableListOf<Pair<String, String>>()
            var remaining = SOURCES.size

            var finalResult: Result<List<GitHubRelease>>? = null

            while (remaining > 0 && finalResult == null) {
                val (name, result) = channel.receive()
                remaining--

                if (result.isSuccess) {
                    Log.d(TAG, "Update check succeeded via $name")
                    finalResult = result
                } else {
                    val msg = result.exceptionOrNull()?.message ?: "unknown"
                    Log.d(TAG, "Update check failed via $name: $msg")
                    failures.add(name to msg)
                }
            }

            // Cancel any still-running siblings
            jobs.forEach { it.cancel() }
            channel.close()

            finalResult ?: run {
                val triedSources = failures.map { it.first }
                Log.e(TAG, "All update sources failed. Tried: $triedSources")
                Result.failure(Exception("Tried ${triedSources.joinToString(", ")} — all failed. Check network."))
            }
        }
    }

    private suspend fun fetchReleases(
        baseUrl: String,
        owner: String,
        repo: String,
    ): List<GitHubRelease> {
        return client.get("$baseUrl/repos/$owner/$repo/releases") {
            header(HttpHeaders.UserAgent, "OConnector")
            header(HttpHeaders.Accept, "application/vnd.github+json")
            parameter("per_page", "10")
        }.body<List<GitHubRelease>>()
    }
}
