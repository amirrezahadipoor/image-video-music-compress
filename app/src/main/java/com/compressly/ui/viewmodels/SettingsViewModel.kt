package com.compressly.ui.viewmodels

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.compressly.CompresslyApp
import com.compressly.AppContainer
import com.compressly.core.engine.MediaInspector
import com.compressly.core.engine.JobControl
import com.compressly.core.engine.audio.WaveformSampler
import com.compressly.core.engine.estimate.SizeEstimator
import com.compressly.core.engine.model.AudioSettings
import com.compressly.core.engine.model.CompressionPreset
import com.compressly.core.engine.model.CompressionSettings
import com.compressly.core.engine.model.InputItem
import com.compressly.core.engine.model.MediaInfo
import com.compressly.core.engine.model.MediaType
import com.compressly.core.engine.model.PhotoFormat
import com.compressly.core.engine.model.PhotoResize
import com.compressly.core.engine.model.PhotoSettings
import com.compressly.core.engine.model.PresetDefaults
import com.compressly.core.engine.model.VideoSettings
import com.compressly.core.engine.photo.PhotoCompressor
import com.compressly.core.engine.video.CodecSupport
import com.compressly.core.util.Storage
import com.compressly.core.util.Uris
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/**
 * Drives the Compression Settings screen for a picked batch. Holds the
 * current preset + advanced settings, computes live size estimates, runs the
 * live before/after photo preview, and starts the job.
 */
class SettingsViewModel(private val container: AppContainer, private val context: Context) : ViewModel() {

    // ---- UI state --------------------------------------------------------

    data class UiState(
        val mediaType: MediaType = MediaType.PHOTO,
        val items: List<InputItem> = emptyList(),
        val preset: CompressionPreset = CompressionPreset.DEFAULT,
        val advanced: Boolean = false,
        val photo: PhotoSettings = PhotoSettings(),
        val video: VideoSettings = VideoSettings(),
        val audio: AudioSettings = AudioSettings(),
        val info: MediaInfo? = null,
        val originalSize: Long = 0L,
        val estimatedSize: Long = 0L,
        val h265Available: Boolean = true,
        val h265FellBack: Boolean = false,
        val preview: PreviewState = PreviewState.Idle,
        val waveform: List<Float> = emptyList(),
        val ready: Boolean = false,
        val lowSpaceWarning: Boolean = false,
        val startingJob: Boolean = false
    )

    sealed class PreviewState {
        data object Idle : PreviewState()
        data object Generating : PreviewState()
        data class Ready(val beforeUri: Uri, val afterUri: Uri, val afterSize: Long) : PreviewState()
        data object Failed : PreviewState()
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var previewJob: Job? = null
    private var lastPreviewFile: File? = null
    private var firstInfo: MediaInfo? = null

    init {
        val selection = container.selection.selection.value
        if (selection != null) {
            _state.update { it.copy(mediaType = selection.mediaType, items = selection.items, ready = true) }
            container.selection.set(null)
            loadMetadata()
        } else {
            _state.update { it.copy(ready = false) }
        }
    }

    private fun loadMetadata() {
        val s = _state.value
        val first = s.items.firstOrNull() ?: return
        viewModelScope.launch {
            val info = runCatching { MediaInspector.inspect(context, first.uri) }.getOrNull()
            firstInfo = info
            val originalSize = first.sizeBytes.takeIf { it > 0 } ?: Uris.sizeOf(context, first.uri)
            _state.update {
                it.copy(
                    info = info,
                    originalSize = originalSize,
                    h265Available = CodecSupport.hasEncoder("video/hevc")
                )
            }
            refreshEstimate()
            if (s.mediaType == MediaType.AUDIO) {
                runCatching { WaveformSampler.samplePeaks(context, first.uri) }
                    .getOrDefault(emptyList())
                    .let { peaks ->
                        if (peaks.isNotEmpty()) _state.update { st -> st.copy(waveform = peaks) }
                    }
            }
            if (s.mediaType == MediaType.PHOTO && s.items.size == 1) {
                regeneratePreview()
            }
        }
    }

    // ---- Preset & advanced ------------------------------------------------

    fun selectPreset(preset: CompressionPreset) {
        val s = _state.value
        _state.update {
            it.copy(
                preset = preset,
                photo = if (s.mediaType == MediaType.PHOTO) PresetDefaults.photoSettingsFor(preset) else it.photo,
                video = if (s.mediaType == MediaType.VIDEO) PresetDefaults.videoSettingsFor(preset) else it.video,
                audio = if (s.mediaType == MediaType.AUDIO) PresetDefaults.audioSettingsFor(preset) else it.audio
            )
        }
        refreshEstimate()
        regeneratePreview()
    }

    fun setAdvanced(advanced: Boolean) {
        _state.update { it.copy(advanced = advanced) }
    }

    // ---- Photo setters ----------------------------------------------------

    fun setPhotoFormat(format: PhotoFormat) {
        _state.update { it.copy(photo = it.photo.copy(outputFormat = format)) }
        refreshEstimate(); regeneratePreview()
    }

    fun setPhotoQuality(quality: Int) {
        val nearest = when {
            quality >= 90 -> CompressionPreset.MAXIMUM_QUALITY
            quality >= 70 -> CompressionPreset.BALANCED
            quality >= 50 -> CompressionPreset.HIGH_COMPRESSION
            else -> CompressionPreset.MAXIMUM_COMPRESSION
        }
        _state.update { it.copy(photo = it.photo.copy(quality = quality), preset = nearest) }
        refreshEstimate(); regeneratePreview()
    }

