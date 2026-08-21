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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.compressly.R
import com.compressly.core.engine.model.CompressionPreset
import com.compressly.core.engine.model.MediaType
import com.compressly.core.engine.model.PresetDefaults
import com.compressly.ui.theme.GradientPrimary

private fun presetTitle(preset: CompressionPreset): Int = when (preset) {
    CompressionPreset.MAXIMUM_QUALITY -> R.string.preset_max_quality
    CompressionPreset.BALANCED -> R.string.preset_balanced
    CompressionPreset.HIGH_COMPRESSION -> R.string.preset_high_compression
    CompressionPreset.MAXIMUM_COMPRESSION -> R.string.preset_max_compression
    CompressionPreset.SMART -> R.string.preset_smart
}

private fun presetDesc(preset: CompressionPreset): Int = when (preset) {
    CompressionPreset.MAXIMUM_QUALITY -> R.string.preset_max_quality_desc
    CompressionPreset.BALANCED -> R.string.preset_balanced_desc
    CompressionPreset.HIGH_COMPRESSION -> R.string.preset_high_compression_desc
    CompressionPreset.MAXIMUM_COMPRESSION -> R.string.preset_max_compression_desc
    CompressionPreset.SMART -> R.string.preset_smart_desc
}

/**
 * Compression level picker. Smart mode (the default) is a one-tap card on
 * top; picking any of the four manual tiers switches to the 4-step gauge.
 */
@Composable
fun PresetGauge(
    selected: CompressionPreset,
    mediaType: MediaType,
    smartSelected: Boolean,
    onSelectSmart: (Boolean) -> Unit,
    onSelect: (CompressionPreset) -> Unit,
    modifier: Modifier = Modifier
) {
    val primary = MaterialTheme.colorScheme.primary
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant

    Column(modifier = modifier.fillMaxWidth()) {
        // ---- Smart mode card ----
        val smartBorder = if (smartSelected) primary else MaterialTheme.colorScheme.outlineVariant
        val smartBg = if (smartSelected) primary.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surface
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = smartBg,
            border = androidx.compose.foundation.BorderStroke(1.5.dp, smartBorder),
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onSelectSmart(!smartSelected) }
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (smartSelected) {
                                Brush.horizontalGradient(GradientPrimary)
                            } else {
                                Brush.horizontalGradient(
                                    listOf(surfaceVariant, surfaceVariant)
                                )
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.AutoAwesome,
                        contentDescription = null,
                        tint = if (smartSelected) Color.White else onSurfaceVariant,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.preset_smart),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.preset_smart_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = onSurfaceVariant
                    )
                }
                // Radio-style check
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(
                            if (smartSelected) primary else MaterialTheme.colorScheme.outlineVariant
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (smartSelected) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(MaterialTheme.colorScheme.onPrimary)
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // ---- Manual 4-step gauge (dimmed while smart is active) ----
        val gaugeAlpha = if (smartSelected) 0.35f else 1f
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .alpha(gaugeAlpha),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            CompressionPreset.ordered.forEach { preset ->
                val lit by animateColorAsState(
                    targetValue = if (preset.order <= selected.order && !smartSelected) primary
                    else surfaceVariant,
                    label = "gauge"
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(12.dp)
                        .clip(RoundedCornerShape(if (preset.order == 0) 12.dp else 4.dp))
                        .background(
                            if (preset.order <= selected.order && !smartSelected) {
                                Brush.horizontalGradient(listOf(primary, primary.copy(alpha = 0.85f)))
                            } else {
                                Brush.horizontalGradient(listOf(lit, lit))
                            }
                        )
                        .clickable(enabled = !smartSelected) { onSelect(preset) }
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        // ---- Selected description + estimate ----
        if (smartSelected) {
            Text(
                text = stringResource(R.string.preset_smart_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = onSurfaceVariant
            )
        } else {
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
        }

        // ---- Estimated saving chip ----
        val (min, max) = PresetDefaults.reductionRange(
            if (smartSelected) CompressionPreset.SMART else selected,
            mediaType
        )
        Spacer(Modifier.height(10.dp))
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
