package com.opencode.remote.ui.sessions

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opencode.remote.data.api.dto.SessionInfo
import com.opencode.remote.data.datastore.ConnectionPreferences
import com.opencode.remote.data.repository.OConnectorRepository
import com.opencode.remote.ui.strings.AppLocale
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import javax.inject.Inject

data class SessionUsageSummary(
    val usagePercent: Int?,
    val totalTokens: Int,
    val modelLimit: Int?,
)

data class SessionsUiState(
    val sessions: List<SessionInfo> = emptyList(),
    val sessionUsageById: Map<String, SessionUsageSummary> = emptyMap(),
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
) : ViewModel() {

    private var allSessions: List<SessionInfo> = emptyList()

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
        observeDarkMode()
        observeHideChildSessions()
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

    fun loadProjectName() {
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

    fun loadSessionUsageForDirectory(directory: String) {
        val visibleSessions = _uiState.value.sessions.filter { it.directory == directory }
        if (visibleSessions.isEmpty()) return

        viewModelScope.launch {
            try {
                val providers = repository.listProviders()
                val usageEntries = coroutineScope {
                    visibleSessions.map { session ->
                        async {
                            val messages = repository.getMessages(session.id, directory)
                            session.id to resolveSessionUsage(messages, providers)
                        }
                    }.awaitAll()
                }

                _uiState.update {
                    it.copy(
                        sessionUsageById = it.sessionUsageById + usageEntries.filter { (_, usage) -> usage != null }
                            .associate { (sessionId, usage) -> sessionId to usage!! },
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load session usage for directory=$directory", e)
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

    fun renameSession(sessionId: String, title: String, directory: String? = null, onSuccess: (() -> Unit)? = null) {
        viewModelScope.launch {
            val trimmed = title.trim()
            if (trimmed.isEmpty()) return@launch
            try {
                val updated = repository.updateSessionTitle(sessionId, trimmed, directory)
                replaceSession(updated)
                onSuccess?.invoke()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to rename session", e)
                val s = com.opencode.remote.ui.strings.AppLocale.strings
                _uiState.update { it.copy(error = s.errRenameSession.replace("%s", e.localizedMessage ?: e.javaClass.simpleName)) }
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

    fun toggleShareSession(sessionId: String, shared: Boolean, directory: String? = null, onSuccess: ((SessionInfo) -> Unit)? = null) {
        viewModelScope.launch {
            try {
                val updated = if (shared) {
                    repository.unshareSession(sessionId, directory)
                } else {
                    repository.shareSession(sessionId, directory)
                }
                replaceSession(updated)
                onSuccess?.invoke(updated)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to toggle share state", e)
                val s = com.opencode.remote.ui.strings.AppLocale.strings
                _uiState.update { it.copy(error = s.errShareSession.replace("%s", e.localizedMessage ?: e.javaClass.simpleName)) }
            }
        }
    }

    private fun replaceSession(updated: SessionInfo) {
        allSessions = allSessions.map { current ->
            if (current.id == updated.id) updated else current
        }
        _uiState.update { it.copy(sessions = filterVisibleSessions(allSessions, it.hideChildSessions)) }
    }

    fun logout(onComplete: () -> Unit) {
        viewModelScope.launch {
            try {
                repository.disconnect()
            } catch (e: Exception) {
                Log.w(TAG, "Disconnect during logout failed", e)
            }
            prefs.saveAutoLoginEnabled(false)
            onComplete()
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    private fun resolveSessionUsage(
        messages: List<com.opencode.remote.data.api.dto.MessageInfo>,
        providers: List<com.opencode.remote.data.api.dto.ProviderInfo>,
    ): SessionUsageSummary? {
        val latestAssistantWithTokens = messages.asReversed().firstOrNull { message ->
            message.role == "assistant" && message.tokenTotal() > 0
        } ?: return null

        val providerId = latestAssistantWithTokens.info.resolvedProviderID ?: messages.asReversed()
            .firstNotNullOfOrNull { message ->
                if (message.role != "assistant") return@firstNotNullOfOrNull null
                message.info.resolvedProviderID
            }
        val modelId = latestAssistantWithTokens.info.resolvedModelID ?: messages.asReversed()
            .firstNotNullOfOrNull { message ->
                if (message.role != "assistant") return@firstNotNullOfOrNull null
                message.info.resolvedModelID
            }
        val contextLimit = findContextLimit(providers, providerId, modelId)
        val totalTokens = latestAssistantWithTokens.tokenTotal()
        val usagePercent = if (contextLimit != null && contextLimit > 0) {
            ((totalTokens.toDouble() / contextLimit.toDouble()) * 100).roundToInt().coerceIn(0, 999)
        } else {
            null
        }

        return SessionUsageSummary(
            usagePercent = usagePercent,
            totalTokens = totalTokens,
            modelLimit = contextLimit,
        )
    }

    private fun findContextLimit(
        providers: List<com.opencode.remote.data.api.dto.ProviderInfo>,
        providerId: String?,
        modelId: String?,
    ): Int? {
        if (providerId.isNullOrBlank() || modelId.isNullOrBlank()) return null
        val provider = providers.firstOrNull { it.id == providerId } ?: return null
        return provider.models[modelId]?.limit?.context
            ?: provider.models.values.firstOrNull { model ->
                model.id == modelId || model.name == modelId
            }?.limit?.context
    }

    private fun com.opencode.remote.data.api.dto.MessageTokens?.tokenTotal(): Int {
        if (this == null) return 0
        return total ?: (
            (input ?: 0) +
                (output ?: 0) +
                (reasoning ?: 0) +
                (cacheRead ?: cache?.read ?: 0) +
                (cacheWrite ?: cache?.write ?: 0)
            )
    }

    private fun com.opencode.remote.data.api.dto.MessageInfo.tokenTotal(): Int {
        val infoTotal = info.tokens.tokenTotal()
        if (infoTotal > 0) return infoTotal
        return parts.sumOf { it.tokens.tokenTotal() }
    }
}
