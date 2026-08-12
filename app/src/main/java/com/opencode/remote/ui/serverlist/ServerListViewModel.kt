package com.opencode.remote.ui.serverlist

import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opencode.remote.data.api.dto.ServerInfo
import com.opencode.remote.data.datastore.ConnectionConfig
import com.opencode.remote.data.datastore.ConnectionPreferences
import com.opencode.remote.data.datastore.ServerManager
import com.opencode.remote.data.repository.OConnectorRepository
import com.opencode.remote.ui.strings.AppLocale
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class ServerListViewModel @Inject constructor(
    private val serverManager: ServerManager,
    private val repository: OConnectorRepository,
    private val preferences: ConnectionPreferences,
) : ViewModel() {

    @Immutable
    data class ServerListUiState(
        val servers: List<ServerInfo> = emptyList(),
        val isConnecting: Boolean = false,
        val connectedServerId: String? = null,
        val error: String? = null,
    )

    private val _uiState = MutableStateFlow(ServerListUiState())
    val uiState: StateFlow<ServerListUiState> = _uiState.asStateFlow()

    companion object {
        private const val TAG = "ServerListViewModel"
    }

    init {
        loadPreferences()
        viewModelScope.launch {
            serverManager.migrateIfNeeded()
        }
        viewModelScope.launch {
            serverManager.servers.collect { list ->
                _uiState.update { it.copy(servers = list) }
            }
        }
        // Auto-connect to last active server at startup (for notification deep links)
        viewModelScope.launch {
            val lastId = serverManager.lastActiveServerId.first()
            if (lastId != null && !repository.isConnected) {
                connectToServer(lastId)
            }
        }
    }

    fun connectToServer(serverId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isConnecting = true, error = null) }

            val server = _uiState.value.servers.find { it.id == serverId }
            if (server == null) {
                _uiState.update {
                    it.copy(isConnecting = false, error = "Server not found")
                }
                return@launch
            }

            // Read password on IO thread — EncryptedSharedPreferences init involves Keystore I/O
            val password = withContext(Dispatchers.IO) {
                serverManager.getPassword(serverId) ?: ""
            }

            val config = ConnectionConfig(
                serverId = server.id,
                host = server.host,
                port = server.port,
                username = server.username,
                password = password,
                useTls = server.useTls,
                insecureTrust = server.insecureTrust,
            )

            try {
                withContext(Dispatchers.IO) {
                    repository.connect(config)
                }
                repository.setServerName(server.name)

                val success = withContext(Dispatchers.IO) {
                    repository.testConnection()
                }

                if (success) {
                    // Only start SSE service after connection is confirmed reachable
                    repository.startSseService()
                    serverManager.saveLastActiveServerId(serverId)
                    _uiState.update {
                        it.copy(
                            isConnecting = false,
                            connectedServerId = serverId,
                        )
                    }
                } else {
                    repository.disconnect()
                    _uiState.update {
                        it.copy(isConnecting = false, error = "Connection test failed")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Connection failed", e)
                try {
                    repository.disconnect()
                } catch (disconnectError: Exception) {
                    Log.w(TAG, "Disconnect also failed", disconnectError)
                }
                _uiState.update {
                    it.copy(
                        isConnecting = false,
                        error = e.localizedMessage ?: "Connection failed",
                    )
                }
            }
        }
    }

    fun deleteServer(id: String) {
        viewModelScope.launch {
            serverManager.deleteServer(id)
        }
    }

    /** Load persisted language and dark mode into AppLocale at startup. */
    private fun loadPreferences() {
        viewModelScope.launch {
            preferences.language.take(1).collect { lang ->
                AppLocale.language = lang
            }
            preferences.darkMode.take(1).collect { enabled ->
                AppLocale.darkMode = enabled
            }
        }
    }

    fun toggleLanguage() {
        viewModelScope.launch {
            val newLang = if (AppLocale.language == "en") "zh" else "en"
            AppLocale.language = newLang
            preferences.saveLanguage(newLang)
        }
    }
}
