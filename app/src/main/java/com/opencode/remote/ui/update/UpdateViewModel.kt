package com.opencode.remote.ui.update

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opencode.remote.data.github.UpdateInfo
import com.opencode.remote.data.github.UpdateRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data object Checking : UpdateUiState
    data class Available(
        val version: String,
        val changelog: String?,
        val downloadUrl: String?,
        val releaseUrl: String
    ) : UpdateUiState
    data object UpToDate : UpdateUiState
    data class Error(val message: String) : UpdateUiState
}

@HiltViewModel
class UpdateViewModel @Inject constructor(
    private val updateRepository: UpdateRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val uiState: StateFlow<UpdateUiState> = _uiState.asStateFlow()

    fun checkForUpdate() {
        viewModelScope.launch {
            _uiState.value = UpdateUiState.Checking
            updateRepository.checkForUpdate()
                .onSuccess { info ->
                    _uiState.value = when (info) {
                        is UpdateInfo.Available -> UpdateUiState.Available(
                            version = info.version,
                            changelog = info.changelog,
                            downloadUrl = info.downloadUrl,
                            releaseUrl = info.releaseUrl
                        )
                        is UpdateInfo.UpToDate -> UpdateUiState.UpToDate
                    }
                }
                .onFailure { e ->
                    _uiState.value = UpdateUiState.Error(
                        e.message ?: "Unknown error"
                    )
                }
        }
    }
}
