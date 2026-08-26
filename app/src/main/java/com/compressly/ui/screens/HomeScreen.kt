package com.compressly.ui.screens

import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.request.videoFrameMillis
import com.compressly.CompresslyApp
import com.compressly.Selection
import ir.siliksama.hajmino.R
import com.compressly.core.data.db.HistoryEntry
import com.compressly.core.engine.model.InputItem
import com.compressly.core.engine.model.MediaType
import com.compressly.core.util.Formats
import com.compressly.core.util.Uris
import com.compressly.ui.components.AdSlot
import com.compressly.ui.components.RotatingGear
import com.compressly.core.util.SoundEffects
import com.compressly.ui.theme.GradientAudio
import com.compressly.ui.theme.GradientHero
import com.compressly.ui.theme.GradientPhoto
import com.compressly.ui.theme.GradientVideo
import com.compressly.ui.viewmodels.HomeViewModel

@Composable
fun HomeScreen(
    onOpenSettings: (MediaType) -> Unit,
    onOpenHistory: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onOpenPremium: () -> Unit,
    onOpenJob: (Long) -> Unit,
    onOpenEntry: (Long) -> Unit,
    viewModel: HomeViewModel = viewModel(factory = HomeViewModel.Factory)
) {
    val totalSaved by viewModel.totalSaved.collectAsStateWithLifecycle()
    val recent by viewModel.recent.collectAsStateWithLifecycle()
    val activeJobs by viewModel.activeJobs.collectAsStateWithLifecycle()
    val isPremium by viewModel.isPremium.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val container = (context.applicationContext as CompresslyApp).container

    fun acceptPicked(type: MediaType, uris: List<Uri>) {
        if (uris.isEmpty()) return
        
        val maxSizeBytes = 2L * 1024 * 1024 * 1024 // 2 GB limit
        val validItems = mutableListOf<InputItem>()
        var skippedTooLarge = false

        uris.forEachIndexed { index, uri ->
            val size = Uris.sizeOf(context, uri).takeIf { it > 0 } ?: -1L
            if (size > maxSizeBytes) {
                skippedTooLarge = true
            } else {
                validItems.add(
                    InputItem(
                        itemId = System.nanoTime() + index,
                        uri = uri,
                        displayName = Uris.displayNameOf(context, uri),
                        sizeBytes = size,
                        mediaType = type
                    )
                )
            }
        }

        if (skippedTooLarge) {
            Toast.makeText(context, context.getString(R.string.pick_error_too_large), Toast.LENGTH_LONG).show()
        }

        if (validItems.isEmpty()) return

        container.selection.set(Selection(type, validItems))
        onOpenSettings(type)
    }

    var pendingDocsType by remember { mutableStateOf<MediaType?>(null) }

    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(50)
    ) { uris -> acceptPicked(MediaType.PHOTO, uris) }

    val videoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(50)
    ) { uris -> acceptPicked(MediaType.VIDEO, uris) }

    val docsPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        pendingDocsType?.let { acceptPicked(it, uris) }
    }

    fun pick(type: MediaType) {
        val mimes = when (type) {
            MediaType.PHOTO -> arrayOf("image/jpeg", "image/png", "image/webp", "image/heic", "image/heif")
            MediaType.VIDEO -> arrayOf("video/mp4", "video/quicktime", "video/x-matroska", "video/webm", "video/3gpp")
            MediaType.AUDIO -> arrayOf("audio/mpeg", "audio/wav", "audio/x-wav", "audio/flac", "audio/aac", "audio/mp4", "audio/ogg", "audio/x-m4a")
        }
        when {
            type == MediaType.AUDIO || Build.VERSION.SDK_INT < 33 -> {
                pendingDocsType = type
                docsPicker.launch(mimes)
            }
            type == MediaType.PHOTO -> photoPicker.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
            else -> videoPicker.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
            )
        }
    }

    val isDark = androidx.compose.foundation.isSystemInDarkTheme()
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Animated ambient background — behind all content
            com.compressly.ui.components.AnimatedBlobs(
                modifier = Modifier.fillMaxSize(),
                dark = isDark
            )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {
            item { HomeHeader(onOpenHistory, onOpenAppSettings) }
            item { HeroCard(totalSaved) }
            // UI-2 BEAUTY: Active jobs banner takes priority over everything else when visible.
            if (activeJobs.isNotEmpty()) {
                item { ActiveJobsBanner(activeJobs.size) { onOpenJob(activeJobs.first().jobId) } }
            }
            item {
                SectionTitle(stringResource(R.string.home_tap_to_choose))
            }
            item { ModuleCards(onPick = ::pick) }
            // UI-2 BEAUTY: Premium banner moved after module cards so it doesn't
            // block the primary call-to-action. Non-intrusive placement.
            if (!isPremium) {
                item { PremiumBanner { onOpenPremium() } }
            }
            item {
                AdSlot(Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            }
            item { SectionTitle(stringResource(R.string.home_recent_activity)) }
            if (recent.isEmpty()) {
                item {
                    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                        Text(
                            text = stringResource(R.string.home_no_activity),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.home_no_activity_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            } else {
                items(recent) { entry ->
                    RecentRow(entry, onClick = { onOpenEntry(entry.id) })
                }
                item {
                    Text(
                        text = stringResource(R.string.home_view_all),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .padding(horizontal = 20.dp, vertical = 8.dp)
                            .clickable { onOpenHistory() }
                    )
                }
            }
        } // end LazyColumn
        } // end Box (AnimatedBlobs container)
    }
}

