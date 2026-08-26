package com.compressly.ui.viewmodels

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.compressly.core.engine.JobControl
import com.compressly.core.engine.model.PhotoResize
import com.compressly.core.engine.model.PhotoSettings
import com.compressly.core.engine.photo.PhotoCompressor
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Separated from SettingsViewModel to keep concerns clean.
 * Manages the live before/after photo preview independently of job settings.
 * Cancels and debounces preview regeneration so slider changes don't
 * saturate the disk with temp files.
 */
class PhotoPreviewViewModel(private val context: Context) : ViewModel() {

    sealed class PreviewState {
        data object Idle      : PreviewState()
        data object Generating : PreviewState()
        data class  Ready(val beforeUri: Uri, val afterUri: Uri, val afterSize: Long) : PreviewState()
        data object Failed    : PreviewState()
    }

    private val _state = MutableStateFlow<PreviewState>(PreviewState.Idle)
    val state: StateFlow<PreviewState> = _state.asStateFlow()

    private var previewJob: Job? = null
    private var lastFile: File? = null

    fun requestPreview(sourceUri: Uri, settings: PhotoSettings, sourceMime: String?) {
        previewJob?.cancel()
        previewJob = viewModelScope.launch {
            _state.value = PreviewState.Generating
            delay(280) // debounce rapid slider changes
            val control = JobControl()
            try {
                val previewSettings = settings.copy(
                    resize = if (settings.resize == PhotoResize.NONE) PhotoResize.R1920 else settings.resize,
                    customMaxWidth = settings.customMaxWidth.coerceAtMost(1920)
                )
                val file = PhotoCompressor(context).compress(sourceUri, sourceMime, previewSettings, control) {}
                lastFile?.delete()
                lastFile = file
                _state.value = PreviewState.Ready(sourceUri, Uri.fromFile(file), file.length())
            } catch (e: Throwable) {
                if (e is com.compressly.core.engine.CompressionCancelledException) return@launch
                _state.value = PreviewState.Failed
            }
        }
    }

    override fun onCleared() {
        previewJob?.cancel()
        lastFile?.delete()
        super.onCleared()
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    PhotoPreviewViewModel(context.applicationContext) as T
            }
    }
}
