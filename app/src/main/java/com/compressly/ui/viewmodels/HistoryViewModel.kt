package com.compressly.ui.viewmodels

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.compressly.CompresslyApp
import com.compressly.AppContainer
import com.compressly.core.data.db.HistoryEntry
import com.compressly.core.engine.model.MediaType
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HistoryViewModel(container: AppContainer) : ViewModel() {

    private val repository = container.historyRepository

    val entries: StateFlow<List<HistoryEntry>> = repository.all
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val totalSaved: StateFlow<Long> = repository.totalSaved
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    fun clear() {
        viewModelScope.launch { repository.clear() }
    }

    /** Bulk-deletes the given history rows (multi-select). */
    fun delete(ids: List<Long>) {
        if (ids.isEmpty()) return
        viewModelScope.launch { repository.delete(ids) }
    }

    fun share(context: Context, entry: HistoryEntry) {
        val uri = entry.outputUri?.let { Uri.parse(it) } ?: return
        val mime = when (MediaType.fromName(entry.mediaType)) {
            MediaType.PHOTO -> "image/*"
            MediaType.VIDEO -> "video/*"
            MediaType.AUDIO -> "audio/*"
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        // createChooser can strip URI grant flags on Android 10+;
        // re-add them to the chooser intent so the receiving app can read.
        val chooser = Intent.createChooser(intent, null).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { context.startActivity(chooser) }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as CompresslyApp
                HistoryViewModel(app.container)
            }
        }
    }
}
