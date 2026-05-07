package com.opencode.remote.data.github

import org.junit.Assert.*
import org.junit.Test

class VersionComparatorTest {

    @Test
    fun `newer patch version is detected`() {
        assertTrue(VersionComparator.isNewer("1.1.1", "v1.1.2"))
    }

    @Test
    fun `same version is not newer`() {
        assertFalse(VersionComparator.isNewer("1.1.1", "v1.1.1"))
    }

    @Test
    fun `older version is not newer`() {
        assertFalse(VersionComparator.isNewer("1.1.1", "v1.1.0"))
    }

    @Test
    fun `major version bump is detected`() {
        assertTrue(VersionComparator.isNewer("1.9.9", "v2.0.0"))
    }

    @Test
    fun `multi-digit segment comparison`() {
        assertTrue(VersionComparator.isNewer("1.1.9", "v1.1.10"))
    }

    @Test
    fun `empty tag returns false`() {
        assertFalse(VersionComparator.isNewer("1.1.1", ""))
    }

    @Test
    fun `blank tag returns false`() {
        assertFalse(VersionComparator.isNewer("1.1.1", "   "))
    }

    @Test
    fun `tag without v prefix works`() {
        assertTrue(VersionComparator.isNewer("1.1.1", "1.1.2"))
    }
}
