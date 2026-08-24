package com.compressly.ui.screens

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
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.compressly.CompresslyApp
import com.compressly.R
import com.compressly.core.data.db.HistoryEntry
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
    val entry by viewModel.entry.collectAsStateWithLifecycle()
    var reveal by remember { mutableStateOf(false) }
    var siblings by remember { mutableStateOf<List<HistoryEntry>>(emptyList()) }

    LaunchedEffect(entry?.id) {
        reveal = false
        // Sound is already played by ProgressScreen when the job completes;
        // avoid double-playing here.
        siblings = entry?.let { runCatching { viewModel.loadSiblings(it) }.getOrDefault(emptyList()) } ?: emptyList()
        reveal = true
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
                contentDescription = null,
                tint = androidx.compose.ui.graphics.Color.White,
                modifier = Modifier.size(48.dp)
            )
        }

        Spacer(Modifier.height(18.dp))

        val reduction = if (entry.inputSize > 0) {
            (1.0 - entry.outputSize.toDouble() / entry.inputSize).coerceIn(0.0, 1.0)
        } else 0.0
        Text(
            text = stringResource(R.string.result_reduction, Formats.percent(reduction)),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = entry.fileName,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )

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

        if (siblings.size > 1) {
            Spacer(Modifier.height(16.dp))
            Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface) {
                Column(modifier = Modifier.padding(14.dp)) {
                    val done = siblings.count { it.status == HistoryEntry.STATUS_DONE }
                    Text(
                        text = stringResource(R.string.result_items_success, done, siblings.size),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(8.dp))
                    siblings.forEach { s ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = s.fileName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                modifier = Modifier.weight(1f)
                            )
                            if (s.status == HistoryEntry.STATUS_DONE) {
                                Text(
                                    text = stringResource(R.string.history_reduced, Formats.humanSize(s.savedBytes)),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            } else {
                                Text(
                                    text = stringResource(R.string.progress_failed),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }
        }

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
            text = entry.fileName,
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
