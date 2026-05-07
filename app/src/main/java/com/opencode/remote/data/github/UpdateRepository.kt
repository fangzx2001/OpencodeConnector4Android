package com.opencode.remote.data.github

import com.opencode.remote.BuildConfig
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UpdateRepository @Inject constructor(
    private val gitHubService: GitHubReleaseService
) {
    suspend fun checkForUpdate(): Result<UpdateInfo> {
        val result = gitHubService.checkLatestRelease("fangzx2001", "OpencodeConnector4Android")
        return result.map { release ->
            if (VersionComparator.isNewer(BuildConfig.VERSION_NAME, release.tagName)) {
                val apkAsset = release.assets.firstOrNull { it.name.endsWith(".apk") }
                UpdateInfo.Available(
                    version = release.tagName.trimStart('v'),
                    changelog = release.body,
                    downloadUrl = apkAsset?.browserDownloadUrl,
                    releaseUrl = release.htmlUrl
                )
            } else {
                UpdateInfo.UpToDate
            }
        }
    }
}
