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
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.runtime.rememberCoroutineScope
import com.compressly.core.data.FolderMediaScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
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
import com.compressly.core.engine.model.ItemPhase
import com.compressly.core.engine.model.JobState
import com.compressly.core.engine.model.MediaType
import com.compressly.core.util.Bidi
import com.compressly.core.util.Formats
import com.compressly.core.util.Uris
import com.compressly.ui.components.AdSlot
import com.compressly.ui.components.BeatingHeart
import com.compressly.ui.components.RotatingGear
import com.compressly.core.util.SoundEffects
import com.compressly.ui.theme.GradientAudio
import com.compressly.ui.theme.GradientHero
import com.compressly.ui.theme.GradientPhoto
import com.compressly.ui.theme.GradientVideo
import com.compressly.ui.viewmodels.HomeViewModel

/**
 * How many files the system picker accepts in one go. One constant for both the
 * launcher and the notice, so the number shown can never drift from the real cap.
 */
private const val PICKER_MAX_ITEMS = 50

@Composable
fun HomeScreen(
    onOpenSettings: (MediaType) -> Unit,
    onOpenHistory: () -> Unit,
    onOpenStorage: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onOpenPremium: () -> Unit,
    onOpenSupport: () -> Unit,
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

    val scope = rememberCoroutineScope()

    // BATCH-LOADING: preparing a large batch (e.g. 1000 files from a folder)
    // does one ContentResolver query per file (size + name) before we can open
    // the compression screen. That takes visible seconds, so we show a blocking
    // overlay with a spinner instead of a frozen screen.
    var preparingBatch by remember { mutableStateOf(false) }

    // LINT-RESOURCE-FIX (LocalContextGetResourceValueCall): the toast texts are
    // resolved ONCE during composition with stringResource and captured, so no
    // resource query runs through LocalContext inside the composable body.
    val msgTooLarge = stringResource(R.string.pick_error_too_large)
    val msgPickerLimit = stringResource(R.string.home_picker_limit_hint, PICKER_MAX_ITEMS)
    val msgFolderEmpty = stringResource(R.string.folder_empty)

    fun acceptPicked(type: MediaType, uris: List<Uri>, cappedPicker: Boolean = false) {
        if (uris.isEmpty()) return
        preparingBatch = true

        // No takePersistableUriPermission here: PhotoPicker / document-picker
        // URIs don't carry a persistable grant, so the call could only fail
        // (silently, in the runCatching) — misleading dead code. The read
        // grant they DO carry lasts until app data is cleared, which fully
        // covers the job's lifetime. (The SAF TREE picker below is the one
        // place a persistable permission is both possible and needed.)
        
        val maxSizeBytes = 2L * 1024 * 1024 * 1024 // 2 GB limit
        scope.launch {
            // PICK-IO-FIX: size + name is one ContentResolver query per URI. On
            // a big batch (e.g. 1000 files from a folder) that used to run
            // SEQUENTIALLY on the main thread and freeze the screen for tens of
            // seconds. Validation now runs on IO in bounded-parallel chunks
            // (max 64 concurrent queries at a time, never thousands of
            // coroutines), preserving order, so a huge folder is ready in a few
            // seconds — and the BATCH-LOADING overlay keeps the user informed.
            val (validItems, skippedTooLarge) = withContext(Dispatchers.IO) {
                data class Sized(val uri: Uri, val name: String, val size: Long)
                val results = uris.chunked(64).flatMap { chunk ->
                    coroutineScope {
                        chunk.map { uri ->
                            async(Dispatchers.IO) {
                                val size = Uris.sizeOf(context, uri).takeIf { it > 0 } ?: -1L
                                Sized(uri, Uris.displayNameOf(context, uri), size)
                            }
                        }.awaitAll()
                    }
                }
                val valid = mutableListOf<InputItem>()
                var skipped = false
                results.forEach { r ->
                    if (r.size > maxSizeBytes) {
                        skipped = true
                    } else {
                        valid.add(
                            InputItem(
                                itemId = System.nanoTime() + valid.size.toLong(),
                                uri = r.uri,
                                displayName = r.name,
                                sizeBytes = r.size,
                                mediaType = type
                            )
                        )
                    }
                }
                valid to skipped
            }

            if (skippedTooLarge) {
                Toast.makeText(context, msgTooLarge, Toast.LENGTH_LONG).show()
            }
            // PICKER-CAP-FIX: the system photo picker hard-stops at
            // PickMultipleVisualMedia's maxItems and explains nothing, so someone
            // who meant "my whole camera folder" got 50 files and no clue why.
            // Naming the limit, and the flow without one, turns a mystery into a
            // choice. cappedPicker: the documents picker has NO cap, so a
            // 50-file docs selection must not be told it hit one.
            if (cappedPicker && uris.size >= PICKER_MAX_ITEMS) {
                Toast.makeText(context, msgPickerLimit, Toast.LENGTH_LONG).show()
            }

            if (validItems.isEmpty()) {
                preparingBatch = false
                return@launch
            }

            preparingBatch = false
            container.selection.set(Selection(type, validItems))
            onOpenSettings(type)
        }
    }

    var pendingDocsType by remember { mutableStateOf<MediaType?>(null) }

    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(PICKER_MAX_ITEMS)
    ) { uris -> acceptPicked(MediaType.PHOTO, uris, cappedPicker = true) }

    val videoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(PICKER_MAX_ITEMS)
    ) { uris -> acceptPicked(MediaType.VIDEO, uris, cappedPicker = true) }

    val docsPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        pendingDocsType?.let { acceptPicked(it, uris) }
    }

    // ── Folder / album picker (SAF tree) ─────────────────────────────────
    var folderSnapshot by remember { mutableStateOf<FolderMediaScanner.Snapshot?>(null) }
    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { tree ->
        if (tree != null) {
            // REPLACE-ORIGINAL: persist BOTH read AND write on the folder tree.
            // Writing into the source document is how replaceOriginal avoids a
            // duplicate (see OutputStore.replaceInPlace), and a read-only grant
            // that expires with the process would make the write fail so the
            // fallback publishes a NEW copy instead — leaving the original.
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    tree,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            // BATCH-LOADING: scanning a folder with up to 10k photos is a
            // ContentResolver pass that takes visible seconds. Show the same
            // blocking overlay the file picker uses so the user sees progress
            // instead of a frozen screen.
            preparingBatch = true
            scope.launch {
                val snap = withContext(Dispatchers.IO) {
                    runCatching { FolderMediaScanner.scan(context, tree) }
                        .getOrDefault(FolderMediaScanner.Snapshot(emptyList(), emptyList(), emptyList(), false))
                }
                preparingBatch = false
                if (snap.total == 0) {
                    Toast.makeText(context, msgFolderEmpty, Toast.LENGTH_LONG).show()
                } else {
                    folderSnapshot = snap
                }
            }
        }
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

    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
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
            item { HomeHeader(onOpenHistory, onOpenStorage, onOpenAppSettings, onOpenSupport) }
            item { HeroCard(totalSaved) }
            // UI-2 BEAUTY: Active jobs banner takes priority over everything else when visible.
            if (activeJobs.isNotEmpty()) {
                // MULTI-JOB-BANNER-FIX: pass ALL active jobs — the banner used
                // to show only the first one, so with several jobs running the
                // user saw one job's count and progress as if it were the queue.
                item { ActiveJobsBanner(activeJobs) { onOpenJob(it) } }
            }
            item {
                SectionTitle(stringResource(R.string.home_tap_to_choose))
            }
            item { ModuleCards(onPick = ::pick) }
            item {
                FolderPickRow(onClick = { folderPicker.launch(null) })
            }
            // UI-2 BEAUTY: Premium banner moved after module cards so it doesn't
            // block the primary call-to-action. Non-intrusive placement.
            // SCREEN-FIX: shown in EVERY flavor. The old `STORE == "bazaar"`
            // condition hid the only entry point to the premium/payment screen
            // in the offline build — the screen existed in navigation but was
            // unreachable. The play flavor now shows factual messaging inside
            // the premium screen instead of hiding it.
            if (!isPremium) {
                item { PremiumBanner { onOpenPremium() } }
            }
            item {
                AdSlot(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), isPremium = isPremium)
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

        // BATCH-LOADING: full-screen blocking overlay with a spinner while the
        // large batch is being validated/prepared before the compression screen
        // opens. Without this a 1000-file pick hangs on a blank-on-frozen screen.
        if (preparingBatch) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background.copy(alpha = 0.85f)
            ) {
                androidx.compose.foundation.layout.Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(androidx.compose.foundation.layout.PaddingValues(24.dp)),
                    horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(52.dp))
                    Spacer(Modifier.height(20.dp))
                    Text(
                        text = stringResource(R.string.pick_processing),
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        }
        } // end Box (AnimatedBlobs container)

            // Folder scan result: choose what to compress.
            folderSnapshot?.let { snap ->
                AlertDialog(
                    onDismissRequest = { folderSnapshot = null },
                    title = { Text(stringResource(R.string.folder_title)) },
                    text = {
                        Column {
                            if (snap.truncated) {
                                Text(
                                    text = stringResource(R.string.folder_truncated),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                                Spacer(Modifier.height(8.dp))
                            }
                            Text(
                                text = stringResource(R.string.folder_found, snap.total),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(8.dp))
                            // One action per non-empty type — nothing is ever
                            // dropped silently; the user picks what to compress.
                            if (snap.photos.isNotEmpty()) {
                                TextButton(onClick = {
                                    val uris = snap.photos.map { it.uri }
                                    folderSnapshot = null
                                    acceptPicked(MediaType.PHOTO, uris)
                                }) { Text(stringResource(R.string.folder_photos, snap.photos.size)) }
                            }
                            if (snap.videos.isNotEmpty()) {
                                TextButton(onClick = {
                                    val uris = snap.videos.map { it.uri }
                                    folderSnapshot = null
                                    acceptPicked(MediaType.VIDEO, uris)
                                }) { Text(stringResource(R.string.folder_videos, snap.videos.size)) }
                            }
                            if (snap.audios.isNotEmpty()) {
                                TextButton(onClick = {
                                    val uris = snap.audios.map { it.uri }
                                    folderSnapshot = null
                                    acceptPicked(MediaType.AUDIO, uris)
                                }) { Text(stringResource(R.string.folder_audios, snap.audios.size)) }
                            }
                        }
                    },
                    confirmButton = {
                        // The user asked for a clear "start" beside "cancel": a
                        // single primary button that begins compressing the
                        // largest group found in the folder. The per-type
                        // buttons above remain for an explicit type choice.
                        TextButton(
                            onClick = {
                                val chosen = when {
                                    snap.videos.isNotEmpty() &&
                                        snap.videos.size >= snap.photos.size &&
                                        snap.videos.size >= snap.audios.size -> snap.videos
                                    snap.audios.isNotEmpty() &&
                                        snap.audios.size >= snap.photos.size -> snap.audios
                                    else -> snap.photos
                                }
                                val uris = chosen.map { it.uri }
                                val type = if (chosen === snap.videos) MediaType.VIDEO
                                else if (chosen === snap.audios) MediaType.AUDIO
                                else MediaType.PHOTO
                                folderSnapshot = null
                                acceptPicked(type, uris)
                            }
                        ) { Text(stringResource(R.string.folder_start)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { folderSnapshot = null }) {
                            Text(stringResource(R.string.action_cancel))
                        }
                    }
                )
            }
    }
}