// ---------------------------------------------------------------------

@Composable
private fun HomeHeader(onOpenHistory: () -> Unit, onOpenAppSettings: () -> Unit) {
    var spin by remember { mutableStateOf(0) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            painter = painterResource(R.drawable.ic_splash_logo),
            contentDescription = null,
            modifier = Modifier.size(38.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = stringResource(R.string.app_tagline),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onOpenHistory) {
            Icon(Icons.Outlined.History, contentDescription = stringResource(R.string.history_title), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = {
            spin++
            SoundEffects.play(SoundEffects.Type.CLICK)
            onOpenAppSettings()
        }) {
            RotatingGear(
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                spinKey = spin
            )
        }
    }
}

@Composable
private fun HeroCard(totalSaved: Long) {
    val onPrimary = Color.White
    // UI-1 BEAUTY: cleaner, more airy hero card — less text, bigger stat focus.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(Brush.linearGradient(GradientHero))
            .padding(horizontal = 24.dp, vertical = 26.dp)
    ) {
        // Layered decorative circles for depth.
        Box(
            modifier = Modifier
                .size(160.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.055f))
                .align(Alignment.TopEnd)
                .offset(x = 54.dp, y = (-54).dp)
        )
        Box(
            modifier = Modifier
                .size(90.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.04f))
                .align(Alignment.BottomStart)
                .offset(x = (-30).dp, y = 30.dp)
        )
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.07f))
                .align(Alignment.TopEnd)
                .offset(x = 16.dp, y = 24.dp)
        )
        Column {
            // Show the offline badge as a subtle pill — privacy-first branding.
            Box(
                modifier = Modifier
                    .background(Color.White.copy(alpha = 0.18f), RoundedCornerShape(50))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(
                    text = "🔒 " + stringResource(R.string.hero_offline_badge),
                    style = MaterialTheme.typography.labelSmall,
                    color = onPrimary.copy(alpha = 0.9f)
                )
            }
            Spacer(Modifier.height(14.dp))
            Text(
                text = stringResource(R.string.home_hero_title),
                style = MaterialTheme.typography.headlineSmall,
                color = onPrimary
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.home_hero_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = onPrimary.copy(alpha = 0.80f)
            )
            Spacer(Modifier.height(20.dp))
            // Total saved stat — the single most impressive number in the whole app.
            if (totalSaved > 0) {
                Text(
                    text = stringResource(R.string.home_total_saved),
                    style = MaterialTheme.typography.labelMedium,
                    color = onPrimary.copy(alpha = 0.75f)
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = Formats.humanSize(totalSaved),
                    style = MaterialTheme.typography.displaySmall,
                    color = onPrimary
                )
            }
        }
    }
}

