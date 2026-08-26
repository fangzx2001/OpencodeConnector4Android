package com.opencode.remote.ui.sessions

import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opencode.remote.data.api.dto.SessionInfo
import com.opencode.remote.data.api.dto.MemoEntry
import com.opencode.remote.data.datastore.ConnectionPreferences
import com.opencode.remote.data.datastore.MemoManager
import com.opencode.remote.data.repository.OConnectorRepository
import com.opencode.remote.ui.strings.AppLocale
import com.opencode.remote.ui.strings.DarkModePref
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import com.opencode.remote.data.api.dto.ServerEvent
import com.opencode.remote.data.sse.SseEventBus
import com.opencode.remote.data.sessionstore.ActiveSessionStore
import com.opencode.remote.data.sessionstore.ChildSessionStore
import com.opencode.remote.data.sessionstore.SessionStatus
import com.opencode.remote.ui.util.TimeGroup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import javax.inject.Inject

enum class ListDensity { DEFAULT, COMPACT }

@Immutable
data class SessionsUiState(
    val sessions: List<SessionInfo> = emptyList(),
    val isLoading: Boolean = false,
    val isCreating: Boolean = false,
    val error: String? = null,
    /** project.worktree or project.id */
    val projectName: String? = null,
    val currentServerName: String? = null,
    // Memo panel
    val isMemoPanelOpen: Boolean = false,
    val memos: List<MemoEntry> = emptyList(),
    // Search
    val searchQuery: String = "",
    val isSearching: Boolean = false,
    // Agent loading error
    val availableAgentsError: Boolean = false,
    // Time-based grouping
    val groupedSessions: Map<TimeGroup, List<SessionInfo>> = emptyMap(),
    // Density
    val listDensity: ListDensity = ListDensity.DEFAULT,
    // Child session tree expansion
    val expandedParents: Set<String> = emptySet(),
)

