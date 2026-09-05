package com.compressly.ui.screens

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.request.videoFrameMillis
import ir.siliksama.hajmino.R
import com.compressly.core.data.db.HistoryEntry
import com.compressly.core.engine.model.MediaType
import com.compressly.core.util.Bidi
import com.compressly.core.util.Formats
import com.compressly.ui.components.AdSlot
import com.compressly.ui.components.EmptyState
import com.compressly.ui.viewmodels.HistoryViewModel
import androidx.compose.material3.Checkbox

@Composable
fun HistoryScreen(
    onBack: () -> Unit,
    onOpenEntry: (Long) -> Unit,
    viewModel: HistoryViewModel = viewModel(factory = HistoryViewModel.Factory)
) {
    val context = LocalContext.current
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    var showClearDialog by remember { mutableStateOf(false) }
    var showBulkDeleteDialog by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf<MediaType?>(null) }
    val haptic = LocalHapticFeedback.current

    // Multi-select (bulk delete) mode: long-press a row to enter, tap rows to
    // toggle selection, then delete all selected at once.
    var selectionMode by remember { mutableStateOf(false) }
    val selected = remember { mutableStateListOf<Long>() }

    fun toggleSelection(id: Long) {
        if (selected.contains(id)) selected.remove(id) else selected.add(id)
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        if (selected.isEmpty()) selectionMode = false
    }

    fun exitSelection() {
        selectionMode = false
        selected.clear()
    }

    val visibleEntries = remember(entries, filter) {
        if (filter == null) entries else entries.filter { MediaType.fromName(it.mediaType) == filter }
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (selectionMode) {
                    IconButton(onClick = { exitSelection() }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.action_cancel))
                    }
                    Text(
                        text = stringResource(R.string.history_selected, selected.size),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    if (selected.isNotEmpty()) {
                        IconButton(onClick = { showBulkDeleteDialog = true }) {
                            Icon(
                                Icons.Outlined.DeleteOutline,
                                contentDescription = stringResource(R.string.history_delete_selected),
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                } else {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                    Text(
                        text = stringResource(R.string.history_title),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    if (entries.isNotEmpty()) {
                        IconButton(onClick = { showClearDialog = true }) {
                            Icon(
                                Icons.Outlined.DeleteOutline,
                                contentDescription = stringResource(R.string.history_clear_all),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            AdSlot(Modifier.padding(horizontal = 20.dp, vertical = 2.dp))

            if (entries.isNotEmpty()) {
                // Per-section filter: All / Photos / Videos / Audio.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 4.dp),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
                ) {
                    listOf(
                        null to stringResource(R.string.history_filter_all),
                        MediaType.PHOTO to stringResource(R.string.history_filter_photos),
                        MediaType.VIDEO to stringResource(R.string.history_filter_videos),
                        MediaType.AUDIO to stringResource(R.string.history_filter_audio)
                    ).forEach { (type, label) ->
                        val isSelected = filter == type
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(
                                    if (isSelected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surfaceVariant
                                )
                                .clickable { filter = type }
                                .padding(horizontal = 14.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            if (entries.isEmpty()) {
                EmptyState(
                    title = stringResource(R.string.history_empty_title),
                    description = stringResource(R.string.history_empty_desc),
                    modifier = Modifier.weight(1f)
                )
            } else if (visibleEntries.isEmpty()) {
                // UI-5 FIX: Show a filter-aware empty message so the user
                // understands why the list is empty (active filter, not no history).
                val filterLabel = when (filter) {
                    MediaType.PHOTO -> stringResource(R.string.history_filter_photos)
                    MediaType.VIDEO -> stringResource(R.string.history_filter_videos)
                    MediaType.AUDIO -> stringResource(R.string.history_filter_audio)
                    null -> ""
                }
                EmptyState(
                    title = stringResource(R.string.history_empty_title),
                    description = stringResource(R.string.history_filter_empty_desc, filterLabel),
                    modifier = Modifier.weight(1f)
                )
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)
                ) {
                    items(visibleEntries, key = { it.id }) { entry ->
                        HistoryRow(
                            entry = entry,
                            selectionMode = selectionMode,
                            selected = selected.contains(entry.id),
                            onClick = {
                                if (selectionMode) toggleSelection(entry.id)
                                else onOpenEntry(entry.id)
                            },
                            onLongClick = {
                                if (!selectionMode) {
                                    selectionMode = true
                                    toggleSelection(entry.id)
                                }
                            },
                            onShare = {
                                if (!selectionMode && entry.status == HistoryEntry.STATUS_DONE)
                                    viewModel.share(context, entry)
                            }
                        )
                    }
                }
            }
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text(stringResource(R.string.history_clear_confirm_title)) },
            text = { Text(stringResource(R.string.history_clear_confirm_msg)) },
            confirmButton = {
                TextButton(onClick = {
                    showClearDialog = false
                    viewModel.clear()
                }) {
                    Text(stringResource(R.string.history_clear_confirm_yes))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    if (showBulkDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showBulkDeleteDialog = false },
            title = { Text(stringResource(R.string.history_selected, selected.size)) },
            text = { Text(stringResource(R.string.history_delete_selected_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    showBulkDeleteDialog = false
                    viewModel.delete(selected.toList())
                    exitSelection()
                }) {
                    Text(stringResource(R.string.history_clear_confirm_yes))
                }
            },
            dismissButton = {
                TextButton(onClick = { showBulkDeleteDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryRow(
    entry: HistoryEntry,
    selectionMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onShare: () -> Unit
) {
    val context = LocalContext.current
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (selectionMode) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = { onClick() }
                )
                Spacer(Modifier.width(4.dp))
            }
            HistoryThumb(entry)
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
                    text = stringResource(R.string.history_time_ago, Formats.timeAgo(entry.createdAt)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                if (entry.status == HistoryEntry.STATUS_DONE) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = Formats.humanSize(entry.inputSize),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = stringResource(R.string.history_arrow),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.outline
                        )
                        Text(
                            text = Formats.humanSize(entry.outputSize),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.history_reduced, Formats.humanSize(entry.savedBytes)),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                } else {
                    Text(
                        text = stringResource(
                            when (entry.status) {
                                HistoryEntry.STATUS_FAILED -> R.string.history_status_failed
                                HistoryEntry.STATUS_CANCELLED -> R.string.history_status_cancelled
                                HistoryEntry.STATUS_RUNNING -> R.string.history_status_running
                                else -> R.string.history_status_interrupted
                            }
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            if (entry.status == HistoryEntry.STATUS_DONE && !selectionMode) {
                IconButton(onClick = onShare) {
                    Icon(
                        Icons.Outlined.Share,
                        contentDescription = stringResource(R.string.action_share),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun HistoryThumb(entry: HistoryEntry) {
    val type = MediaType.fromName(entry.mediaType)
    val uri = entry.outputUri?.let { Uri.parse(it) } ?: Uri.parse(entry.inputUri)
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        when (type) {
            MediaType.PHOTO -> AsyncImage(
                model = uri,
                contentDescription = entry.fileName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(56.dp)
            )
            MediaType.VIDEO -> AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(uri)
                    .videoFrameMillis(1000)
                    .build(),
                contentDescription = entry.fileName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(56.dp)
            )
            MediaType.AUDIO -> Icon(
                imageVector = Icons.Outlined.MusicNote,
                contentDescription = entry.fileName,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp)
            )
        }
    }
}
