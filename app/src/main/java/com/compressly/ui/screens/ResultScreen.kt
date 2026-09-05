package com.compressly.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.compressly.CompresslyApp
import ir.siliksama.hajmino.R
import com.compressly.core.data.db.HistoryEntry
import com.compressly.core.util.Bidi
import com.compressly.core.util.Formats
import com.compressly.ui.components.ActionButton
import com.compressly.ui.components.GhostButton
import com.compressly.ui.components.LoadingState
import com.compressly.core.util.SoundEffects
import com.compressly.ui.components.AdSlot
import com.compressly.ui.components.StatCard
import com.compressly.ui.theme.GradientSuccess
import com.compressly.ui.util.ErrorMessages
import com.compressly.ui.viewmodels.ResultViewModel
import kotlinx.coroutines.launch

@Composable
fun ResultScreen(
    entryId: Long,
    onBack: () -> Unit,
    onCompressAnother: () -> Unit,
    onHistory: () -> Unit,
    viewModel: ResultViewModel = viewModel(
        factory = ResultViewModel.factory(LocalContext.current.applicationContext as CompresslyApp, entryId)
    )
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val entry by viewModel.entry.collectAsStateWithLifecycle()
    var reveal by remember { mutableStateOf(false) }
    var siblings by remember { mutableStateOf<List<HistoryEntry>>(emptyList()) }

    LaunchedEffect(entry?.id) {
        reveal = false
        // Sound is already played by ProgressScreen when the job completes;
        // avoid double-playing here.
        siblings = entry?.let { runCatching { viewModel.loadSiblings(it) }.getOrDefault(emptyList()) } ?: emptyList()
        reveal = true
        // TACTILE-COMPLETE: a light confirmation buzz when a done result is
        // shown; only for a successful completion (not for a failure screen).
        if (entry?.status == HistoryEntry.STATUS_DONE) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        val current = entry
        if (current == null) {
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.action_back))
                }
                LoadingState(stringResource(R.string.progress_preparing))
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.action_back))
                }
                Text(
                    text = stringResource(if (current.status == HistoryEntry.STATUS_DONE) R.string.result_title_success else R.string.result_title_failed),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(8.dp))

            AnimatedVisibility(
                visible = reveal,
                enter = fadeIn(animationSpec = spring(dampingRatio = 0.7f)) +
                    scaleIn(initialScale = 0.88f, animationSpec = spring(dampingRatio = 0.6f))
            ) {
                if (current.status == HistoryEntry.STATUS_DONE) {
                    SuccessContent(current, siblings, viewModel, context, onCompressAnother, onHistory)
                } else {
                    FailureContent(current, onCompressAnother, onHistory)
                }
            }
        }
    }
}

@Composable
private fun SuccessContent(
    entry: HistoryEntry,
    siblings: List<HistoryEntry>,
    viewModel: ResultViewModel,
    context: android.content.Context,
    onCompressAnother: () -> Unit,
    onHistory: () -> Unit
) {
    // STATS-SCOPE-FIX: the numbers cover exactly the task the user ran, and only
    // rows that actually produced an output. Counting failed/cancelled rows
    // (outputSize 0) inflated a batch's saving, and a row belonging to another
    // job — which the old colliding job ids made possible — is what showed a
    // single video's result screen the totals of the folder before it.
    val done = siblings.filter { it.status == HistoryEntry.STATUS_DONE }
    // BATCH-RESULT-FIX: after a folder/batch (more than one file) the result
    // must NOT single out one file with its own preview and reduction — the
    // user asked for the whole job's total before/after change. A batch shows
    // an aggregate summary instead; a single file keeps the full preview.
    if (siblings.size > 1) {
        BatchResultContent(entry, siblings, done, viewModel, context, onHistory)
        return
    }
    SingleResultContent(done.firstOrNull { it.id == entry.id } ?: entry, viewModel, context, onCompressAnother, onHistory)
}

