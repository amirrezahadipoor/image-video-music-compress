package com.compressly.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.compressly.core.util.Formats
import com.compressly.ui.theme.GradientPrimary
import com.compressly.ui.viewmodels.StorageViewModel
import ir.siliksama.hajmino.R

/**
 * Storage dashboard (B6): a friendly, offline snapshot of how full the device
 * is, the largest media files that are eating the space, and how much the app
 * has already saved the user. No data leaves the device.
 */
@Composable
fun StorageDashboardScreen(
    onBack: () -> Unit,
    viewModel: StorageViewModel = viewModel(factory = StorageViewModel.Factory)
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
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
                    Icon(
                        Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = stringResource(R.string.action_back)
                    )
                }
                Text(
                    text = stringResource(R.string.storage_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(Modifier.height(8.dp))

            // Device capacity
            CapacityCard(state)
            Spacer(Modifier.height(16.dp))

            // Space already saved by compression
            SavedCard(state.savedBytes)
            Spacer(Modifier.height(20.dp))

            Text(
                text = stringResource(R.string.storage_largest_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(8.dp))
            if (state.largest.isEmpty()) {
                Text(
                    text = stringResource(R.string.storage_largest_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                state.largest.forEach { entry ->
                    LargestFileRow(name = entry.name, sizeBytes = entry.sizeBytes)
                    Spacer(Modifier.height(6.dp))
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun CapacityCard(state: StorageViewModel.UiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = stringResource(R.string.storage_capacity),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = stringResource(
                    R.string.storage_capacity_value,
                    Formats.humanSize(state.usedBytes),
                    Formats.humanSize(state.totalBytes)
                ),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.storage_free, Formats.humanSize(state.freeBytes)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun SavedCard(savedBytes: Long) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = GradientPrimary.first()
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.storage_saved_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = androidx.compose.ui.graphics.Color.White
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.storage_saved_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.9f)
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text = Formats.humanSize(savedBytes),
                style = MaterialTheme.typography.headlineSmall,
                color = androidx.compose.ui.graphics.Color.White
            )
        }
    }
}

@Composable
private fun LargestFileRow(name: String, sizeBytes: Long) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = name.ifBlank { stringResource(R.string.storage_unnamed) },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = Formats.humanSize(sizeBytes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
