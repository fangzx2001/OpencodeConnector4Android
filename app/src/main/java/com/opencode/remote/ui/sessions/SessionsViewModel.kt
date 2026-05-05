package com.opencode.remote.ui.sessions

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opencode.remote.data.api.dto.SessionInfo
import com.opencode.remote.data.repository.OConnectorRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SessionsUiState(
    val sessions: List<SessionInfo> = emptyList(),
    val isLoading: Boolean = false,
    val isCreating: Boolean = false,
    val error: String? = null,
    /** project.worktree or project.id */
    val projectName: String? = null,
)

@HiltViewModel
class SessionsViewModel @Inject constructor(
    private val repository: OConnectorRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SessionsUiState())
    val uiState: StateFlow<SessionsUiState> = _uiState.asStateFlow()

    private val _creationEvents = MutableSharedFlow<String>()
    val creationEvents: SharedFlow<String> = _creationEvents.asSharedFlow()

    companion object {
        private const val TAG = "SessionsViewModel"
    }

    init {
        loadSessions()
        loadProjectName()
    }

    fun loadSessions() {
        viewModelScope.launch {
            // Only show spinner if there's no existing data (first load)
            val hasData = _uiState.value.sessions.isNotEmpty()
            if (!hasData) {
                _uiState.update { it.copy(isLoading = true, error = null) }
            }
            try {
                val sessions = repository.listSessions()
                _uiState.update { it.copy(sessions = sessions, isLoading = false) }
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
}
