package com.compressly.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import kotlin.math.roundToInt
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.compressly.CompresslyApp
import ir.siliksama.hajmino.R
import com.compressly.core.engine.model.AudioFormat
import com.compressly.core.engine.model.InputItem
import com.compressly.core.engine.model.CompressionPreset
import com.compressly.core.engine.model.MediaType
import com.compressly.core.engine.model.PhotoFormat
import com.compressly.core.engine.model.PhotoResize
import com.compressly.core.engine.model.VideoAudioMode
import com.compressly.core.engine.model.VideoCodec
import com.compressly.core.engine.model.VideoResolution
import com.compressly.core.util.Bidi
import com.compressly.core.util.Formats
import com.compressly.ui.components.BeforeAfterSlider
import com.compressly.core.util.SoundEffects
import com.compressly.ui.components.ChipSelector
import com.compressly.ui.components.GradientSummaryBar
import com.compressly.ui.components.InfoRow
import com.compressly.ui.components.SmartAnalysisCard
import com.compressly.ui.components.PresetPicker
import com.compressly.ui.components.SectionHeader
import com.compressly.ui.components.ShimmerBox
import com.compressly.ui.components.ToggleRow
import com.compressly.ui.components.Waveform
import com.compressly.ui.viewmodels.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompressionSettingsScreen(
    mediaType: MediaType,
    onBack: () -> Unit,
    onJobStarted: (Long) -> Unit,
    viewModel: SettingsViewModel = viewModel(
        factory = SettingsViewModel.factory(
            LocalContext.current.applicationContext as CompresslyApp,
            LocalContext.current
        )
    )
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()

    // BATCH-TOTALS-FIX: originalSize/estimatedSize describe the FIRST file
    // only — that is what the analysis and the per-grade estimates are
    // computed from. For a multi-file batch the headline numbers must cover
    // the whole selection, or a 50-file batch would show a single file's
    // size and the bar would promise 50 files at one file's size.
    val totalOriginal = remember(state.items, state.originalSize) {
        if (state.items.size > 1) {
            state.items.sumOf { it.sizeBytes.takeIf { s -> s > 0 } ?: 0L }
        } else state.originalSize
    }
    val totalEstimated = remember(state.items.size, state.estimatedSize) {
        if (state.items.size > 1) state.estimatedSize.coerceAtLeast(0L) * state.items.size
        else state.estimatedSize
    }

    LaunchedEffect(state.ready) {
        if (!state.ready) onBack()
    }

    // Notification permission explainer (Android 13+).
    var showPermissionDialog by remember { mutableStateOf(false) }
    fun startCompression() {
        val jobId = viewModel.compress()
        if (jobId != null) {
            SoundEffects.play(SoundEffects.Type.CLICK)
            onJobStarted(jobId)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        // Whether granted or not, proceed with compression — notification
        // permission is optional and the job runs either way.
        startCompression()
    }

    fun requestCompression() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            showPermissionDialog = true
        } else {
            startCompression()
        }
    }

    // UI-3 FIX: Compress button is now pinned to the bottom of the screen via
    // Scaffold's bottomBar slot so it is always visible without scrolling.
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                GradientSummaryBar(
                    count = state.items.size,
                    estimatedSize = if (totalEstimated > 0)
                        Formats.humanSize(totalEstimated)
                    else stringResource(R.string.unknown),
                    onClick = ::requestCompression
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(
                        if (mediaType == MediaType.VIDEO) R.string.processing_warning_long_video
                        else R.string.progress_will_keep_running
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // ---- Top bar ----
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.action_back))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(
                            when (mediaType) {
                                MediaType.PHOTO -> R.string.settings_title_photo
                                MediaType.VIDEO -> R.string.settings_title_video
                                MediaType.AUDIO -> R.string.settings_title_audio
                            }
                        ),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = pluralStringResource(R.plurals.settings_files_selected, state.items.size, state.items.size),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
            ) {
                // ---- Photo live preview ----
                // BATCH-VISIBILITY-FIX: a multi-file batch is shown as a real
                // list the user can inspect and trim — previously a 50-file
                // batch was a black box with just a count and one thumbnail.
                if (mediaType == MediaType.PHOTO && state.items.size == 1) {
                    PhotoPreviewCard(
                        state = state.preview,
                        mediaType = mediaType
                    )
                    Spacer(Modifier.height(20.dp))
                } else if (state.items.size > 1) {
                    SelectedFilesCard(
                        items = state.items,
                        onRemove = viewModel::removeItem
                    )
                    Spacer(Modifier.height(20.dp))
                }

                // ---- Smart content analysis (video) ----
                if (mediaType == MediaType.VIDEO) {
                    state.info?.takeIf { it.hasComplexity }?.let { info ->
                        SmartAnalysisCard(info = info, video = state.video)
                        Spacer(Modifier.height(20.dp))
                    }
                }

                // ---- Compression level ----
                SectionHeader(stringResource(R.string.settings_preset_section))
                if (state.analyzing) {
                    Text(
                        text = stringResource(R.string.analysis_working),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                } else {
                    state.recommended?.let { rec ->
                        val recTitle = stringResource(
                            when (rec) {
                                CompressionPreset.MAXIMUM_QUALITY -> R.string.preset_max_quality
                                CompressionPreset.BALANCED -> R.string.preset_balanced
                                CompressionPreset.HIGH_COMPRESSION -> R.string.preset_high_compression
                                CompressionPreset.MAXIMUM_COMPRESSION -> R.string.preset_max_compression
                                CompressionPreset.SMART -> R.string.preset_smart
                            }
                        )
                        Text(
                            text = stringResource(R.string.analysis_banner, recTitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }
                }
                PresetPicker(
                    selected = if (state.preset == CompressionPreset.SMART)
                        state.recommended ?: CompressionPreset.BALANCED
                    else state.preset,
                    onSelect = viewModel::selectPreset,
                    originalSize = state.originalSize,
                    estimates = state.gradeEstimates,
                    recommended = state.recommended,
                    analyzing = state.analyzing
                )

                Spacer(Modifier.height(20.dp))

                // ---- Estimates ----
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        InfoRow(
                            label = stringResource(R.string.settings_original_size),
                            value = if (totalOriginal > 0) Formats.humanSize(totalOriginal)
                            else stringResource(R.string.unknown)
                        )
                        Spacer(Modifier.height(8.dp))
                        InfoRow(
                            label = stringResource(R.string.settings_estimated_size),
                            value = if (totalEstimated > 0) Formats.humanSize(totalEstimated)
                            else stringResource(R.string.unknown),
                            accent = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.estimate_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                )

                // ---- Replace original (delete source on success) ----
                // REPLACE-ORIGINAL: an explicit, opt-in toggle. When ON, each
                // successfully compressed file replaces its original (the source
                // is deleted). Placed prominently (not buried in Advanced) because
                // it is destructive and the user must see it before starting.
                Spacer(Modifier.height(20.dp))
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        ToggleRow(
                            title = stringResource(R.string.replace_original_title),
                            description = stringResource(R.string.replace_original_desc),
                            checked = state.replaceOriginal,
                            onCheckedChange = viewModel::setReplaceOriginal
                        )
                        // ---- Output location (replace in place / new folder) ----
                        Spacer(Modifier.height(12.dp))
                        OutputLocationSelector(state, viewModel)
                    }
                }

                // ---- Advanced ----
                Spacer(Modifier.height(20.dp))
                SectionHeader(stringResource(R.string.settings_advanced_section))
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        ToggleRow(
                            title = stringResource(R.string.settings_advanced_toggle),
                            description = stringResource(R.string.advanced_hint),
                            checked = state.advanced,
                            onCheckedChange = viewModel::setAdvanced
                        )
                        if (state.advanced) {
                            Spacer(Modifier.height(16.dp))
                            when (mediaType) {
                                MediaType.PHOTO -> PhotoAdvanced(state, viewModel)
                                MediaType.VIDEO -> VideoAdvanced(state, viewModel)
                                MediaType.AUDIO -> AudioAdvanced(state, viewModel)
                            }
                        }
                    }
                }

                // Bottom padding so content is not hidden behind the sticky
                // compress bar added by the Scaffold's bottomBar slot.
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    // ---- Low storage warning ----
    if (state.lowSpaceWarning) {
        val free = com.compressly.core.util.Storage.freeBytes()
        AlertDialog(
            onDismissRequest = { viewModel.dismissLowSpaceWarning() },
            title = { Text(stringResource(R.string.error_low_storage_warn_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.error_low_storage_warn_body,
                        Formats.humanSize(free),
                        Formats.humanSize(state.estimatedSize)
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.dismissLowSpaceWarning()
                    val jobId = viewModel.forceCompress()
                    if (jobId != null) {
                        SoundEffects.play(SoundEffects.Type.CLICK)
                        onJobStarted(jobId)
                    }
                }) {
                    Text(stringResource(R.string.action_compress))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissLowSpaceWarning() }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    // ---- Notification permission explainer ----
    if (showPermissionDialog) {
        AlertDialog(
            onDismissRequest = { showPermissionDialog = false },
            title = { Text(stringResource(R.string.perm_notification_title)) },
            text = { Text(stringResource(R.string.perm_notification_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showPermissionDialog = false
                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }) {
                    Text(stringResource(R.string.action_allow))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showPermissionDialog = false
                    // Start compression anyway — notification permission is
                    // optional; the job runs without a visible notification.
                    startCompression()
                }) {
                    Text(stringResource(R.string.action_not_now))
                }
            }
        )
    }
}

// ---------------------------------------------------------------------

@Composable
private fun PhotoPreviewCard(
    state: SettingsViewModel.PreviewState,
    mediaType: MediaType
) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        when (state) {
            is SettingsViewModel.PreviewState.Idle -> ShimmerBox(
                Modifier.fillMaxWidth().height(250.dp),
                cornerRadius = 22
            )
            is SettingsViewModel.PreviewState.Generating -> ShimmerBox(
                Modifier.fillMaxWidth().height(250.dp),
                cornerRadius = 22
            )
            is SettingsViewModel.PreviewState.Ready -> {
                Column {
                    Text(
                        text = stringResource(R.string.photo_before_after),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(250.dp)
                    ) {
                        BeforeAfterSlider(
                            beforeUri = state.beforeUri,
                            afterUri = state.afterUri
                        )
                    }
                }
            }
            is SettingsViewModel.PreviewState.Failed -> Text(
                text = stringResource(R.string.error_generic),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(24.dp)
            )
        }
    }
}

