package com.compressly.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import kotlin.math.cos
import kotlin.math.sin

/**
 * Subtle animated background with softly moving blobs.
 * Designed to stay behind all content — completely non-intrusive.
 * Respects dark mode by using different blob colors.
 */
@Composable
fun AnimatedBlobs(
    modifier: Modifier = Modifier,
    dark: Boolean = false
) {
    val primary   = MaterialTheme.colorScheme.primary
    val tertiary  = MaterialTheme.colorScheme.tertiary
    val secondary = MaterialTheme.colorScheme.secondary

    val t = rememberInfiniteTransition(label = "blobs")

    // Each blob moves on a slightly different period so they never sync.
    val a1 by t.animateFloat(0f, 360f,
        infiniteRepeatable(tween(18_000, easing = LinearEasing)), label = "a1")
    val a2 by t.animateFloat(360f, 0f,
        infiniteRepeatable(tween(24_000, easing = LinearEasing)), label = "a2")
    val a3 by t.animateFloat(0f, 360f,
        infiniteRepeatable(tween(31_000, easing = LinearEasing)), label = "a3")
    val pulse by t.animateFloat(0.85f, 1.15f,
        infiniteRepeatable(tween(4_000, easing = FastOutSlowInEasing,
            repeatMode = RepeatMode.Reverse)), label = "pulse")

    val alpha = if (dark) 0.08f else 0.06f

    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height

        // Blob 1 — primary color, orbits upper-right
        val b1x = w * 0.7f + cos(Math.toRadians(a1.toDouble())).toFloat() * w * 0.22f
        val b1y = h * 0.2f + sin(Math.toRadians(a1.toDouble())).toFloat() * h * 0.12f
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(primary.copy(alpha = alpha * pulse), Color.Transparent),
                center = Offset(b1x, b1y),
                radius = w * 0.45f
            ),
            radius = w * 0.45f,
            center = Offset(b1x, b1y)
        )

        // Blob 2 — tertiary (violet), bottom-left
        val b2x = w * 0.2f + cos(Math.toRadians(a2.toDouble())).toFloat() * w * 0.15f
        val b2y = h * 0.75f + sin(Math.toRadians(a2.toDouble())).toFloat() * h * 0.10f
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(tertiary.copy(alpha = alpha * 0.8f), Color.Transparent),
                center = Offset(b2x, b2y),
                radius = w * 0.38f
            ),
            radius = w * 0.38f,
            center = Offset(b2x, b2y)
        )

        // Blob 3 — secondary (teal), center-wandering
        val b3x = w * 0.5f + cos(Math.toRadians(a3.toDouble())).toFloat() * w * 0.30f
        val b3y = h * 0.5f + sin(Math.toRadians(a3.toDouble() * 0.7)).toFloat() * h * 0.25f
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(secondary.copy(alpha = alpha * 0.5f), Color.Transparent),
                center = Offset(b3x, b3y),
                radius = w * 0.30f
            ),
            radius = w * 0.30f,
            center = Offset(b3x, b3y)
        )
    }
}