@Composable
private fun ActiveJobsBanner(count: Int, onOpenJob: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(primary.copy(alpha = 0.12f))
            .clickable { onOpenJob() }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Pulsing live indicator instead of static dot
        com.compressly.ui.components.PulsingDot(color = primary, size = 10.dp)
        Spacer(Modifier.width(12.dp))
        Text(
            text = stringResource(R.string.home_jobs_active, count),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = stringResource(R.string.action_open),
            style = MaterialTheme.typography.labelLarge,
            color = primary
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 8.dp)
    )
}

@Composable
private fun ModuleCards(onPick: (MediaType) -> Unit) {
    Column(
        modifier = Modifier.padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ModuleCard(
            title = stringResource(R.string.home_compress_photo),
            subtitle = stringResource(R.string.home_photo_subtitle),
            gradient = GradientPhoto,
            icon = Icons.Outlined.PhotoCamera,
            onClick = { onPick(MediaType.PHOTO) }
        )
        ModuleCard(
            title = stringResource(R.string.home_compress_video),
            subtitle = stringResource(R.string.home_video_subtitle),
            gradient = GradientVideo,
            icon = Icons.Outlined.Videocam,
            onClick = { onPick(MediaType.VIDEO) }
        )
        ModuleCard(
            title = stringResource(R.string.home_compress_audio),
            subtitle = stringResource(R.string.home_audio_subtitle),
            gradient = GradientAudio,
            icon = Icons.Outlined.MusicNote,
            onClick = { onPick(MediaType.AUDIO) }
        )
    }
}

@Composable
private fun ModuleCard(
    title: String,
    subtitle: String,
    gradient: List<Color>,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    com.compressly.ui.components.PressableCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp)
    ) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.linearGradient(gradient))
            .clickable(onClick = onClick, onClickLabel = title)
            .padding(horizontal = 20.dp, vertical = 22.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color.White.copy(alpha = 0.22f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                // Decorative — title text already describes the action
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(28.dp)
            )
        }
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.85f)
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.9f),
            modifier = Modifier.size(26.dp)
        )
    }
    } // end PressableCard
}

@Composable
private fun RecentRow(entry: HistoryEntry, onClick: () -> Unit) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Thumbnail(entry)
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.fileName,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = entry.settingsSummary.ifBlank { entry.fileName },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(10.dp))
        if (entry.status == HistoryEntry.STATUS_DONE) {
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = stringResource(R.string.history_reduced, Formats.humanSize(entry.savedBytes)),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = Formats.humanSize(entry.outputSize),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            Text(
                text = stringResource(
                    when (entry.status) {
                        HistoryEntry.STATUS_FAILED -> R.string.history_status_failed
                        HistoryEntry.STATUS_CANCELLED -> R.string.history_status_cancelled
                        else -> R.string.history_status_interrupted
                    }
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun Thumbnail(entry: HistoryEntry) {
    val mediaType = MediaType.fromName(entry.mediaType)
    val uri = entry.outputUri?.let { Uri.parse(it) } ?: entry.inputUri.let { Uri.parse(it) }
    Box(
        modifier = Modifier
            .size(52.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        when (mediaType) {
            MediaType.PHOTO -> AsyncImage(
                model = uri,
                contentDescription = entry.fileName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(52.dp)
            )
            MediaType.VIDEO -> AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(uri)
                    .videoFrameMillis(1000)
                    .build(),
                contentDescription = entry.fileName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(52.dp)
            )
            MediaType.AUDIO -> Icon(
                imageVector = Icons.Outlined.MusicNote,
                contentDescription = entry.fileName,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(26.dp)
            )
        }
    }
}

@Composable
private fun PremiumBanner(onBuy: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(primary)
            .clickable { onBuy() }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Outlined.AutoAwesome,
            contentDescription = null,
            tint = onPrimary,
            modifier = Modifier.size(24.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.premium_title),
                style = MaterialTheme.typography.titleSmall,
                color = onPrimary
            )
            Text(
                text = stringResource(R.string.premium_desc),
                style = MaterialTheme.typography.labelSmall,
                color = onPrimary.copy(alpha = 0.8f)
            )
        }
    }
}
