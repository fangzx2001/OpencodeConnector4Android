package com.opencode.remote.ui.connection

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opencode.remote.data.api.dto.ServerInfo
import com.opencode.remote.data.datastore.ConnectionConfig
import com.opencode.remote.data.datastore.ConnectionPreferences
import com.opencode.remote.data.datastore.ServerManager
import com.opencode.remote.data.repository.OConnectorRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject

data class ConnectionUiState(
    val host: String = "",
    val port: String = "4096",
    val username: String = "",
    val password: String = "",
    val useTls: Boolean = false,
    val insecureTrust: Boolean = false,
    val serverName: String = "",
    val isConnecting: Boolean = false,
    val isConnected: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class ConnectionViewModel @Inject constructor(
    private val repository: OConnectorRepository,
    private val preferences: ConnectionPreferences,
    private val serverManager: ServerManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ConnectionUiState())
    val uiState: StateFlow<ConnectionUiState> = _uiState.asStateFlow()

    companion object {
        private const val TAG = "ConnectionViewModel"
    }

    init {
        viewModelScope.launch {
            // 只读取一次保存的配置，避免后续 saveConfig 写入时覆盖用户正在编辑的输入
            preferences.connectionConfig
                .take(1)
                .collect { config ->
                    _uiState.update {
                        it.copy(
                            host = config.host,
                            port = config.port.toString(),
                            username = config.username,
                            password = config.password,
                            useTls = config.useTls,
                            insecureTrust = config.insecureTrust,
                        )
                    }
                }
        }
    }

    fun onHostChange(host: String) {
        _uiState.update { it.copy(host = host, error = null) }
    }

    fun onPortChange(port: String) {
        // Only allow digits
        if (port.all { it.isDigit() } || port.isEmpty()) {
            _uiState.update { it.copy(port = port, error = null) }
        }
    }

    fun onUsernameChange(username: String) {
        _uiState.update { it.copy(username = username, error = null) }
    }

    fun onPasswordChange(password: String) {
        _uiState.update { it.copy(password = password, error = null) }
    }

    fun onServerNameChange(name: String) {
        _uiState.update { it.copy(serverName = name, error = null) }
    }

    fun onUseTlsChange(useTls: Boolean) {
        _uiState.update { it.copy(useTls = useTls) }
    }

    fun onInsecureTrustChange(insecureTrust: Boolean) {
        _uiState.update { it.copy(insecureTrust = insecureTrust) }
    }

    fun connect() {
        val state = _uiState.value
        val host = state.host.trim()
        val port = state.port.trim().toIntOrNull()
        val s = com.opencode.remote.ui.strings.AppLocale.strings

        if (host.isEmpty()) {
            _uiState.update { it.copy(error = s.errEnterIp) }
            return
        }
        if (port == null || port !in 1..65535) {
            _uiState.update { it.copy(error = s.errInvalidPort) }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isConnecting = true, error = null) }

            try {
                val config = ConnectionConfig(
                    host = host,
                    port = port,
                    username = state.username.trim(),
                    password = state.password,
                    useTls = state.useTls,
                    insecureTrust = state.insecureTrust,
                )

                // 在 IO 线程创建 HttpClient（避免在主线程加载引擎/依赖）
                withContext(Dispatchers.IO) {
                    repository.connect(config)
                }
                repository.setServerName(state.serverName.trim())

                // Save preferences
                preferences.saveConfig(config)

                // Test connection on IO thread
                val success = withContext(Dispatchers.IO) {
                    repository.testConnection()
                }

                if (success) {
                    // Pre-load agent list for later use
                    try {
                        withContext(Dispatchers.IO) { repository.listAgents() }
                    } catch (_: Exception) { /* non-critical */ }
                    _uiState.update { it.copy(isConnecting = false, isConnected = true) }
                } else {
                    repository.disconnect()
                    _uiState.update {
                        it.copy(
                            isConnecting = false,
                            error = s.errCannotConnect
                        )
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
                        error = s.errConnectionFailed.replace("%s", e.localizedMessage ?: e.javaClass.simpleName)
                    )
                }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun saveAndConnect() {
        val state = _uiState.value
        val host = state.host.trim()
        val port = state.port.trim().toIntOrNull()
        val s = com.opencode.remote.ui.strings.AppLocale.strings

        if (host.isEmpty()) {
            _uiState.update { it.copy(error = s.errEnterIp) }
            return
        }
        if (port == null || port !in 1..65535) {
            _uiState.update { it.copy(error = s.errInvalidPort) }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isConnecting = true, error = null) }

            try {
                val serverId = UUID.randomUUID().toString()
                val serverInfo = ServerInfo(
                    id = serverId,
                    name = state.serverName.trim(),
                    host = host,
                    port = port,
                    username = state.username.trim(),
                    useTls = state.useTls,
                    insecureTrust = state.insecureTrust,
                )
                serverManager.addServer(serverInfo, state.password)

                val config = ConnectionConfig(
                    serverId = serverId,
                    host = host,
                    port = port,
                    username = state.username.trim(),
                    password = state.password,
                    useTls = state.useTls,
                    insecureTrust = state.insecureTrust,
                )

                withContext(Dispatchers.IO) { repository.connect(config) }
                repository.setServerName(state.serverName.trim())

                val success = withContext(Dispatchers.IO) { repository.testConnection() }

                if (success) {
                    serverManager.saveLastActiveServerId(serverId)
                    _uiState.update { it.copy(isConnecting = false, isConnected = true) }
                } else {
                    repository.disconnect()
                    _uiState.update {
                        it.copy(
                            isConnecting = false,
                            error = s.errCannotConnect
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Save & connect failed", e)
                try {
                    repository.disconnect()
                } catch (disconnectError: Exception) {
                    Log.w(TAG, "Disconnect also failed", disconnectError)
                }
                _uiState.update {
                    it.copy(
                        isConnecting = false,
                        error = s.errConnectionFailed.replace("%s", e.localizedMessage ?: e.javaClass.simpleName)
                    )
                }
            }
        }
    }

    fun saveLanguage(lang: String) {
        viewModelScope.launch { preferences.saveLanguage(lang) }
    }

    /** Load persisted language into AppLocale. Call once at app startup. */
    fun loadLanguage() {
        viewModelScope.launch {
            preferences.language.take(1).collect { lang ->
                com.opencode.remote.ui.strings.AppLocale.language = lang
            }
        }
    }

    /** Load persisted dark mode into AppLocale. Call once at app startup. */
    fun loadDarkMode() {
        viewModelScope.launch {
            preferences.darkMode.take(1).collect { enabled ->
                com.opencode.remote.ui.strings.AppLocale.darkMode = enabled
            }
        }
    }
}
