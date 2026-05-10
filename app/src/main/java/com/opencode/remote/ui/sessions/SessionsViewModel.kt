package com.opencode.remote.ui.sessions

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opencode.remote.data.api.dto.SessionInfo
import com.opencode.remote.data.datastore.ConnectionPreferences
import com.opencode.remote.data.repository.OConnectorRepository
import com.opencode.remote.ui.strings.AppLocale
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import com.opencode.remote.data.api.dto.ServerEvent
import com.opencode.remote.data.sse.SseEventBus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import javax.inject.Inject

data class SessionsUiState(
    val sessions: List<SessionInfo> = emptyList(),
    val isLoading: Boolean = false,
    val isCreating: Boolean = false,
    val error: String? = null,
    /** project.worktree or project.id */
    val projectName: String? = null,
    val hideChildSessions: Boolean = false,
)

@HiltViewModel
class SessionsViewModel @Inject constructor(
    private val repository: OConnectorRepository,
    private val prefs: ConnectionPreferences,
    private val sseEventBus: SseEventBus,
) : ViewModel() {

    private var allSessions: List<SessionInfo> = emptyList()

    private val _uiState = MutableStateFlow(SessionsUiState())
    val uiState: StateFlow<SessionsUiState> = _uiState.asStateFlow()

    private val _creationEvents = MutableSharedFlow<String>()
    val creationEvents: SharedFlow<String> = _creationEvents.asSharedFlow()

    private var sseJob: Job? = null
    private var pollingJob: Job? = null

    companion object {
        private const val TAG = "SessionsViewModel"
    }

    init {
        loadSessions()
        loadProjectName()
        observeDarkMode()
        observeHideChildSessions()
        subscribeToSseEvents()
        startSessionsPolling()
    }

    private fun observeDarkMode() {
        viewModelScope.launch {
            prefs.darkMode.collect { enabled ->
                AppLocale.darkMode = enabled
            }
        }
    }

    private fun observeHideChildSessions() {
        viewModelScope.launch {
            prefs.hideChildSessions.collect { enabled ->
                _uiState.update {
                    it.copy(
                        hideChildSessions = enabled,
                        sessions = filterVisibleSessions(allSessions, enabled),
                    )
                }
            }
        }
    }

    fun toggleDarkMode() {
        viewModelScope.launch {
            val newValue = !AppLocale.darkMode
            AppLocale.darkMode = newValue
            prefs.saveDarkMode(newValue)
        }
    }

    fun toggleHideChildSessions() {
        viewModelScope.launch {
            prefs.saveHideChildSessions(!_uiState.value.hideChildSessions)
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
                _uiState.update {
                    it.copy(
                        sessions = filterVisibleSessions(allSessions, it.hideChildSessions),
                        isLoading = false,
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

    private fun subscribeToSseEvents() {
        sseJob = viewModelScope.launch {
            try {
                sseEventBus.events.collect { event ->
                    val type = event.payload.type
                    if (type == "session.updated" || type == "session.created") {
                        Log.d(TAG, "SSE session event: $type, refreshing sessions")
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
    }
}
