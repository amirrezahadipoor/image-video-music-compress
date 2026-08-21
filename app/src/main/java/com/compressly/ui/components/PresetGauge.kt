package com.compressly.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.compressly.core.engine.model.CompressionPreset
import com.compressly.core.engine.model.MediaType
import com.compressly.core.engine.model.PresetDefaults
import com.compressly.R

private fun presetTitle(preset: CompressionPreset): Int = when (preset) {
    CompressionPreset.MAXIMUM_QUALITY -> R.string.preset_max_quality
    CompressionPreset.BALANCED -> R.string.preset_balanced
    CompressionPreset.HIGH_COMPRESSION -> R.string.preset_high_compression
    CompressionPreset.MAXIMUM_COMPRESSION -> R.string.preset_max_compression
}

private fun presetDesc(preset: CompressionPreset): Int = when (preset) {
    CompressionPreset.MAXIMUM_QUALITY -> R.string.preset_max_quality_desc
    CompressionPreset.BALANCED -> R.string.preset_balanced_desc
    CompressionPreset.HIGH_COMPRESSION -> R.string.preset_high_compression_desc
    CompressionPreset.MAXIMUM_COMPRESSION -> R.string.preset_max_compression_desc
}

/**
 * The 4-tier compression grading meter shared by photos, videos and audio.
 * Tapping a level animates the gauge; the selected level's description and
 * honest estimated saving range are shown underneath, plus an "Advanced"
 * toggle that reveals fine-grained controls.
 */
@Composable
fun PresetGauge(
    selected: CompressionPreset,
    mediaType: MediaType,
    onSelect: (CompressionPreset) -> Unit,
    modifier: Modifier = Modifier
) {
    val primary = MaterialTheme.colorScheme.primary
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant

    Column(modifier = modifier.fillMaxWidth()) {
        // ---- Gauge meter ----
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            CompressionPreset.ordered.forEach { preset ->
                val lit by animateColorAsState(
                    targetValue = if (preset.order <= selected.order) primary
                    else surfaceVariant,
                    label = "gauge"
                )
                val litAlpha by animateFloatAsState(
                    targetValue = if (preset.order <= selected.order) 1f else 0.55f,
                    label = "gaugeAlpha"
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(12.dp)
                        .clip(RoundedCornerShape(if (preset.order == 0) 12.dp else 4.dp))
                        .background(
                            if (preset.order <= selected.order) {
                                Brush.horizontalGradient(
                                    listOf(primary, primary.copy(alpha = 0.85f))
                                )
                            } else {
                                Brush.horizontalGradient(listOf(lit, lit))
                            }
                        )
                        .clickable { onSelect(preset) }
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        // ---- Selected level name ----
        Text(
            text = stringResource(presetTitle(selected)),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(presetDesc(selected)),
            style = MaterialTheme.typography.bodyMedium,
            color = onSurfaceVariant
        )

        // ---- Estimated saving chip ----
        val (min, max) = PresetDefaults.reductionRange(selected, mediaType)
        Surface(
            color = surfaceVariant,
            shape = RoundedCornerShape(12.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.settings_est_saving),
                    style = MaterialTheme.typography.labelMedium,
                    color = onSurfaceVariant
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.settings_est_saving_range, "$min%", "$max%"),
                    style = MaterialTheme.typography.labelLarge,
                    color = primary
                )
            }
        }
    }
}
