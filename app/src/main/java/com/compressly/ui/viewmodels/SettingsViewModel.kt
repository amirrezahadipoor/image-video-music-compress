package com.compressly.ui.viewmodels

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.compressly.CompresslyApp
import com.compressly.AppContainer
import com.compressly.Selection
import com.compressly.core.engine.MediaInspector
import com.compressly.core.engine.JobControl
import com.compressly.core.engine.audio.WaveformSampler
import com.compressly.core.engine.estimate.GradeAdvisor
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
        val smart: Boolean = true,
        val advanced: Boolean = false,
        val photo: PhotoSettings = PhotoSettings(),
        val video: VideoSettings = VideoSettings(),
        val audio: AudioSettings = AudioSettings(),
        val info: MediaInfo? = null,
        val originalSize: Long = 0L,
        val estimatedSize: Long = 0L,
        val h265Available: Boolean = true,
        val av1Available: Boolean = false,
        val h265FellBack: Boolean = false,
        val preview: PreviewState = PreviewState.Idle,
        val waveform: List<Float> = emptyList(),
        val ready: Boolean = false,
        val lowSpaceWarning: Boolean = false,
        val startingJob: Boolean = false,
        val analyzing: Boolean = true,
        val recommended: CompressionPreset? = null,
        val gradeEstimates: Map<CompressionPreset, Long> = emptyMap(),
        /** REPLACE-ORIGINAL: delete the source file after a successful compression. */
        val replaceOriginal: Boolean = false,
        /** OUTPUT-LOCATION: where the result is written (default / same as source / custom). */
        val outputLocation: com.compressly.core.engine.model.OutputLocation =
            com.compressly.core.engine.model.OutputLocation.DEFAULT,
        /** Per-job custom folder (SAF tree URI) used when outputLocation == CUSTOM. */
        val outputFolder: String? = null
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
    /**
     * BATCH-ESTIMATE-FIX: probed info for the first up-to-three files of the
     * batch (in item order). The headline batch estimate is the AVERAGE of
     * these files' estimates, not one file's number multiplied by the count
     * — for a mixed-size batch (a 40 MB video next to a 2 MB one) the old
     * rule could be off by several times.
     */
    private var batchInfos: List<Pair<InputItem, MediaInfo>> = emptyList()
    /** Once the user taps a grade, analysis must not override it. */
    private var userPickedGrade = false

    init {
        // Atomically consume the selection to avoid losing it on fast config changes.
        var selection: Selection? = null
        container.selection.selection.value?.let { sel ->
            selection = sel
            container.selection.set(null)
        }
        if (selection != null) {
            val sel = selection!!
            _state.update { it.copy(mediaType = sel.mediaType, items = sel.items, ready = true) }
            // Apply the user's saved defaults (Smart unless changed).
            viewModelScope.launch {
                val def = container.settingsRepository.defaultPreset.first()
                // "Preserve metadata by default" from app settings. This used to
                // be read, stored and shown as a toggle, but photoSettingsFor()
                // and audioSettingsFor() both hardcode preserveMetadata = true,
                // so the switch did nothing: a user who asked for location and
                // camera data to be stripped by default got it kept on every
                // single compression.
                val preserve = container.settingsRepository.preserveMetadataDefault.first()
                applyPreset(def, preserveMetadata = preserve)
            }
            loadMetadata()
        } else {
            _state.update { it.copy(ready = false) }
        }
    }

    private fun loadMetadata() {
        val s = _state.value
        val first = s.items.firstOrNull() ?: return
        viewModelScope.launch {
            // MAIN-THREAD-FIX: inspect() runs MediaMetadataRetriever + a
            // MediaExtractor track probe against the URI — a binder call plus
            // file I/O that can take well over 100 ms on slow devices. It used
            // to run on the main thread (this scope), stuttering or ANRing
            // every time the settings screen opened.
            val info = withContext(Dispatchers.IO) {
                runCatching { MediaInspector.inspect(context, first.uri) }.getOrNull()
            }
            val originalSize = first.sizeBytes.takeIf { it > 0 }
                ?: withContext(Dispatchers.IO) { Uris.sizeOf(context, first.uri) }
            // Probe the next two files too (still on IO) for the batch
            // average. The first file stays the one the grade advice and the
            // per-grade picker prices.
            batchInfos = buildList {
                info?.let { add(first to it) }
                for (item in s.items.drop(1).take(2)) {
                    val more = withContext(Dispatchers.IO) {
                        runCatching { MediaInspector.inspect(context, item.uri) }.getOrNull()
                    }
                    if (more != null) add(item to more)
                }
            }
            _state.update {
                it.copy(
                    info = info,
                    originalSize = originalSize,
                    h265Available = CodecSupport.hasEncoder("video/hevc"),
                    av1Available = CodecSupport.hasEncoder("video/av01")
                )
            }
            // ANALYSIS-FIX: videos get a content probe before the numbers are
            // promised. The enriched MediaInfo feeds both the grade advice and
            // the live estimate, so the Smart budget shown to the user is the
            // one the encoder will really use.
            if (s.mediaType == MediaType.VIDEO && info?.hasVideo == true && info.durationMs > 0) {
                analyseContent(first.uri, info, originalSize)
            } else {
                applyAnalysis(info, originalSize, first.uri)
                refreshEstimate()
            }
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

    /**
     * Runs the content probe for a video and folds the measurement into the
     * same [MediaInfo] the estimate is computed from. Non-fatal: any failure
     * leaves the neutral path in place.
     */
    private fun analyseContent(uri: Uri, base: MediaInfo, originalSize: Long) {
        viewModelScope.launch(Dispatchers.Default) {
            val analysed = runCatching {
                com.compressly.core.engine.analysis.ComplexityAnalyzer.analyze(
                    context, uri, base.durationMs
                )
            }.getOrNull()
            val enriched = if (analysed != null) base.copy(
                complexity = analysed.complexity,
                motion = analysed.motion,
                detail = analysed.detail,
                color = analysed.color,
                sceneCuts = analysed.sceneCuts
            ) else base
            _state.update { it.copy(info = enriched) }
            applyAnalysis(enriched, originalSize, uri)
            refreshEstimate()
        }
    }

    // ---- Preset & advanced ------------------------------------------------

    fun selectPreset(preset: CompressionPreset) {
        userPickedGrade = true
        applyPreset(preset)
    }

    private fun applyPreset(preset: CompressionPreset, preserveMetadata: Boolean? = null) {
        val s = _state.value
        val smart = preset == CompressionPreset.SMART
        // The level owns quality and rate, not the metadata choice. On first
        // load it comes from the user's saved default; after that, changing the
        // level carries the current choice across instead of resetting it.
        val photoMeta = preserveMetadata ?: s.photo.preserveMetadata
        val audioMeta = preserveMetadata ?: s.audio.preserveMetadata
        _state.update {
            it.copy(
                preset = preset,
                smart = smart,
                photo = if (s.mediaType == MediaType.PHOTO)
                    PresetDefaults.photoSettingsFor(preset).copy(preserveMetadata = photoMeta)
                else it.photo,
                // Pass the current video settings so codec / custom size / trim
                // survive a level change instead of being silently reset.
                video = if (s.mediaType == MediaType.VIDEO) PresetDefaults.videoSettingsFor(preset, it.video) else it.video,
                audio = if (s.mediaType == MediaType.AUDIO)
                    PresetDefaults.audioSettingsFor(preset).copy(preserveMetadata = audioMeta)
                else it.audio
            )
        }
        refreshEstimate()
        regeneratePreview()
    }

    /** Any manual tweak turns smart mode off (the user is now in control). */
    private fun disableSmart() {
        _state.update {
            it.copy(
                smart = false,
                preset = if (it.preset == CompressionPreset.SMART) CompressionPreset.BALANCED else it.preset
            )
        }
    }

    fun setAdvanced(advanced: Boolean) {
        _state.update { it.copy(advanced = advanced) }
    }

    // ---- Photo setters ----------------------------------------------------

    fun setPhotoFormat(format: PhotoFormat) {
        _state.update { it.copy(photo = it.photo.copy(outputFormat = format)) }
        disableSmart()
        refreshEstimate(); regeneratePreview()
    }

    fun setPhotoQuality(quality: Int) {
        val nearest = when {
            quality >= 90 -> CompressionPreset.MAXIMUM_QUALITY
            quality >= 70 -> CompressionPreset.BALANCED
            quality >= 50 -> CompressionPreset.HIGH_COMPRESSION
            else -> CompressionPreset.MAXIMUM_COMPRESSION
        }
        _state.update {
            it.copy(photo = it.photo.copy(quality = quality), preset = nearest, smart = false)
        }
        refreshEstimate(); regeneratePreview()
    }

    fun setPhotoResize(resize: PhotoResize) {
        _state.update { it.copy(photo = it.photo.copy(resize = resize)) }
        disableSmart()
        refreshEstimate(); regeneratePreview()
    }

    fun setPhotoCustomWidth(width: Int) {
        _state.update { it.copy(photo = it.photo.copy(customMaxWidth = width.coerceIn(320, 8000))) }
        disableSmart()
        refreshEstimate(); regeneratePreview()
    }

    fun setPhotoMetadata(preserve: Boolean) {
        _state.update { it.copy(photo = it.photo.copy(preserveMetadata = preserve)) }
    }

    /**
     * BATCH-REMOVE: drops one file from the configured batch. If the removed
     * file was the one the analysis ran on, the whole analysis re-runs on the
     * new first file so sizes, estimates and grade advice stay truthful.
     * Removing the last file returns the user to Home (ready = false).
     */
    fun removeItem(itemId: Long) {
        val wasFirst = state.value.items.firstOrNull()?.itemId == itemId
        val remaining = state.value.items.filterNot { it.itemId == itemId }
        _state.update { s -> s.copy(items = remaining, ready = remaining.isNotEmpty()) }
        if (remaining.isEmpty()) return
        if (wasFirst) loadMetadata()
    }

    // ---- Video setters ----------------------------------------------------

    fun setVideoSettings(transform: (VideoSettings) -> VideoSettings) {
        disableSmart()
        _state.update { it.copy(video = transform(it.video)) }
        refreshEstimate()
    }

    // ---- Audio setters ----------------------------------------------------

    fun setAudioSettings(transform: (AudioSettings) -> AudioSettings) {
        disableSmart()
        _state.update { it.copy(audio = transform(it.audio)) }
        refreshEstimate()
    }

    // ---- Estimates --------------------------------------------------------

    private fun applyAnalysis(info: MediaInfo?, originalSize: Long, uri: Uri) {
        if (info == null || originalSize <= 0L) {
            _state.update { it.copy(analyzing = false) }
            if (!userPickedGrade && _state.value.preset == CompressionPreset.SMART) {
                applyPreset(CompressionPreset.BALANCED)
            }
            return
        }
        val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull()
        val snap = GradeAdvisor.advise(_state.value.mediaType, info, originalSize, mime)
        val shouldSuggest = !userPickedGrade && _state.value.preset == CompressionPreset.SMART
        _state.update {
            it.copy(
                analyzing = false,
                recommended = snap.recommended,
                gradeEstimates = snap.estimates
            )
        }
        if (shouldSuggest) applyPreset(snap.recommended)
    }

    private fun refreshEstimate() {
        val s = _state.value
        val info = s.info ?: return
        val first = s.items.first()
        // Per-file estimate at the CURRENT settings; the first file uses the
        // measured original size, the others their probed sizes (skipped when
        // unknown, rather than feeding -1 into the math).
        fun estimateOf(item: InputItem, fileInfo: MediaInfo): Long? {
            val size = if (item.itemId == first.itemId) s.originalSize
            else item.sizeBytes.takeIf { it > 0 } ?: return null
            return when (s.mediaType) {
                MediaType.PHOTO -> SizeEstimator.estimatePhoto(
                    context.contentResolver.getType(item.uri),
                    fileInfo.effectiveWidth, fileInfo.effectiveHeight, size, s.photo
                )
                MediaType.VIDEO -> SizeEstimator.estimateVideo(fileInfo, s.video, s.preset)
                MediaType.AUDIO -> SizeEstimator.estimateAudio(fileInfo, s.audio)
            }
        }
        val firstEstimate = estimateOf(first, info) ?: 0L
        val batchAverage = if (s.items.size > 1) {
            val infoByItem = batchInfos.associate { it.first.itemId to it.second }
            s.items.take(3).mapNotNull { item ->
                val fileInfo = if (item.itemId == first.itemId) info
                else infoByItem[item.itemId] ?: return@mapNotNull null
                estimateOf(item, fileInfo)
            }.let { estimates ->
                if (estimates.size > 1) estimates.average().toLong() else 0L
            }
        } else 0L
        _state.update {
            it.copy(estimatedSize = if (batchAverage > 0) batchAverage else firstEstimate)
        }
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
        // DOUBLE-TAP-FIX: `startingJob` used to be written and never read, so a
        // second tap on the compress bar enqueued the same files a second time —
        // two jobs over one selection, and the second one writing into files the
        // first had already moved. The flag is the lock now.
        if (!s.ready || s.items.isEmpty() || s.startingJob) return null

        // SPACE-FIX: estimatedSize is the estimate for the FIRST file only, so
        // checking one file's worth used to let a 50-file batch start and die
        // mid-way with an IOException. The naive fix (first x N) then over-warned
        // on mixed folders -- and every false warning was also a way to reach
        // "compress anyway", which is the button that skipped the replace-consent
        // prompt. Both halves now share one rule: JobTotals.estimateBatchBytes.
        val batchEstimate = com.compressly.core.util.JobTotals.estimateBatchBytes(
            totalOriginal = s.items.sumOf { if (it.sizeBytes > 0L) it.sizeBytes else 0L },
            firstOriginal = s.originalSize,
            firstEstimate = s.estimatedSize,
            count = s.items.size
        )
        if (batchEstimate > 0 && !Storage.hasEnoughSpace(batchEstimate)) {
            _state.update { it.copy(lowSpaceWarning = true) }
            return null
        }

        val settings = buildSettings(s)
        // Lock before enqueueing; released again only if nothing was queued, since
        // on success the screen navigates away and this ViewModel is discarded
        // with its nav entry (a new selection starts unlocked).
        _state.update { it.copy(startingJob = true) }
        val jobId = runCatching { container.jobCoordinator.enqueue(s.mediaType, s.items, settings) }
            .onFailure { android.util.Log.e("SettingsViewModel", "enqueue failed", it) }
            .getOrNull()
        if (jobId == null) _state.update { it.copy(startingJob = false) }
        return jobId
    }

    fun dismissLowSpaceWarning() {
        _state.update { it.copy(lowSpaceWarning = false) }
    }

    /** Starts the job even when the free-space check warned (user choice). */
    fun forceCompress(): Long? {
        _state.update { it.copy(lowSpaceWarning = false) }
        val s = _state.value
        if (!s.ready || s.items.isEmpty() || s.startingJob) return null
        val settings = buildSettings(s)
        _state.update { it.copy(startingJob = true) }
        val jobId = runCatching { container.jobCoordinator.enqueue(s.mediaType, s.items, settings) }
            .onFailure { android.util.Log.e("SettingsViewModel", "enqueue failed", it) }
            .getOrNull()
        if (jobId == null) _state.update { it.copy(startingJob = false) }
        return jobId
    }

    private fun buildSettings(s: UiState): CompressionSettings = when (s.mediaType) {
        MediaType.PHOTO -> CompressionSettings.Photo(
            s.photo.copy(smart = s.smart || s.preset == CompressionPreset.SMART),
            if (s.smart) CompressionPreset.SMART else s.preset,
            replaceOriginal = s.replaceOriginal,
            outputLocation = s.outputLocation,
            outputFolder = s.outputFolder
        )
        MediaType.VIDEO -> {
            // Unavailable codecs (no matching encoder on this device) fall back
            // to H.264 rather than failing the whole job. h265FellBack is set so
            // the UI can tell the user the requested H.265 was not used — it was
            // a dead state field before.
            val codec = when {
                !s.h265Available && s.video.codec == com.compressly.core.engine.model.VideoCodec.H265 -> {
                    _state.update { it.copy(h265FellBack = true) }
                    com.compressly.core.engine.model.VideoCodec.H264
                }
                !s.av1Available && s.video.codec == com.compressly.core.engine.model.VideoCodec.AV1 -> {
                    _state.update { it.copy(h265FellBack = true) }
                    com.compressly.core.engine.model.VideoCodec.H264
                }
                else -> {
                    _state.update { it.copy(h265FellBack = false) }
                    s.video.codec
                }
            }
            CompressionSettings.Video(
                s.video.copy(codec = codec),
                if (s.smart) CompressionPreset.SMART else s.preset,
                replaceOriginal = s.replaceOriginal,
                outputLocation = s.outputLocation,
                outputFolder = s.outputFolder
            )
        }
        MediaType.AUDIO -> CompressionSettings.Audio(
            s.audio,
            if (s.smart) CompressionPreset.SMART else s.preset,
            replaceOriginal = s.replaceOriginal,
            outputLocation = s.outputLocation,
            outputFolder = s.outputFolder
        )
    }

    /** REPLACE-ORIGINAL: toggle whether the source files are deleted on success. */
    fun setReplaceOriginal(value: Boolean) {
        // When the user ticks "replace", default the result to landing in the
        // SAME folder as the source (replace in place). They can still switch
        // to a custom folder below.
        _state.update {
            it.copy(
                replaceOriginal = value,
                outputLocation = if (value && it.outputLocation == com.compressly.core.engine.model.OutputLocation.DEFAULT)
                    com.compressly.core.engine.model.OutputLocation.SAME_AS_SOURCE else it.outputLocation
            )
        }
    }

    /** OUTPUT-LOCATION: choose where the compressed result is written. */
    fun setOutputLocation(location: com.compressly.core.engine.model.OutputLocation) {
        _state.update { it.copy(outputLocation = location) }
    }

    /** OUTPUT-LOCATION: set the per-job custom folder (SAF tree URI). */
    fun setOutputFolder(uri: String?) {
        _state.update { it.copy(outputFolder = uri, outputLocation = com.compressly.core.engine.model.OutputLocation.CUSTOM) }
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
