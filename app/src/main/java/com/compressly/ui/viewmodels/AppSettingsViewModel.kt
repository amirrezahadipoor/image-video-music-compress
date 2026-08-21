package com.compressly.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.compressly.CompresslyApp
import com.compressly.AppContainer
import com.compressly.core.data.SettingsRepository
import com.compressly.core.data.ThemeMode
import com.compressly.core.engine.model.CompressionPreset
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AppSettingsViewModel(container: AppContainer) : ViewModel() {

    private val repository: SettingsRepository = container.settingsRepository

    val themeMode: StateFlow<ThemeMode> = repository.themeMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThemeMode.SYSTEM)

    val defaultPreset: StateFlow<CompressionPreset> = repository.defaultPreset
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CompressionPreset.DEFAULT)

    val preserveMetadataDefault: StateFlow<Boolean> = repository.preserveMetadataDefault
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { repository.setThemeMode(mode) }
    }

    fun setDefaultPreset(preset: CompressionPreset) {
        viewModelScope.launch { repository.setDefaultPreset(preset) }
    }

    fun setPreserveMetadataDefault(value: Boolean) {
        viewModelScope.launch { repository.setPreserveMetadataDefault(value) }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as CompresslyApp
                AppSettingsViewModel(app.container)
            }
        }
    }
}
