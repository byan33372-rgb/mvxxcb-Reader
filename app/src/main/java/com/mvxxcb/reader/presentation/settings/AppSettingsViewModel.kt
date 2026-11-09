package com.mvxxcb.reader.presentation.settings

// 实现归属 WP-U（04 §4）。契约依据：02 §8.7（AppSettingsViewModel）。

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mvxxcb.reader.di.AppContainer
import com.mvxxcb.reader.domain.model.AppPreferences
import com.mvxxcb.reader.domain.repository.AppPreferencesRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AppSettingsUiState(
    val isLoading: Boolean = true,
    val preferences: AppPreferences = AppPreferences(),
)

class AppSettingsViewModel(
    private val appPreferencesRepository: AppPreferencesRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AppSettingsUiState())
    val uiState: StateFlow<AppSettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            appPreferencesRepository.preferences
                .catch { e ->
                    _uiState.update { it.copy(isLoading = false) }
                    e
                }
                .collect { preferences ->
                    _uiState.update { it.copy(isLoading = false, preferences = preferences) }
                }
        }
    }

    fun update(transform: (AppPreferences) -> AppPreferences) {
        viewModelScope.launch {
            appPreferencesRepository.update(transform)
        }
    }

    companion object {
        /** 手动 DI 创建入口（契约 02 §0.6） */
        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    AppSettingsViewModel(container.appPreferencesRepository) as T
            }
    }
}
