package com.compressly.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.compressly.core.engine.estimate.GradeAdvisor
import com.compressly.core.engine.model.CompressionPreset
import com.compressly.core.util.Formats
import ir.siliksama.hajmino.R

private fun presetTitle(preset: CompressionPreset): Int = when (preset) {
    CompressionPreset.SMART -> R.string.preset_smart
    CompressionPreset.MAXIMUM_QUALITY -> R.string.preset_max_quality
    CompressionPreset.BALANCED -> R.string.preset_balanced
    CompressionPreset.HIGH_COMPRESSION -> R.string.preset_high_compression
    CompressionPreset.MAXIMUM_COMPRESSION -> R.string.preset_max_compression
}

private fun presetDesc(preset: CompressionPreset): Int = when (preset) {
    CompressionPreset.SMART -> R.string.preset_smart_desc
    CompressionPreset.MAXIMUM_QUALITY -> R.string.preset_max_quality_desc
    CompressionPreset.BALANCED -> R.string.preset_balanced_desc
    CompressionPreset.HIGH_COMPRESSION -> R.string.preset_high_compression_desc
    CompressionPreset.MAXIMUM_COMPRESSION -> R.string.preset_max_compression_desc
}

/**
 * Four named grades. When [estimates] and [originalSize] are set (a file has
 * been analysed) each row shows the expected size and percent for *this* file.
 * [recommended] is the grade the analyser picked; the user can tap a stronger one.
 *
 * On the app-settings screen there is no file, so Smart + the four grades
 * are listed without numbers.
 */
@Composable
fun PresetPicker(
    selected: CompressionPreset,
    onSelect: (CompressionPreset) -> Unit,
    modifier: Modifier = Modifier,
    originalSize: Long = 0L,
    estimates: Map<CompressionPreset, Long> = emptyMap(),
    recommended: CompressionPreset? = null,
    analyzing: Boolean = false,
    showSmart: Boolean = false
) {
    val grades = if (showSmart) CompressionPreset.all else CompressionPreset.ordered
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        grades.forEach { preset ->
            PresetRow(
                preset = preset,
                selected = preset == selected,
                recommended = preset == recommended && !showSmart,
                originalSize = originalSize,
                estimatedSize = estimates[preset],
                analyzing = analyzing,
                onSelect = onSelect
            )
        }
        Text(
            text = stringResource(R.string.preset_picker_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun PresetRow(
    preset: CompressionPreset,
    selected: Boolean,
    recommended: Boolean,
    originalSize: Long,
    estimatedSize: Long?,
    analyzing: Boolean,
    onSelect: (CompressionPreset) -> Unit
) {
    val primary = MaterialTheme.colorScheme.primary
    val bg by animateColorAsState(
        targetValue = if (selected) primary.copy(alpha = 0.10f)
        else MaterialTheme.colorScheme.surface,
        label = "presetBg"
    )
    val border by animateColorAsState(
        targetValue = if (selected) primary else MaterialTheme.colorScheme.outlineVariant,
        label = "presetBorder"
    )
    val saving = if (originalSize > 0 && estimatedSize != null) {
        GradeAdvisor.savingFraction(originalSize, estimatedSize)
    } else null

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(
                onClickLabel = stringResource(presetTitle(preset)),
                onClick = { onSelect(preset) }
            ),
        shape = RoundedCornerShape(16.dp),
        color = bg,
        border = BorderStroke(if (selected) 1.5.dp else 1.dp, border)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(if (selected) primary else MaterialTheme.colorScheme.outlineVariant),
                contentAlignment = Alignment.Center
            ) {
                if (selected) {
                    Icon(
                        imageVector = Icons.Outlined.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(presetTitle(preset)),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (recommended) {
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = primary.copy(alpha = 0.14f)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.AutoAwesome,
                                    contentDescription = null,
                                    tint = primary,
                                    modifier = Modifier.size(12.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = stringResource(R.string.preset_recommended),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = primary
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(presetDesc(preset)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.width(10.dp))

            Column(horizontalAlignment = Alignment.End) {
                when {
                    analyzing && estimatedSize == null -> {
                        Text(
                            text = "…",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    saving != null && saving > 0.02 -> {
                        Text(
                            text = "−" + Formats.percent(saving),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = if (selected) primary else MaterialTheme.colorScheme.onSurface
                        )
                        if (estimatedSize != null) {
                            Text(
                                text = Formats.humanSize(estimatedSize),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    estimatedSize != null -> {
                        Text(
                            text = stringResource(R.string.preset_no_gain),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