// ---------------------------------------------------------------------

@Composable
private fun FolderPickRow(onClick: () -> Unit) {
    val context = LocalContext.current
    val folderPickTitle = stringResource(R.string.folder_pick_title)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    SoundEffects.play(SoundEffects.Type.CLICK)
                    onClick()
                }
                // A11Y: label the folder row on its clickable node.
                .clearAndSetSemantics {
                    role = Role.Button
                    contentDescription = folderPickTitle
                    onClick { onClick(); true }
                }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Outlined.FolderOpen,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.folder_pick_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(R.string.folder_pick_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ---------------------------------------------------------------------

@Composable
private fun HomeHeader(
    onOpenHistory: () -> Unit,
    onOpenStorage: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onOpenSupport: () -> Unit
) {
    var spin by remember { mutableIntStateOf(0) }
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
        // Beating heart: the optional donation entry — top of the dashboard.
        A11yIconButton(
            onClick = {
                SoundEffects.play(SoundEffects.Type.CLICK)
                onOpenSupport()
            },
            label = stringResource(R.string.support_title)
        ) {
            BeatingHeart(
                tint = Color(0xFFE5487B),
                size = 24.dp
            )
        }
        A11yIconButton(onClick = onOpenHistory, label = stringResource(R.string.history_title)) {
            Icon(Icons.Outlined.History, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // Storage dashboard (B6): shows device capacity, the largest media
        // files and how much the app has already saved.
        A11yIconButton(onClick = onOpenStorage, label = stringResource(R.string.storage_title)) {
            Icon(
                Icons.Outlined.Save,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        // Settings gear.
        A11yIconButton(
            onClick = {
                spin++
                SoundEffects.play(SoundEffects.Type.CLICK)
                onOpenAppSettings()
            },
            label = stringResource(R.string.app_settings_title)
        ) {
            RotatingGear(
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                spinKey = spin
            )
        }
    }
}

/**
 * A11Y-FIX for the Home header icons. material3's IconButton always creates a
 * clickable node, but with Icon(contentDescription = ...) the label ends up on
 * a *child* node and the clickable node itself reads as an unlabeled button —
 * exactly the node the accessibility scan flags (@[164,75,212,123]). Reset the
 * node with clearAndSetSemantics and re-declare role + label + onClick in one
 * place so the single clickable node is both labelled and activatable.
 */
@Composable
private fun A11yIconButton(
    onClick: () -> Unit,
    label: String,
    content: @Composable () -> Unit
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.clearAndSetSemantics {
            role = Role.Button
            contentDescription = label
            onClick { onClick(); true }
        }
    ) {
        content()
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
                    text = stringResource(R.string.hero_offline_badge),
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
private fun ActiveJobsBanner(jobs: List<JobState>, onOpenJob: (Long) -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    // Whole-queue numbers: total files and the size-weighted overall progress
    // across every active job.
    val totalFiles = jobs.sumOf { it.items.size }
    val overall = if (totalFiles > 0)
        jobs.map { it.overallFraction * it.items.size }.sum() / totalFiles
    else 0f
    // BANNER-FIX: keep items visible until BOTH of a parallel pair finish
    // (a finished file shows 100% while its sibling still runs), so the
    // pair's percentages are always on screen together.
    val inFlight = jobs
        .flatMap { j -> j.items.filter {
            it.phase != ItemPhase.QUEUED &&
                it.phase != ItemPhase.CANCELLED &&
                it.phase != ItemPhase.FAILED
        } }
        .take(2)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(primary.copy(alpha = 0.12f))
            .clickable { onOpenJob(jobs.first().jobId) }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Pulsing live indicator instead of static dot
        com.compressly.ui.components.PulsingDot(color = primary, size = 10.dp)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.home_jobs_active, jobs.size),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            // In-flight files of a parallel batch, each with its own progress.
            // The name + percent is wrapped in a BiDi isolate so a file name
            // like IMG_2024.jpg cannot reorder the digits around it.
            inFlight.take(2).forEach { item ->
                Text(
                    text = Bidi.isolate(
                        "${item.fileName} — ${(item.weightedFraction * 100).toInt()}%"
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = stringResource(R.string.home_jobs_percent, (overall * 100).toInt()),
                style = MaterialTheme.typography.titleSmall,
                color = primary
            )
            Text(
                text = stringResource(R.string.action_open),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
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
        shape = RoundedCornerShape(22.dp),
        label = title
    ) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.linearGradient(gradient))
            .padding(horizontal = 20.dp, vertical = 20.dp),
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
            // A11Y: label the row with its file name on the clickable node.
            .clearAndSetSemantics {
                role = Role.Button
                contentDescription = Bidi.isolate(entry.fileName)
                onClick { onClick(); true }
            }
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Thumbnail(entry)
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = Bidi.isolate(entry.fileName),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                // STATUS-SUBTITLE-FIX: non-DONE rows repeated the file name as
                // the subtitle (their settings summary is blank) — they now
                // say what actually happened to the file instead.
                text = when (entry.status) {
                    HistoryEntry.STATUS_DONE -> entry.settingsSummary.ifBlank { entry.fileName }
                    HistoryEntry.STATUS_FAILED -> stringResource(R.string.history_status_failed)
                    HistoryEntry.STATUS_CANCELLED -> stringResource(R.string.history_status_cancelled)
                    HistoryEntry.STATUS_RUNNING -> stringResource(R.string.history_status_running)
                    else -> stringResource(R.string.history_status_interrupted)
                },
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
    val premiumTitle = stringResource(R.string.premium_title)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(primary)
            .clickable { onBuy() }
            // A11Y: label the banner on its clickable node.
            .clearAndSetSemantics {
                role = Role.Button
                contentDescription = premiumTitle
                onClick { onBuy(); true }
            }
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
