package com.compressly.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.compressly.core.engine.analysis.ComplexityMath
import com.compressly.core.engine.model.MediaInfo
import com.compressly.core.engine.model.VideoSettings
import com.compressly.core.engine.video.VideoPlanner
import ir.siliksama.hajmino.R

/**
 * Presents the content probe (motion / detail / colour) and the resolution
 * Smart itself chose for this file. Deliberately compact — this is a real
 * measurement, not decoration, so it carries numbers the user can act on.
 */
@Composable
fun SmartAnalysisCard(
    info: MediaInfo,
    video: VideoSettings,
    modifier: Modifier = Modifier
) {
    if (!info.hasComplexity) return
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.analysis_content_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                val label = when (ComplexityMath.motionLabel(info.complexity)) {
                    ComplexityMath.MotionLabel.STATIC -> R.string.analysis_content_static
                    ComplexityMath.MotionLabel.GENTLE -> R.string.analysis_content_gentle
                    ComplexityMath.MotionLabel.DYNAMIC -> R.string.analysis_content_dynamic
                }
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        text = stringResource(label),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            AnalysisBar(
                label = stringResource(R.string.analysis_content_motion),
                value = info.motion.coerceIn(0f, 1f)
            )
            Spacer(Modifier.height(6.dp))
            AnalysisBar(
                label = stringResource(R.string.analysis_content_detail),
                value = info.detail.coerceIn(0f, 1f)
            )
            Spacer(Modifier.height(6.dp))
            AnalysisBar(
                label = stringResource(R.string.analysis_content_color),
                value = info.color.coerceIn(0f, 1f)
            )
            if (info.sceneCuts > 0) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = pluralStringResource(
                        R.plurals.analysis_content_scenes, info.sceneCuts, info.sceneCuts
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium
                )
            }
            val sourceEdge = maxOf(info.effectiveWidth, info.effectiveHeight)
            val smartEdge = VideoPlanner.smartResolutionEdge(info, video)
            if (sourceEdge > smartEdge) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(
                        R.string.analysis_smart_resolution,
                        edgeLabel(smartEdge),
                        edgeLabel(sourceEdge)
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

private fun edgeLabel(edge: Int): String = when {
    edge >= 3200 -> "4K"
    edge >= 1600 -> "1080p"
    edge >= 1000 -> "720p"
    else -> "480p"
}

@Composable
private fun AnalysisBar(label: String, value: Float) {
    val animated by animateFloatAsState(
        targetValue = value,
        animationSpec = tween(durationMillis = 500),
        label = "analysisBar"
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(width = 64.dp, height = 16.dp)
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(8.dp)
                .background(
                    MaterialTheme.colorScheme.surfaceVariant,
                    RoundedCornerShape(4.dp)
                )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(animated.coerceIn(0.02f, 1f))
                    .height(8.dp)
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                MaterialTheme.colorScheme.primary,
                                MaterialTheme.colorScheme.tertiary
                            )
                        ),
                        RoundedCornerShape(4.dp)
                    )
            )
        }
    }
}
