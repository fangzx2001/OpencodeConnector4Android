package com.opencode.remote.ui.sessions

import com.opencode.remote.data.api.dto.SessionInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionVisibilityTest {

    @Test
    fun `returns all sessions when child filtering disabled`() {
        val sessions = listOf(
            SessionInfo(id = "root", parentID = null),
            SessionInfo(id = "child", parentID = "root"),
        )

        assertEquals(sessions, filterVisibleSessions(sessions, hideChildSessions = false))
    }

    @Test
    fun `filters child sessions when child filtering enabled`() {
        val root = SessionInfo(id = "root", parentID = null)
        val blankParent = SessionInfo(id = "root-blank", parentID = "   ")
        val child = SessionInfo(id = "child", parentID = "root")

        assertEquals(
            listOf(root, blankParent),
            filterVisibleSessions(listOf(root, blankParent, child), hideChildSessions = true),
        )
    }
}