/**
 * The batch manifest: exactly which files will be compressed, with their
 * sizes, and a per-file remove. The list is capped at three rows so a huge
 * batch does not push the settings out of reach.
 */
@Composable
private fun SelectedFilesCard(
    items: List<InputItem>,
    onRemove: (Long) -> Unit
) {
    val visible = items.take(3)
    val more = items.size - visible.size
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = stringResource(R.string.selected_files_title),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            visible.forEach { item ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = Bidi.isolate(item.displayName),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = Formats.humanSize(item.sizeBytes.takeIf { s -> s > 0 } ?: 0L),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(2.dp))
                    IconButton(
                        onClick = { onRemove(item.itemId) },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            Icons.Outlined.Close,
                            contentDescription = stringResource(R.string.action_remove),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }
            }
            if (more > 0) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.selected_files_more, more),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ---- Advanced panels ----------------------------------------------------

@Composable
private fun PhotoAdvanced(
    state: SettingsViewModel.UiState,
    viewModel: SettingsViewModel
) {
    SectionHeader(stringResource(R.string.settings_output_format))
    ChipSelector(
        options = listOf(PhotoFormat.SOURCE, PhotoFormat.JPEG, PhotoFormat.WEBP, PhotoFormat.PNG),
        selected = state.photo.outputFormat,
        labelOf = { fmt ->
            stringResource(
                when (fmt) {
                    PhotoFormat.SOURCE -> R.string.photo_keep_original_format
                    PhotoFormat.JPEG -> R.string.photo_format_jpeg
                    PhotoFormat.WEBP -> R.string.photo_format_webp
                    PhotoFormat.PNG -> R.string.photo_format_png
                }
            )
        },
        onSelect = viewModel::setPhotoFormat
    )
    Spacer(Modifier.height(16.dp))

    Text(
        text = stringResource(R.string.settings_quality) + "  " + stringResource(R.string.settings_quality_value, state.photo.quality),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurface
    )
    Slider(
        value = state.photo.quality.toFloat(),
        onValueChange = { viewModel.setPhotoQuality(it.toInt()) },
        valueRange = 1f..100f,
        colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.primary)
    )
    Spacer(Modifier.height(12.dp))

    SectionHeader(stringResource(R.string.photo_resize))
    ChipSelector(
        options = listOf(PhotoResize.NONE, PhotoResize.R2560, PhotoResize.R1920, PhotoResize.R1280, PhotoResize.R1024, PhotoResize.CUSTOM),
        selected = state.photo.resize,
        labelOf = { res ->
            when (res) {
                PhotoResize.NONE -> stringResource(R.string.photo_resize_original)
                PhotoResize.R2560 -> stringResource(R.string.photo_resize_2560)
                PhotoResize.R1920 -> stringResource(R.string.photo_resize_1920)
                PhotoResize.R1280 -> stringResource(R.string.photo_resize_1280)
                PhotoResize.R1024 -> stringResource(R.string.photo_resize_1024)
                PhotoResize.CUSTOM -> stringResource(R.string.photo_resize_custom)
            }
        },
        onSelect = viewModel::setPhotoResize
    )
    if (state.photo.resize == PhotoResize.CUSTOM) {
        Spacer(Modifier.height(10.dp))
        // CUSTOM-WIDTH-FIX: the field keeps a local draft instead of binding
        // straight to the setting, which is clamped to 320..8000 — a bound
        // field snapped "1" to "320" and a cleared field to "1600", so no
        // value could ever be typed. The draft is:
        //  - committed live once it parses to an IN-RANGE number (so typing
        //    "1200" updates the estimate as soon as it becomes valid),
        //  - committed (clamped) when the user presses Done,
        //  - reverted to the committed value when Done is pressed on empty.
        var widthDraft by remember { mutableStateOf(state.photo.customMaxWidth.toString()) }
        val keyboard = LocalSoftwareKeyboardController.current
        OutlinedTextField(
            value = widthDraft,
            onValueChange = { raw ->
                val digits = raw.filter(Char::isDigit).take(5)
                widthDraft = digits
                digits.toIntOrNull()?.takeIf { it in 320..8000 }?.let {
                    if (it != state.photo.customMaxWidth) viewModel.setPhotoCustomWidth(it)
                }
            },
            label = { Text(stringResource(R.string.photo_custom_width_hint)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            keyboardActions = KeyboardActions(onDone = {
                keyboard?.hide()
                val parsed = widthDraft.trim().toIntOrNull()
                if (parsed == null) {
                    widthDraft = state.photo.customMaxWidth.toString()
                } else if (parsed != state.photo.customMaxWidth) {
                    viewModel.setPhotoCustomWidth(parsed) // VM clamps 320..8000
                }
            }),
            modifier = Modifier.fillMaxWidth()
        )
    }
    Spacer(Modifier.height(16.dp))

    ToggleRow(
        title = stringResource(R.string.settings_metadata),
        description = if (state.photo.preserveMetadata) {
            stringResource(R.string.settings_metadata_preserve_desc)
        } else {
            stringResource(R.string.settings_metadata_strip_desc)
        },
        checked = state.photo.preserveMetadata,
        onCheckedChange = viewModel::setPhotoMetadata
    )
}

@Composable
private fun VideoAdvanced(
    state: SettingsViewModel.UiState,
    viewModel: SettingsViewModel
) {
    SectionHeader(stringResource(R.string.video_resolution))
    ChipSelector(
        options = listOf(
            VideoResolution.ORIGINAL, VideoResolution.R2160,
            VideoResolution.R1080, VideoResolution.R720, VideoResolution.R480
        ),
        selected = state.video.resolution,
        labelOf = { res ->
            stringResource(
                when (res) {
                    VideoResolution.ORIGINAL -> R.string.video_resolution_original
                    VideoResolution.R2160 -> R.string.video_resolution_2160
                    VideoResolution.R1080 -> R.string.video_resolution_1080
                    VideoResolution.R720 -> R.string.video_resolution_720
                    VideoResolution.R480 -> R.string.video_resolution_480
                    VideoResolution.CUSTOM -> R.string.video_resolution_custom
                }
            )
        },
        onSelect = { res -> viewModel.setVideoSettings { it.copy(resolution = res) } }
    )
    Spacer(Modifier.height(14.dp))

    SectionHeader(stringResource(R.string.video_fps))
    ChipSelector(
        options = listOf(null, 60, 30, 24),
        selected = state.video.frameRate,
        labelOf = { fps ->
            when (fps) {
                null -> stringResource(R.string.video_fps_original)
                60 -> stringResource(R.string.video_fps_60)
                30 -> stringResource(R.string.video_fps_30)
                else -> stringResource(R.string.video_fps_24)
            }
        },
        onSelect = { fps -> viewModel.setVideoSettings { it.copy(frameRate = fps) } }
    )
    Spacer(Modifier.height(14.dp))

    SectionHeader(stringResource(R.string.video_codec))
    ChipSelector(
        options = listOf(VideoCodec.H264, VideoCodec.H265, VideoCodec.AV1),
        selected = state.video.codec,
        labelOf = { codec ->
            stringResource(
                when (codec) {
                    VideoCodec.H264 -> R.string.video_codec_h264
                    VideoCodec.H265 -> R.string.video_codec_h265
                    VideoCodec.AV1 -> R.string.video_codec_av1
                }
            )
        },
        onSelect = { codec -> viewModel.setVideoSettings { it.copy(codec = codec) } }
    )
    Text(
        text = stringResource(
            when (state.video.codec) {
                VideoCodec.H265 -> R.string.video_codec_h265_desc
                VideoCodec.AV1 -> R.string.video_codec_av1_desc
                VideoCodec.H264 -> R.string.video_codec_h264_desc
            }
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp)
    )
    if (state.video.codec == VideoCodec.H265 && !state.h265Available) {
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.video_codec_unavailable),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
    }
    if (state.video.codec == VideoCodec.AV1 && !state.av1Available) {
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.video_codec_av1_unavailable),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
    }
    // h265FellBack: the requested codec was swapped to H.264 at build time
    // because this device has no encoder for it — tell the user plainly
    // instead of letting them assume their H.265/AV1 choice was honoured.
    if (state.h265FellBack) {
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.video_codec_fell_back),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
    }
    Spacer(Modifier.height(14.dp))

    // Size-target: "compress this under X MB" instead of a quality tier.
    SectionHeader(stringResource(R.string.video_size_target))
    SizeTargetSection(state, viewModel)
    Spacer(Modifier.height(14.dp))

    SectionHeader(stringResource(R.string.video_video_hdr))
    ToggleRow(
        title = stringResource(R.string.video_hdr_preserve),
        description = stringResource(R.string.video_hdr_preserve_desc),
        checked = state.video.preserveHdr,
        onCheckedChange = { on -> viewModel.setVideoSettings { it.copy(preserveHdr = on) } }
    )
    Spacer(Modifier.height(14.dp))

    SectionHeader(stringResource(R.string.video_audio_track))
    ChipSelector(
        options = listOf(VideoAudioMode.KEEP, VideoAudioMode.COMPRESS, VideoAudioMode.STRIP),
        selected = state.video.audioMode,
        labelOf = { mode ->
            stringResource(
                when (mode) {
                    VideoAudioMode.KEEP -> R.string.video_audio_keep
                    VideoAudioMode.COMPRESS -> R.string.video_audio_compress
                    VideoAudioMode.STRIP -> R.string.video_audio_strip
                }
            )
        },
        onSelect = { mode -> viewModel.setVideoSettings { it.copy(audioMode = mode) } }
    )
    Spacer(Modifier.height(14.dp))

    TrimSection(state, viewModel)
}

