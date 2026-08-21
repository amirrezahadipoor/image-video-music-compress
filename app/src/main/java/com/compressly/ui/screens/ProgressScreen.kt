package com.compressly.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.compressly.CompresslyApp
import com.compressly.R
import com.compressly.core.engine.model.ItemPhase
import com.compressly.core.engine.model.JobStatus
import com.compressly.ui.components.GhostButton
import com.compressly.ui.components.InlineProgress
import com.compressly.ui.components.LoadingState
import com.compressly.ui.components.ProgressRing
import com.compressly.ui.viewmodels.JobViewModel

@Composable
fun ProgressScreen(
    jobId: Long,
    onBack: () -> Unit,
    onResult: (Long) -> Unit,
    onHistory: () -> Unit,
    viewModel: JobViewModel = viewModel(
        factory = JobViewModel.factory(LocalContext.current.applicationContext as CompresslyApp, jobId)
    )
) {
    val context = LocalContext.current
    val container = (context.applicationContext as CompresslyApp).container
    val job by viewModel.job.collectAsStateWithLifecycle()
    var showCancelDialog by remember { mutableStateOf(false) }

    // Navigate to the result screen once the job completes successfully.
    LaunchedEffect(job?.status) {
        if (job?.status == JobStatus.COMPLETED) {
            val entry = container.historyRepository.getFirstDoneByJob(jobId)
            if (entry != null) {
                onResult(entry.id)
            } else {
                onHistory()
            }
        }
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        val current = job
        if (current == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                horizontalAlignment = Alignment.Start
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.action_back))
                }
                LoadingState(stringResource(R.string.progress_preparing))
            }
            return@Scaffold
        }

        val isTerminal = current.status == JobStatus.COMPLETED ||
            current.status == JobStatus.FAILED ||
            current.status == JobStatus.CANCELLED

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.progress_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                if (!isTerminal) {
                    IconButton(onClick = { showCancelDialog = true }) {
                        Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.action_cancel))
                    }
                } else {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // ---- Big ring ----
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                ProgressRing(
                    progress = current.overallFraction,
                    label = statusLabel(current.status, current.isPaused)
                )
            }

            Spacer(Modifier.height(24.dp))

            // ---- Controls ----
            if (current.status == JobStatus.RUNNING || current.status == JobStatus.PAUSED) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    GhostButton(
                        text = if (current.isPaused) stringResource(R.string.action_resume)
                        else stringResource(R.string.action_pause),
                        onClick = { if (current.isPaused) viewModel.resume() else viewModel.pause() },
                        modifier = Modifier.weight(1f)
                    )
                    GhostButton(
                        text = stringResource(R.string.action_cancel),
                        onClick = { showCancelDialog = true },
                        modifier = Modifier.weight(1f)
                    )
                }
            } else if (current.status == JobStatus.CANCELLING) {
                Text(
                    text = stringResource(R.string.progress_cancelling),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (isTerminal) {
                Spacer(Modifier.height(20.dp))
                TerminalSummary(current, onHistory)
            }

            Spacer(Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.progress_will_keep_running),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // ---- Per-item rows ----
            Spacer(Modifier.height(16.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(current.items) { item ->
                    ItemRow(item)
                }
            }
        }
    }

    if (showCancelDialog) {
        AlertDialog(
            onDismissRequest = { showCancelDialog = false },
            title = { Text(stringResource(R.string.action_cancel)) },
            text = { Text(stringResource(R.string.progress_stopped_by_user)) },
            confirmButton = {
                TextButton(onClick = {
                    showCancelDialog = false
                    viewModel.cancel()
                }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
            dismissButton = {
                TextButton(onClick = { showCancelDialog = false }) {
                    Text(stringResource(R.string.action_done))
                }
            }
        )
    }
}

@Composable
private fun statusLabel(status: JobStatus, isPaused: Boolean): String = when {
    isPaused -> stringResource(R.string.progress_paused)
    status == JobStatus.CANCELLING -> stringResource(R.string.progress_cancelling)
    status == JobStatus.COMPLETED -> stringResource(R.string.progress_complete)
    status == JobStatus.FAILED -> stringResource(R.string.progress_failed)
    status == JobStatus.CANCELLED -> stringResource(R.string.progress_stopped_by_user)
    else -> stringResource(R.string.progress_compressing)
}

@Composable
private fun ItemRow(item: com.compressly.core.engine.model.ItemState) {
    val isDone = item.phase == ItemPhase.DONE
    val failed = item.phase == ItemPhase.FAILED || item.phase == ItemPhase.CANCELLED
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = item.fileName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = when {
                        isDone -> stringResource(R.string.progress_complete)
                        failed -> stringResource(
                            if (item.phase == ItemPhase.CANCELLED) R.string.progress_stopped_by_user else R.string.progress_failed
                        )
                        else -> phaseLabel(item.phase)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (failed) MaterialTheme.colorScheme.error
                    else if (isDone) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(8.dp))
            InlineProgress(
                fraction = if (isDone) 1f else if (failed) 0f else item.fraction,
                color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun phaseLabel(phase: ItemPhase): String = when (phase) {
    ItemPhase.QUEUED -> stringResource(R.string.progress_preparing)
    ItemPhase.PREPARING -> stringResource(R.string.progress_preparing)
    ItemPhase.COMPRESSING -> stringResource(R.string.progress_compressing)
    ItemPhase.FINALIZING -> stringResource(R.string.progress_finalizing)
    else -> ""
}

@Composable
private fun TerminalSummary(
    job: com.compressly.core.engine.model.JobState,
    onHistory: () -> Unit
) {
    val doneCount = job.items.count { it.phase == ItemPhase.DONE }
    val failedCount = job.items.count { it.phase == ItemPhase.FAILED }
    Column {
        when (job.status) {
            JobStatus.FAILED -> Text(
                text = stringResource(R.string.result_title_failed),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.error
            )
            JobStatus.CANCELLED -> Text(
                text = stringResource(R.string.progress_stopped_by_user),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            else -> Text(
                text = stringResource(R.string.result_items_success, doneCount, job.items.size),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        if (failedCount > 0) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.result_items_success, doneCount, job.items.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(16.dp))
        GhostButton(
            text = stringResource(R.string.result_view_history),
            onClick = onHistory
        )
    }
}