@Composable
private fun BatchResultContent(
    entry: HistoryEntry,
    all: List<HistoryEntry>,
    done: List<HistoryEntry>,
    viewModel: ResultViewModel,
    context: android.content.Context,
    onHistory: () -> Unit
) {
    val totalCount  = all.size
    val doneCount   = done.size
    // Only finished rows carry real byte counts; the count line above still
    // names the failures so the total is never presented as the whole job.
    val totalBefore = done.sumOf { it.inputSize }
    val totalAfter  = done.sumOf { it.outputSize }
    val totalSaved  = done.sumOf { it.savedBytes }
    val reduction   = if (totalBefore > 0)
        (totalSaved.toDouble() / totalBefore).coerceIn(0.0, 1.0)
    else 0.0
    val shownReduction by androidx.compose.animation.core.animateFloatAsState(
        targetValue = reduction.toFloat(),
        animationSpec = androidx.compose.animation.core.tween(durationMillis = 850),
        label = "batch_reduction_countup"
    )

    Column(modifier = Modifier.fillMaxWidth()) {
        // Success badge
        Box(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .size(96.dp)
                .background(Brush.linearGradient(GradientSuccess), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Outlined.Check,
                contentDescription = stringResource(R.string.result_title_success),
                tint = androidx.compose.ui.graphics.Color.White,
                modifier = Modifier.size(48.dp)
            )
        }

        Spacer(Modifier.height(18.dp))

        Text(
            text = stringResource(R.string.result_reduction, Formats.percent(shownReduction.toDouble())),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.result_items_success, doneCount, totalCount),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )

        // Aggregate before / after totals of the WHOLE batch, plus the total
        // reduction — the only numbers that matter after a folder job.
        Spacer(Modifier.height(22.dp))
        BatchSummaryCard(all, done)

        Spacer(Modifier.height(14.dp))
        OriginalsRetainedCard(done, viewModel)

        Spacer(Modifier.height(24.dp))
        ActionButton(
            text = stringResource(R.string.result_share_batch, doneCount),
            onClick = { viewModel.shareAll(context, all) },
            icon = Icons.Outlined.Share
        )
        // OPEN-OUTPUT-FIX: a batch job used to end with a share button and
        // nothing else — no way to actually look at the compressed files. A
        // folder has no single "the file", so the last finished result opens,
        // and "open folder" shows the place the batch was written to.
        Spacer(Modifier.height(10.dp))
        GhostButton(
            text = stringResource(R.string.result_open_output),
            onClick = { viewModel.open(context, done.lastOrNull() ?: entry) }
        )
        Spacer(Modifier.height(10.dp))
        GhostButton(
            text = stringResource(R.string.result_open_folder),
            onClick = { viewModel.openFolder(context, done.lastOrNull() ?: entry) }
        )
        Spacer(Modifier.height(10.dp))
        GhostButton(
            text = stringResource(R.string.result_view_history),
            onClick = onHistory
        )
        Spacer(Modifier.height(20.dp))
        AdSlot()
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SingleResultContent(
    entry: HistoryEntry,
    viewModel: ResultViewModel,
    context: android.content.Context,
    onCompressAnother: () -> Unit,
    onHistory: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        // Success badge
        Box(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .size(96.dp)
                .background(Brush.linearGradient(GradientSuccess), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Outlined.Check,
                contentDescription = stringResource(R.string.result_title_success),
                tint = androidx.compose.ui.graphics.Color.White,
                modifier = Modifier.size(48.dp)
            )
        }

        Spacer(Modifier.height(18.dp))

        val reduction = if (entry.inputSize > 0) {
            (1.0 - entry.outputSize.toDouble() / entry.inputSize).coerceIn(0.0, 1.0)
        } else 0.0
        // Count-up: the headline number ticks from 0 to the real saving instead
        // of popping in. Pure cosmetics, but it reads as "the engine worked".
        val shownReduction by androidx.compose.animation.core.animateFloatAsState(
            targetValue = reduction.toFloat(),
            animationSpec = androidx.compose.animation.core.tween(durationMillis = 850),
            label = "reduction_countup"
        )
        Text(
            text = stringResource(R.string.result_reduction, Formats.percent(shownReduction.toDouble())),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = Bidi.isolate(entry.fileName),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )

        // In-app preview + before/after comparison for the compressed file.
        // PHOTO -> draggable Before/After slider; VIDEO/AUDIO -> playable
        // ExoPlayer with an Original/Compressed toggle. Never hits the network.
        Spacer(Modifier.height(20.dp))
        Text(
            text = stringResource(R.string.result_preview_label),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp)
        )
        Spacer(Modifier.height(8.dp))
        com.compressly.ui.components.ResultMediaPreview(entry)

        Spacer(Modifier.height(22.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard(
                label = stringResource(R.string.result_before_size),
                value = Formats.humanSize(entry.inputSize),
                modifier = Modifier.weight(1f)
            )
            StatCard(
                label = stringResource(R.string.result_after_size),
                value = Formats.humanSize(entry.outputSize),
                modifier = Modifier.weight(1f),
                accent = MaterialTheme.colorScheme.primary
            )
        }

        // Settings summary chip (codec, quality, duration…)
        if (entry.settingsSummary.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            androidx.compose.material3.Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Text(
                    text = entry.settingsSummary,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        OriginalsRetainedCard(listOf(entry), viewModel)

        Spacer(Modifier.height(24.dp))

        ActionButton(
            text = stringResource(R.string.result_share_output),
            onClick = { viewModel.share(context, entry) },
            icon = Icons.Outlined.Share
        )
        Spacer(Modifier.height(10.dp))
        GhostButton(
            text = stringResource(R.string.result_open_output),
            onClick = { viewModel.open(context, entry) }
        )
        Spacer(Modifier.height(10.dp))
        GhostButton(
            text = stringResource(R.string.result_open_folder),
            onClick = { viewModel.openFolder(context, entry) }
        )
        Spacer(Modifier.height(10.dp))
        GhostButton(
            text = stringResource(R.string.result_compress_another),
            onClick = onCompressAnother
        )
        Spacer(Modifier.height(10.dp))
        GhostButton(
            text = stringResource(R.string.result_view_history),
            onClick = onHistory
        )
        Spacer(Modifier.height(20.dp))
        AdSlot()
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun BatchSummaryCard(all: List<HistoryEntry>, done: List<HistoryEntry>) {
    val doneCount   = done.size
    val totalBefore = done.sumOf { it.inputSize }
    val totalAfter  = done.sumOf { it.outputSize }
    val totalSaved  = done.sumOf { it.savedBytes }
    val surface     = MaterialTheme.colorScheme.surface
    val primary     = MaterialTheme.colorScheme.primary
    val onSurface   = MaterialTheme.colorScheme.onSurface
    val onSurfaceVar = MaterialTheme.colorScheme.onSurfaceVariant

    androidx.compose.material3.Surface(
        shape = RoundedCornerShape(18.dp),
        color = surface
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(
                text = pluralStringResource(R.plurals.result_batch_files, doneCount, doneCount, all.size),
                style = MaterialTheme.typography.labelMedium,
                color = onSurfaceVar
            )
            Spacer(Modifier.height(10.dp))
            // Exact total of the whole folder/batch: before and after, so the
            // user sees precisely how much the batch shrank, not just one file.
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard(
                    label = stringResource(R.string.result_before_size),
                    value = Formats.humanSize(totalBefore),
                    modifier = Modifier.weight(1f)
                )
                StatCard(
                    label = stringResource(R.string.result_after_size),
                    value = Formats.humanSize(totalAfter),
                    modifier = Modifier.weight(1f),
                    accent = primary
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val shownSaved by androidx.compose.animation.core.animateFloatAsState(
                    targetValue = totalSaved.toFloat(),
                    animationSpec = androidx.compose.animation.core.tween(durationMillis = 850),
                    label = "saved_countup"
                )
                Text(
                    text = stringResource(R.string.result_batch_saved, Formats.humanSize(shownSaved.toLong())),
                    style = MaterialTheme.typography.titleSmall,
                    color = onSurface
                )
                val shownPct by androidx.compose.animation.core.animateFloatAsState(
                    targetValue = (totalSaved.toDouble() / totalBefore.coerceAtLeast(1))
                        .coerceIn(0.0, 1.0).toFloat(),
                    animationSpec = androidx.compose.animation.core.tween(durationMillis = 850),
                    label = "pct_countup"
                )
                Text(
                    text = if (totalBefore > 0) Formats.percent(shownPct.toDouble()) else "0%",
                    style = MaterialTheme.typography.titleMedium,
                    color = primary
                )
            }
        }
    }
}

/**
 * The honest tail of "replace the original". When MediaStore refused to let the
 * app remove a file it does not own, the job used to finish in silence and the
 * user only noticed later that the gallery was untouched. Now the count is said
 * plainly, with one tap that goes through the system dialog — the only route
 * Android allows for someone else's media row.
 */
@Composable
private fun OriginalsRetainedCard(entries: List<HistoryEntry>, viewModel: ResultViewModel) {
    val retained = viewModel.removableOriginals(entries)
    if (retained.isEmpty()) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val grantLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) {
        // The dialog does the deleting itself; only the stale warning is left
        // to clean up, and only for rows whose file is really gone.
        scope.launch {
            viewModel.refreshRetainedMarkers(context, retained)
            busy = false
        }
    }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.result_originals_retained, retained.size),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(6.dp))
                TextButton(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        val pending = viewModel.requestDeleteGrant(context, retained)
                        if (pending != null) {
                            runCatching {
                                grantLauncher.launch(IntentSenderRequest.Builder(pending.intentSender).build())
                            }.onFailure {
                                scope.launch {
                                    viewModel.deleteOriginals(context, retained)
                                    busy = false
                                }
                            }
                        } else {
                            scope.launch {
                                viewModel.deleteOriginals(context, retained)
                                busy = false
                            }
                        }
                    }
                ) {
                    Text(
                        stringResource(
                            if (busy) R.string.result_removing_originals else R.string.result_remove_originals
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun FailureContent(
    entry: HistoryEntry,
    onCompressAnother: () -> Unit,
    onHistory: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(ErrorMessages.forKey(entry.error)),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = Bidi.isolate(entry.fileName),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(22.dp))
        GhostButton(
            text = stringResource(R.string.result_compress_another),
            onClick = onCompressAnother
        )
        Spacer(Modifier.height(10.dp))
        GhostButton(
            text = stringResource(R.string.result_view_history),
            onClick = onHistory
        )
    }
}