/** "Compress to under X MB" — a concrete size budget the user sets. */
@Composable
private fun SizeTargetSection(
    state: SettingsViewModel.UiState,
    viewModel: SettingsViewModel
) {
    val target = state.video.sizeTargetMb
    ToggleRow(
        title = stringResource(R.string.video_size_target_on),
        description = stringResource(R.string.video_size_target_desc),
        checked = target != null,
        onCheckedChange = { on ->
            viewModel.setVideoSettings { it.copy(sizeTargetMb = if (on) (target ?: 50) else null) }
        }
    )
    if (target != null) {
        Spacer(Modifier.height(10.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = stringResource(R.string.video_size_target_value, target),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Slider(
            value = target.toFloat(),
            onValueChange = { v -> viewModel.setVideoSettings { it.copy(sizeTargetMb = v.roundToInt()) } },
            valueRange = 10f..500f,
            steps = 48
        )
    }
}

@Composable
private fun TrimSection(
    state: SettingsViewModel.UiState,
    viewModel: SettingsViewModel
) {
    val durationMs = state.info?.durationMs ?: 0L
    ToggleRow(
        title = stringResource(R.string.video_trim_on),
        description = stringResource(R.string.video_trim),
        checked = state.video.trimEnabled,
        onCheckedChange = { enabled ->
            viewModel.setVideoSettings {
                it.copy(trimEnabled = enabled, trimStartMs = 0, trimEndMs = durationMs)
            }
        }
    )
    if (state.video.trimEnabled && durationMs > 0) {
        Spacer(Modifier.height(10.dp))
        val durationF = durationMs.toFloat()
        val startF = state.video.trimStartMs.toFloat().coerceIn(0f, durationF)
        val endF = state.video.trimEndMs.toFloat().takeIf { it > 0 }?.coerceIn(0f, durationF) ?: durationF
        RangeSlider(
            value = startF..endF,
            onValueChange = { range ->
                viewModel.setVideoSettings {
                    it.copy(trimStartMs = range.start.toLong(), trimEndMs = range.endInclusive.toLong())
                }
            },
            valueRange = 0f..durationMs.toFloat()
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = stringResource(R.string.video_trim_start) + " " + Formats.humanDuration(state.video.trimStartMs),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = stringResource(R.string.video_trim_end) + " " + Formats.humanDuration(state.video.trimEndMs),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun AudioAdvanced(
    state: SettingsViewModel.UiState,
    viewModel: SettingsViewModel
) {
    if (state.waveform.isNotEmpty()) {
        SectionHeader(stringResource(R.string.audio_waveform))
        Waveform(peaks = state.waveform)
        Spacer(Modifier.height(10.dp))
    }

    SectionHeader(stringResource(R.string.audio_output_format))
    ChipSelector(
        options = listOf(AudioFormat.AAC, AudioFormat.MP3),
        selected = state.audio.format,
        labelOf = { fmt ->
            stringResource(if (fmt == AudioFormat.AAC) R.string.audio_format_m4a else R.string.audio_format_mp3)
        },
        onSelect = { fmt -> viewModel.setAudioSettings { it.copy(format = fmt) } }
    )
    Spacer(Modifier.height(14.dp))

    SectionHeader(stringResource(R.string.audio_bitrate))
    ChipSelector(
        options = listOf(64, 128, 192, 256, 320),
        selected = state.audio.bitrate,
        labelOf = { bps ->
            stringResource(
                when (bps) {
                    64 -> R.string.audio_bitrate_64
                    128 -> R.string.audio_bitrate_128
                    192 -> R.string.audio_bitrate_192
                    256 -> R.string.audio_bitrate_256
                    else -> R.string.audio_bitrate_320
                }
            )
        },
        onSelect = { bps -> viewModel.setAudioSettings { it.copy(bitrate = bps) } }
    )
    Spacer(Modifier.height(14.dp))

    ToggleRow(
        title = stringResource(R.string.settings_metadata),
        description = if (state.audio.preserveMetadata) {
            stringResource(R.string.settings_metadata_preserve_desc)
        } else {
            stringResource(R.string.settings_metadata_strip_desc)
        },
        checked = state.audio.preserveMetadata,
        onCheckedChange = { preserve -> viewModel.setAudioSettings { it.copy(preserveMetadata = preserve) } }
    )
}


/** OUTPUT-LOCATION: where the compressed result is written. */
@Composable
private fun OutputLocationSelector(
    state: SettingsViewModel.UiState,
    viewModel: SettingsViewModel
) {
    val context = LocalContext.current
    var folderName by remember { mutableStateOf<String?>(null) }
    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { tree ->
        if (tree != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    tree,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            val name = runCatching {
                androidx.documentfile.provider.DocumentFile.fromTreeUri(context, tree)?.name
            }.getOrNull()
            folderName = name
            viewModel.setOutputFolder(tree.toString())
        }
    }

    val location = state.outputLocation
    ChipSelector(
        options = listOf(
            com.compressly.core.engine.model.OutputLocation.DEFAULT,
            com.compressly.core.engine.model.OutputLocation.SAME_AS_SOURCE,
            com.compressly.core.engine.model.OutputLocation.CUSTOM
        ),
        selected = location,
        labelOf = { loc ->
            stringResource(
                when (loc) {
                    com.compressly.core.engine.model.OutputLocation.DEFAULT -> R.string.output_location_default
                    com.compressly.core.engine.model.OutputLocation.SAME_AS_SOURCE -> R.string.output_location_same_as_source
                    com.compressly.core.engine.model.OutputLocation.CUSTOM -> R.string.output_location_custom
                }
            )
        },
        onSelect = { loc -> viewModel.setOutputLocation(loc) }
    )
    if (location != com.compressly.core.engine.model.OutputLocation.DEFAULT) {
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(
                when (location) {
                    com.compressly.core.engine.model.OutputLocation.SAME_AS_SOURCE -> R.string.output_location_same_as_source_desc
                    else -> R.string.output_location_custom_desc
                }
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    if (location == com.compressly.core.engine.model.OutputLocation.CUSTOM) {
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = { folderPicker.launch(null) }) {
            Text(
                stringResource(
                    if (folderName != null || state.outputFolder != null) R.string.output_location_change_folder
                    else R.string.output_location_pick_folder
                )
            )
        }
        if (folderName != null) {
            Text(
                text = folderName!!,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}