@HiltViewModel
class SessionsViewModel @Inject constructor(
    private val repository: OConnectorRepository,
    private val prefs: ConnectionPreferences,
    private val sseEventBus: SseEventBus,
    private val memoManager: MemoManager,
    private val activeSessionStore: ActiveSessionStore,
    private val childSessionStore: ChildSessionStore,
) : ViewModel() {

    private var allSessions: List<SessionInfo> = emptyList()

    private val _uiState = MutableStateFlow(SessionsUiState())
    val uiState: StateFlow<SessionsUiState> = _uiState.asStateFlow()

    private val _creationEvents = MutableSharedFlow<String>()
    val creationEvents: SharedFlow<String> = _creationEvents.asSharedFlow()

    /** Expose active session status map from ActiveSessionStore. */
    val sessionStatusMap: StateFlow<Map<String, SessionStatus>> = activeSessionStore.statusMap

    /** Expose child session map from ChildSessionStore. */
    val childrenMap: StateFlow<Map<String, Set<String>>> = childSessionStore.childrenMap

    private var sseJob: Job? = null
    private var pollingJob: Job? = null
    private var searchJob: Job? = null

    companion object {
        private const val TAG = "SessionsViewModel"
        /** Debounce delay for search. Override in tests to 0 for synchronous behavior. */
        @VisibleForTesting
        var searchDebounceMs: Long = 300L
    }

    init {
        loadSessions()
        loadProjectName()
        loadCurrentServerName()
        observeDarkMode()
        observeListDensity()
        subscribeToSseEvents()
        startSessionsPolling()
    }

    private fun observeDarkMode() {
        viewModelScope.launch {
            prefs.darkModePref.collect { pref ->
                AppLocale.darkModePref = pref
            }
        }
    }

    fun toggleDarkMode() {
        viewModelScope.launch {
            val newValue = when (AppLocale.darkModePref) {
                DarkModePref.SYSTEM -> DarkModePref.DARK
                DarkModePref.DARK -> DarkModePref.LIGHT
                DarkModePref.LIGHT -> DarkModePref.SYSTEM
            }
            AppLocale.darkModePref = newValue
            prefs.saveDarkMode(newValue)
        }
    }

    private fun observeListDensity() {
        viewModelScope.launch {
            prefs.listDensity.collect { value ->
                val density = if (value == "compact") ListDensity.COMPACT else ListDensity.DEFAULT
                _uiState.update { it.copy(listDensity = density) }
            }
        }
    }

    fun toggleDensity() {
        viewModelScope.launch {
            val newValue = if (_uiState.value.listDensity == ListDensity.DEFAULT) "compact" else "default"
            prefs.saveListDensity(newValue)
        }
    }

    /** Toggle expand/collapse for a parent session's child tree. */
    fun toggleExpand(sessionId: String) {
        _uiState.update { state ->
            val expanded = state.expandedParents
            state.copy(
                expandedParents = if (sessionId in expanded) expanded - sessionId else expanded + sessionId
            )
        }
    }

    /** Get all sessions for a project directory from the unfiltered cache. */
    fun allSessionsForProject(directory: String): List<SessionInfo> =
        allSessions.filter { it.directory == directory }

    /** Check whether children for a parent need refreshing (not yet loaded). */
    fun shouldRefreshChildren(parentId: String): Boolean =
        !childSessionStore.hasLoadedChildren(parentId)

    /** Get children for a parent session synchronously. */
    fun getChildSessionIds(parentId: String): Set<String> =
        childSessionStore.getChildren(parentId)

    /** Refresh child sessions for a parent from the server. */
    fun refreshChildSessions(parentId: String) {
        viewModelScope.launch {
            childSessionStore.refreshChildren(parentId, repository)
        }
    }

    fun loadSessions() {
        viewModelScope.launch {
            // Only show spinner if there's no existing data (first load)
            val hasData = _uiState.value.sessions.isNotEmpty()
            if (!hasData) {
                _uiState.update { it.copy(isLoading = true, error = null) }
            }
            try {
                allSessions = repository.listAllSessions()
                val visibleSessions = allSessions.filter { it.parentID.isNullOrBlank() }
                _uiState.update {
                    it.copy(
                        sessions = visibleSessions,
                        isLoading = false,
                        groupedSessions = groupSessionsByTime(visibleSessions),
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load sessions", e)
                val s = com.opencode.remote.ui.strings.AppLocale.strings
                _uiState.update {
                    it.copy(isLoading = false, error = s.errLoadSessions.replace("%s", e.localizedMessage ?: e.javaClass.simpleName))
                }
            }
        }
    }

    private fun loadProjectName() {
        viewModelScope.launch {
            try {
                val project = repository.getCurrentProject()
                _uiState.update { it.copy(projectName = project.worktree ?: project.id) }
            } catch (e: Exception) { Log.w(TAG, "Failed to load project name", e) }
        }
    }

    private fun loadCurrentServerName() {
        val name = repository.getCurrentServerName()
        if (name != null) {
            _uiState.update { it.copy(currentServerName = name) }
        }
    }

    fun loadAgents() {
        viewModelScope.launch {
            try {
                repository.listAgents()
                _uiState.update { it.copy(availableAgentsError = false) }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load agents", e)
                _uiState.update { it.copy(availableAgentsError = true) }
            }
        }
    }

    fun setSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query, isSearching = query.isNotBlank()) }
        searchJob?.cancel()
        if (query.isBlank()) {
            // Cleared — immediately restore full list
            val visibleSessions = allSessions.filter { it.parentID.isNullOrBlank() }
            _uiState.update {
                it.copy(
                    sessions = visibleSessions,
                    isSearching = false,
                    groupedSessions = groupSessionsByTime(visibleSessions),
                )
            }
        } else {
            searchJob = viewModelScope.launch {
                delay(searchDebounceMs)
                val visibleSessions = allSessions.filter { it.parentID.isNullOrBlank() }
                val filtered = visibleSessions
                    .filter { session ->
                        session.title?.contains(query, ignoreCase = true) == true
                    }
                _uiState.update {
                    it.copy(
                        sessions = filtered,
                        groupedSessions = groupSessionsByTime(filtered),
                    )
                }
            }
        }
    }

    fun createSession(directory: String? = null) {
        viewModelScope.launch {
            _uiState.update { it.copy(isCreating = true, error = null) }
            try {
                val response = repository.createSession(directory)
                loadSessions()
                _uiState.update { it.copy(isCreating = false) }
                _creationEvents.emit(response.id)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create session", e)
                val s = com.opencode.remote.ui.strings.AppLocale.strings
                _uiState.update {
                    it.copy(isCreating = false, error = s.errCreateSession.replace("%s", e.localizedMessage ?: e.javaClass.simpleName))
                }
            }
        }
    }

    fun deleteSession(sessionId: String, directory: String? = null) {
        viewModelScope.launch {
            try {
                repository.deleteSession(sessionId, directory)
                loadSessions()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete session", e)
                val s = com.opencode.remote.ui.strings.AppLocale.strings
                _uiState.update { it.copy(error = s.errDeleteSession.replace("%s", e.localizedMessage ?: e.javaClass.simpleName)) }
            }
        }
    }

    fun forkSession(sessionId: String, directory: String? = null) {
        viewModelScope.launch {
            try {
                repository.forkSession(sessionId, directory)
                loadSessions()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fork session", e)
                val s = com.opencode.remote.ui.strings.AppLocale.strings
                _uiState.update { it.copy(error = s.errForkSession.replace("%s", e.localizedMessage ?: e.javaClass.simpleName)) }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    // ─── Memo Panel ────────────────────────────────────────────────

    fun setMemoPanelOpen(open: Boolean, directory: String) {
        _uiState.update { it.copy(isMemoPanelOpen = open) }
        if (open) {
            loadMemos(directory)
        }
    }

    fun closeMemoPanel() {
        _uiState.update { it.copy(isMemoPanelOpen = false, memos = emptyList()) }
    }

    private fun loadMemos(directory: String) {
        viewModelScope.launch {
            try {
                val memos = memoManager.loadMemos(directory)
                _uiState.update { it.copy(memos = memos.sortedByDescending { m -> m.updatedAt }) }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load memos", e)
            }
        }
    }

    fun addMemo(directory: String) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val entry = MemoEntry(
                id = java.util.UUID.randomUUID().toString(),
                directory = directory,
                title = "",
                content = "",
                isDone = false,
                createdAt = now,
                updatedAt = now,
            )
            memoManager.addMemo(entry)
            loadMemos(directory)
        }
    }

    fun updateMemo(directory: String, memo: MemoEntry) {
        viewModelScope.launch {
            memoManager.updateMemo(memo.copy(updatedAt = System.currentTimeMillis()))
            loadMemos(directory)
        }
    }

    fun deleteMemo(directory: String, memoId: String) {
        viewModelScope.launch {
            memoManager.deleteMemo(memoId)
            loadMemos(directory)
        }
    }

    fun toggleMemoDone(directory: String, memo: MemoEntry) {
        viewModelScope.launch {
            memoManager.updateMemo(memo.copy(
                isDone = !memo.isDone,
                updatedAt = System.currentTimeMillis(),
            ))
            loadMemos(directory)
        }
    }

    private fun subscribeToSseEvents() {
        sseJob = viewModelScope.launch {
            try {
                sseEventBus.events.collect { envelope ->
                    val type = envelope.event.payload.type
                    if (type == "session.updated" || type == "session.created" || type == "session.deleted") {
                        Log.d(TAG, "SSE session event: $type, refreshing sessions")
                        if (type == "session.deleted") {
                            val deletedId = envelope.event.payload.properties.sessionID
                            if (deletedId != null && deletedId in _uiState.value.expandedParents) {
                                _uiState.update { it.copy(expandedParents = it.expandedParents - deletedId) }
                            }
                        }
                        loadSessions()
                    }
                }
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    Log.w(TAG, "SSE subscription error in SessionsViewModel", e)
                }
            }
        }
    }

    private fun startSessionsPolling() {
        pollingJob = viewModelScope.launch {
            while (isActive) {
                delay(30_000)  // 30-second interval (listAllSessions is expensive)
                try {
                    loadSessions()
                } catch (e: Exception) {
                    Log.w(TAG, "Sessions polling error", e)
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        sseJob?.cancel()
        pollingJob?.cancel()
        searchJob?.cancel()
    }
}
