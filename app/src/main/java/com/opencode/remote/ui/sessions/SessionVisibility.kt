package com.opencode.remote.ui.sessions

import com.opencode.remote.data.api.dto.SessionInfo

internal fun filterVisibleSessions(
    sessions: List<SessionInfo>,
    hideChildSessions: Boolean,
): List<SessionInfo> {
    if (!hideChildSessions) {
        return sessions
    }
    return sessions.filter { it.parentID.isNullOrBlank() }
}