    fun setPhotoResize(resize: PhotoResize) {
        _state.update { it.copy(photo = it.photo.copy(resize = resize)) }
        refreshEstimate(); regeneratePreview()
    }

    fun setPhotoCustomWidth(width: Int) {
        _state.update { it.copy(photo = it.photo.copy(customMaxWidth = width.coerceIn(320, 8000))) }
        refreshEstimate(); regeneratePreview()
    }

    fun setPhotoMetadata(preserve: Boolean) {
        _state.update { it.copy(photo = it.photo.copy(preserveMetadata = preserve)) }
    }

    // ---- Video setters ----------------------------------------------------

    fun setVideoSettings(transform: (VideoSettings) -> VideoSettings) {
        _state.update { it.copy(video = transform(it.video)) }
        refreshEstimate()
    }

    // ---- Audio setters ----------------------------------------------------

    fun setAudioSettings(transform: (AudioSettings) -> AudioSettings) {
        _state.update { it.copy(audio = transform(it.audio)) }
        refreshEstimate()
    }

    // ---- Estimates --------------------------------------------------------

    private fun refreshEstimate() {
        val s = _state.value
        val info = s.info ?: return
        val estimate = when (s.mediaType) {
            MediaType.PHOTO -> SizeEstimator.estimatePhoto(
                context.contentResolver.getType(s.items.first().uri),
                info.effectiveWidth, info.effectiveHeight, s.originalSize, s.photo
            )
            MediaType.VIDEO -> SizeEstimator.estimateVideo(info, s.video, s.preset)
            MediaType.AUDIO -> SizeEstimator.estimateAudio(info.durationMs, s.audio)
        }
        _state.update { it.copy(estimatedSize = estimate) }
    }

    // ---- Photo live preview ------------------------------------------------

    fun regeneratePreview() {
        val s = _state.value
        if (s.mediaType != MediaType.PHOTO || s.items.size != 1) return
        previewJob?.cancel()
        val item = s.items.first()
        val settings = s.photo
        previewJob = viewModelScope.launch {
            _state.update { it.copy(preview = PreviewState.Generating) }
            delay(300) // debounce slider/setting changes
            val control = JobControl()
            try {
                // Preview renders at a capped size for speed; quality/format
                // settings still apply, so the visual trade-off is faithful.
                val previewSettings = settings.copy(
                    resize = if (settings.resize == PhotoResize.NONE) PhotoResize.R1920 else settings.resize,
                    customMaxWidth = settings.customMaxWidth.coerceAtMost(1920)
                )
                val mime = context.contentResolver.getType(item.uri)
                val beforeUri = item.uri
                val file = PhotoCompressor(context).compress(beforeUri, mime, previewSettings, control) { }
                lastPreviewFile?.delete()
                lastPreviewFile = file
                _state.update {
                    it.copy(preview = PreviewState.Ready(beforeUri, Uri.fromFile(file), file.length()))
                }
            } catch (e: Throwable) {
                if (e is com.compressly.core.engine.CompressionCancelledException) return@launch
                _state.update { it.copy(preview = PreviewState.Failed) }
            }
        }
    }

    // ---- Starting the job -------------------------------------------------

    fun compress(): Long? {
        val s = _state.value
        if (!s.ready || s.items.isEmpty()) return null

        val estimate = s.estimatedSize
        if (estimate > 0 && !Storage.hasEnoughSpace(estimate)) {
            _state.update { it.copy(lowSpaceWarning = true) }
            return null
        }

        val settings: CompressionSettings = when (s.mediaType) {
            MediaType.PHOTO -> CompressionSettings.Photo(s.photo, s.preset)
            MediaType.VIDEO -> {
                val effective = if (!s.h265Available && s.video.codec == com.compressly.core.engine.model.VideoCodec.H265) {
                    s.video.copy(codec = com.compressly.core.engine.model.VideoCodec.H264)
                } else s.video
                CompressionSettings.Video(effective, s.preset)
            }
            MediaType.AUDIO -> CompressionSettings.Audio(s.audio, s.preset)
        }
        val jobId = container.jobCoordinator.enqueue(s.mediaType, s.items, settings)
        _state.update { it.copy(startingJob = true) }
        return jobId
    }

    fun dismissLowSpaceWarning() {
        _state.update { it.copy(lowSpaceWarning = false) }
    }

    /** Starts the job even when the free-space check warned (user choice). */
    fun forceCompress(): Long? {
        _state.update { it.copy(lowSpaceWarning = false) }
        val s = _state.value
        if (!s.ready || s.items.isEmpty()) return null
        val settings: CompressionSettings = when (s.mediaType) {
            MediaType.PHOTO -> CompressionSettings.Photo(s.photo, s.preset)
            MediaType.VIDEO -> {
                val effective = if (!s.h265Available && s.video.codec == com.compressly.core.engine.model.VideoCodec.H265) {
                    s.video.copy(codec = com.compressly.core.engine.model.VideoCodec.H264)
                } else s.video
                CompressionSettings.Video(effective, s.preset)
            }
            MediaType.AUDIO -> CompressionSettings.Audio(s.audio, s.preset)
        }
        val jobId = container.jobCoordinator.enqueue(s.mediaType, s.items, settings)
        _state.update { it.copy(startingJob = true) }
        return jobId
    }

    override fun onCleared() {
        previewJob?.cancel()
        lastPreviewFile?.delete()
        super.onCleared()
    }

    companion object {
        fun factory(app: CompresslyApp, context: Context): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    SettingsViewModel(app.container, context.applicationContext) as T
            }
    }
}
