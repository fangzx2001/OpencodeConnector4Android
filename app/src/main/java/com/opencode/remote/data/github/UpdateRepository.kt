package com.opencode.remote.data.github

import com.opencode.remote.BuildConfig
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UpdateRepository @Inject constructor(
    private val gitHubService: GitHubReleaseService
) {
    suspend fun checkForUpdate(): Result<UpdateInfo> {
        val result = gitHubService.listReleases("fangzx2001", "OpencodeConnector4Android")
        return result.map { releases ->
            // Find the latest release with a pure version tag (skip PR/pre-release tags)
            val validRelease = releases
                .filter { VersionComparator.isStableTag(it.tagName) }
                .maxByOrNull { VersionComparator.parseVersion(it.tagName) }

            if (validRelease != null && VersionComparator.isNewer(BuildConfig.VERSION_NAME, validRelease.tagName)) {
                val apkAsset = validRelease.assets.firstOrNull { it.name.endsWith(".apk") }
                UpdateInfo.Available(
                    version = validRelease.tagName.trimStart('v'),
                    changelog = validRelease.body,
                    downloadUrl = apkAsset?.browserDownloadUrl,
                    releaseUrl = validRelease.htmlUrl
                )
            } else {
                UpdateInfo.UpToDate
            }
        }
    }
}
