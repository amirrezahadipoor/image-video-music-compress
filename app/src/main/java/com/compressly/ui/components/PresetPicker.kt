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
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Compress
import androidx.compose.material.icons.outlined.HighQuality
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.compressly.core.engine.model.CompressionPreset
import com.compressly.core.engine.model.MediaType
import com.compressly.core.engine.model.PresetDefaults
import com.compressly.ui.theme.GradientPrimary
import ir.siliksama.hajmino.R

private fun presetIcon(preset: CompressionPreset): ImageVector = when (preset) {
    CompressionPreset.SMART -> Icons.Outlined.AutoAwesome
    CompressionPreset.MAXIMUM_QUALITY -> Icons.Outlined.HighQuality
    CompressionPreset.BALANCED -> Icons.Outlined.Tune
    CompressionPreset.HIGH_COMPRESSION -> Icons.Outlined.Compress
    CompressionPreset.MAXIMUM_COMPRESSION -> Icons.Outlined.Archive
}

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
 * Compression level picker.
 *
 * One list, five named choices, each with an icon, a one-line description and
 * the saving it typically delivers. It replaces the old Smart-card-plus-gauge
 * combination, where the four manual tiers were unlabelled coloured bars, dimmed
 * to 35 % and made non-clickable while Smart was on - you could not tell what
 * you were choosing, and tapping a bar did nothing at all.
 *
 * Every row is always readable and always tappable; the selected one is simply
 * highlighted.
 *
 * @param mediaType when null (e.g. on the app-settings screen, where there is no
 * file in context) the saving badge is hidden.
 */
@Composable
fun PresetPicker(
    selected: CompressionPreset,
    onSelect: (CompressionPreset) -> Unit,
    modifier: Modifier = Modifier,
    mediaType: MediaType? = null
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        CompressionPreset.all.forEach { preset ->
            PresetRow(
                preset = preset,
                selected = preset == selected,
                mediaType = mediaType,
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
    mediaType: MediaType?,
    onSelect: (CompressionPreset) -> Unit
) {
    val primary = MaterialTheme.colorScheme.primary
    val bg by animateColorAsState(
        targetValue = if (selected) primary.copy(alpha = 0.09f)
        else MaterialTheme.colorScheme.surface,
        label = "presetBg"
    )
    val border by animateColorAsState(
        targetValue = if (selected) primary else MaterialTheme.colorScheme.outlineVariant,
        label = "presetBorder"
    )

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
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // ---- Icon tile ----
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (selected) Brush.horizontalGradient(GradientPrimary)
                        else Brush.horizontalGradient(
                            listOf(
                                MaterialTheme.colorScheme.surfaceVariant,
                                MaterialTheme.colorScheme.surfaceVariant
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = presetIcon(preset),
                    contentDescription = null,
                    tint = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(Modifier.width(12.dp))

            // ---- Title + description ----
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(presetTitle(preset)),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (preset == CompressionPreset.SMART) {
                        Spacer(Modifier.width(6.dp))
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = primary.copy(alpha = 0.14f)
                        ) {
                            Text(
                                text = stringResource(R.string.preset_recommended),
                                style = MaterialTheme.typography.labelSmall,
                                color = primary,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                            )
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

            // ---- Saving badge + selection mark ----
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (mediaType != null) {
                    val (_, max) = PresetDefaults.reductionRange(preset, mediaType)
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            text = stringResource(R.string.preset_saving_badge, "$max"),
                            style = MaterialTheme.typography.labelSmall,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(
                            if (selected) primary else MaterialTheme.colorScheme.outlineVariant
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (selected) {
                        Icon(
                            imageVector = Icons.Outlined.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(13.dp)
                        )
                    }
                }
            }
        }
    }
}
