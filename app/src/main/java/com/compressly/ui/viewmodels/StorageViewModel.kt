package com.compressly.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.compressly.AppContainer
import com.compressly.CompresslyApp
import com.compressly.core.data.StorageRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * State for the storage dashboard (B6): device capacity, the largest media
 * files, and the total space the app has already freed for the user. All data
 * is gathered offline on the IO dispatcher.
 */
class StorageViewModel(container: AppContainer) : ViewModel() {

    data class UiState(
        val loading: Boolean = true,
        val totalBytes: Long = 0L,
        val freeBytes: Long = 0L,
        val largest: List<StorageRepository.StorageFileEntry> = emptyList(),
        /** Total bytes saved across completed compressions (from history). */
        val savedBytes: Long = 0L
    ) {
        val usedBytes: Long get() = (totalBytes - freeBytes).coerceAtLeast(0L)
        val usedFraction: Float get() = if (totalBytes > 0) usedBytes.toFloat() / totalBytes else 0f
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val storage = withContext(Dispatchers.IO) { container.storageRepository.deviceStorage() }
            val largest = withContext(Dispatchers.IO) { container.storageRepository.largestMedia(5) }
            val saved = container.historyRepository.totalSaved.first() ?: 0L
            _state.value = UiState(
                loading = false,
                totalBytes = storage?.totalBytes ?: 0L,
                freeBytes = storage?.freeBytes ?: 0L,
                largest = largest,
                savedBytes = saved
            )
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as CompresslyApp
                StorageViewModel(app.container)
            }
        }
    }
}
