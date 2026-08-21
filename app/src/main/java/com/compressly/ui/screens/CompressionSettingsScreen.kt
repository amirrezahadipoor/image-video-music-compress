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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.compressly.CompresslyApp
import com.compressly.R
import com.compressly.core.engine.model.AudioFormat
import com.compressly.core.engine.model.MediaType
import com.compressly.core.engine.model.PhotoFormat
import com.compressly.core.engine.model.PhotoResize
import com.compressly.core.engine.model.VideoAudioMode
import com.compressly.core.engine.model.VideoCodec
import com.compressly.core.engine.model.VideoResolution
import com.compressly.core.util.Formats
import com.compressly.ui.components.BeforeAfterSlider
import com.compressly.core.util.SoundEffects
import com.compressly.ui.components.ChipSelector
import com.compressly.ui.components.GradientSummaryBar
import com.compressly.ui.components.InfoRow
import com.compressly.ui.components.PresetGauge
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

    LaunchedEffect(state.ready) {
        if (!state.ready) onBack()
    }

    // Notification permission explainer (Android 13+).
    var showPermissionDialog by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> /* proceed regardless; job still runs without a notification */ }

    fun startCompression() {
        val jobId = viewModel.compress()
        if (jobId != null) {
            SoundEffects.play(SoundEffects.Type.CLICK)
            onJobStarted(jobId)
        }
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

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background
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
                        text = stringResource(R.string.settings_files_selected, state.items.size),
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
                if (mediaType == MediaType.PHOTO && state.items.size == 1) {
                    PhotoPreviewCard(
                        state = state.preview,
                        mediaType = mediaType
                    )
                    Spacer(Modifier.height(20.dp))
                } else if (mediaType == MediaType.PHOTO) {
                    MultiPhotoThumb(state.items.firstOrNull()?.uri)
                    Spacer(Modifier.height(20.dp))
                }

                // ---- Preset gauge ----
                SectionHeader(stringResource(R.string.settings_preset_section))
                PresetGauge(
                    selected = state.preset,
                    mediaType = mediaType,
                    smartSelected = state.smart,
                    onSelectSmart = viewModel::setSmart,
                    onSelect = viewModel::selectPreset
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
                            value = Formats.humanSize(state.originalSize)
                        )
                        Spacer(Modifier.height(8.dp))
                        InfoRow(
                            label = stringResource(R.string.settings_estimated_size),
                            value = if (state.estimatedSize > 0) Formats.humanSize(state.estimatedSize)
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

                Spacer(Modifier.height(24.dp))
                GradientSummaryBar(
                    count = state.items.size,
                    estimatedSize = stringResource(R.string.settings_estimated_size) +
                        "  " + Formats.humanSize(state.estimatedSize),
                    onClick = ::requestCompression
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(
                        if (mediaType == MediaType.VIDEO) R.string.processing_warning_long_video
                        else R.string.progress_will_keep_running
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 24.dp)
                )
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
                    viewModel.forceCompress()
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

@Composable
private fun MultiPhotoThumb(uri: android.net.Uri?) {
    if (uri == null) return
    AsyncImage(
        model = uri,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .fillMaxWidth()
            .height(150.dp)
            .clip(RoundedCornerShape(22.dp))
    )
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
        OutlinedTextField(
            value = state.photo.customMaxWidth.toString(),
            onValueChange = { viewModel.setPhotoCustomWidth(it.toIntOrNull() ?: 1600) },
            label = { Text(stringResource(R.string.photo_custom_width_hint)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
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
        options = listOf(VideoResolution.ORIGINAL, VideoResolution.R1080, VideoResolution.R720, VideoResolution.R480),
        selected = state.video.resolution,
        labelOf = { res ->
            stringResource(
                when (res) {
                    VideoResolution.ORIGINAL -> R.string.video_resolution_original
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
        options = listOf(null, 30, 24),
        selected = state.video.frameRate,
        labelOf = { fps ->
            when (fps) {
                null -> stringResource(R.string.video_fps_original)
                30 -> stringResource(R.string.video_fps_30)
                else -> stringResource(R.string.video_fps_24)
            }
        },
        onSelect = { fps -> viewModel.setVideoSettings { it.copy(frameRate = fps) } }
    )
    Spacer(Modifier.height(14.dp))

    SectionHeader(stringResource(R.string.video_codec))
    ChipSelector(
        options = listOf(VideoCodec.H264, VideoCodec.H265),
        selected = state.video.codec,
        labelOf = { codec ->
            stringResource(if (codec == VideoCodec.H264) R.string.video_codec_h264 else R.string.video_codec_h265)
        },
        onSelect = { codec -> viewModel.setVideoSettings { it.copy(codec = codec) } }
    )
    Text(
        text = if (state.video.codec == VideoCodec.H265) {
            stringResource(R.string.video_codec_h265_desc)
        } else {
            stringResource(R.string.video_codec_h264_desc)
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp)
    )
    if (!state.h265Available && state.video.codec == VideoCodec.H265) {
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.video_codec_unavailable),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
    }
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

