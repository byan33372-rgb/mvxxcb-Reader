package com.mvxxcb.reader.presentation.settings

// 实现归属 WP-U（04 §4）。契约依据：02 §8.7（ReaderSettingsViewModel）。

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mvxxcb.reader.di.AppContainer
import com.mvxxcb.reader.domain.model.ReaderSettings
import com.mvxxcb.reader.domain.repository.ReaderSettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ReaderSettingsUiState(
    val isLoading: Boolean = true,
    val settings: ReaderSettings = ReaderSettings(),
)

class ReaderSettingsViewModel(
    private val readerSettingsRepository: ReaderSettingsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ReaderSettingsUiState())
    val uiState: StateFlow<ReaderSettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            readerSettingsRepository.settings
                .catch { e ->
                    _uiState.update { it.copy(isLoading = false) }
                    e
                }
                .collect { settings ->
                    _uiState.update { it.copy(isLoading = false, settings = settings) }
                }
        }
    }

    fun update(transform: (ReaderSettings) -> ReaderSettings) {
        viewModelScope.launch {
            readerSettingsRepository.update(transform)
        }
    }

    fun resetToDefault() {
        update { ReaderSettings() }
    }

    companion object {
        /** 手动 DI 创建入口（契约 02 §0.6） */
        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    ReaderSettingsViewModel(container.readerSettingsRepository) as T
            }
    }
}
