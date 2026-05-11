package com.opencode.remote.data.github

import io.github.g00fy2.versioncompare.Version

object VersionComparator {
    // Only accept pure version tags like "v1.2.0", "v1.3.1" — skip PR/pre-release/beta suffixes
    private val PURE_VERSION_REGEX = Regex("^v\\d+(\\.\\d+)*$")

    /** Check if a tag is a stable version (e.g. "v1.2.0"), not a PR variant (e.g. "v_pr2", "v1.1.2pr1") */
    fun isStableTag(tag: String): Boolean = PURE_VERSION_REGEX.matches(tag)

    /** Parse tag to a comparable Version. Returns Version(0) for invalid tags. */
    fun parseVersion(tag: String): Version {
        return try {
            Version(tag.trimStart('v'))
        } catch (e: Exception) {
            Version("0")
        }
    }

    fun isNewer(currentVersion: String, remoteTag: String): Boolean {
        if (remoteTag.isBlank()) return false
        if (!PURE_VERSION_REGEX.matches(remoteTag)) return false
        val remote = remoteTag.trimStart('v')
        if (remote.isBlank()) return false
        return try {
            Version(remote).isHigherThan(currentVersion)
        } catch (e: Exception) {
            false
        }
    }
}
