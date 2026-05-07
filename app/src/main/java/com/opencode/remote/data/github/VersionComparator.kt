package com.opencode.remote.data.github

import io.github.g00fy2.versioncompare.Version

object VersionComparator {
    fun isNewer(currentVersion: String, remoteTag: String): Boolean {
        if (remoteTag.isBlank()) return false
        val remote = remoteTag.trimStart('v')
        if (remote.isBlank()) return false
        return try {
            Version(remote).isHigherThan(currentVersion)
        } catch (e: Exception) {
            false
        }
    }
}
