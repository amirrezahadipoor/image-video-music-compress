package com.compressly.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Lightweight waveform preview: renders a list of peak values as bars.
 * The peaks are sampled by the audio engine (see WaveformSampler).
 */
@Composable
fun Waveform(
    peaks: List<Float>,
    modifier: Modifier = Modifier,
    color: Color? = null,
    barCount: Int = 64
) {
    val resolvedColor = color ?: MaterialTheme.colorScheme.primary
    val animated = remember { Animatable(0f) }
    LaunchedEffect(peaks) {
        animated.animateTo(1f, tween(450))
    }
    val sampled = remember(peaks) {
        if (peaks.isEmpty()) return@remember peaks
        val step = peaks.size.toFloat() / barCount
        List(barCount) { i ->
            val from = (i * step).toInt()
            val to = ((i + 1) * step).toInt().coerceAtMost(peaks.size)
            if (to > from) peaks.subList(from, to).max() else 0f
        }
    }
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
    ) {
        if (sampled.isEmpty()) return@Canvas
        val gap = 3.dp.toPx()
        val barWidth = (size.width - gap * (sampled.size - 1)) / sampled.size
        val mid = size.height / 2
        sampled.forEachIndexed { index, peak ->
            val h = (peak.coerceIn(0f, 1f) * (size.height - 6.dp.toPx())) * animated.value
            val x = index * (barWidth + gap)
            drawRoundRect(
                color = color,
                topLeft = androidx.compose.ui.geometry.Offset(x, mid - h / 2),
                size = Size(barWidth, h.coerceAtLeast(2.dp.toPx())),
                cornerRadius = CornerRadius(barWidth / 2, barWidth / 2)
            )
        }
    }
}
