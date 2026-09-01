package com.compressly.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.compressly.ui.theme.GradientPrimary

/**
 * Animated "press-to-scale" wrapper.
 * Wraps any content with a spring-based press scale animation.
 * Use instead of plain .clickable when you want the tactile feel.
 */
@Composable
fun PressableCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(22.dp),
    content: @Composable () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.96f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessHigh
        ),
        label = "press_scale"
    )
    val elevation by animateDpAsState(
        targetValue = if (isPressed) 2.dp else 6.dp,
        animationSpec = spring(stiffness = Spring.StiffnessHigh),
        label = "press_elev"
    )
    Surface(
        modifier = modifier
            .scale(scale)
            .shadow(elevation, shape, clip = false)
            .clip(shape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        shape = shape,
        color = Color.Transparent
    ) {
        content()
    }
}

/**
 * Shimmer loading placeholder with smooth flowing animation.
 * Use for cards/rows that are loading data.
 */
@Composable
fun ShimmerPlaceholder(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 16.dp
) {
    val t = rememberInfiniteTransition(label = "shimmer")
    val offset by t.animateFloat(
        initialValue = -1f, targetValue = 2f,
        animationSpec = infiniteRepeatable(
            tween(1400, easing = FastOutSlowInEasing),
            RepeatMode.Restart
        ),
        label = "shimmer_offset"
    )
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.surfaceVariant,
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        MaterialTheme.colorScheme.surfaceVariant,
                    ),
                    start = androidx.compose.ui.geometry.Offset(offset * 1000f, 0f),
                    end = androidx.compose.ui.geometry.Offset(offset * 1000f + 600f, 0f)
                )
            )
    )
}

/**
 * A pulsing dot indicator — useful for "live" / "active" states.
 */
@Composable
fun PulsingDot(
    color: Color = MaterialTheme.colorScheme.primary,
    size: Dp = 10.dp,
    modifier: Modifier = Modifier
) {
    val t = rememberInfiniteTransition(label = "dot_pulse")
    val scale by t.animateFloat(
        1f, 1.45f,
        infiniteRepeatable(
            tween(900, easing = FastOutSlowInEasing),
            RepeatMode.Reverse
        ),
        label = "dot_scale"
    )
    val alpha by t.animateFloat(
        0.7f, 1f,
        infiniteRepeatable(
            tween(900, easing = FastOutSlowInEasing),
            RepeatMode.Reverse
        ),
        label = "dot_alpha"
    )
    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        // Halo
        Box(
            modifier = Modifier
                .size(size)
                .scale(scale)
                .clip(CircleShape)
                .background(color.copy(alpha = alpha * 0.3f))
        )
        // Core
        Box(
            modifier = Modifier
                .size(size * 0.6f)
                .clip(CircleShape)
                .background(color)
        )
    }
}

/**
 * A beating heart — the optional donation entry point on the dashboard.
 * Two quick pulse-cycles per loop read as a heartbeat rather than a generic
 * pulsing dot, so the icon says "support" without a single word.
 */
@Composable
fun BeatingHeart(
    tint: Color = Color(0xFFE5487B),
    size: Dp = 22.dp,
    modifier: Modifier = Modifier,
    contentDescription: String? = null
) {
    val t = rememberInfiniteTransition(label = "heartbeat")
    val scale by t.animateFloat(
        1f, 1.16f,
        infiniteRepeatable(
            tween(360, easing = FastOutSlowInEasing),
            RepeatMode.Reverse
        ),
        label = "heart_scale"
    )
    val alpha by t.animateFloat(
        0.9f, 1f,
        infiniteRepeatable(
            tween(360, easing = FastOutSlowInEasing),
            RepeatMode.Reverse
        ),
        label = "heart_alpha"
    )
    Icon(
        imageVector = Icons.Filled.Favorite,
        contentDescription = contentDescription,
        tint = tint.copy(alpha = alpha),
        modifier = modifier
            .size(size)
            .scale(scale)
    )
}

/**
 * Animated gradient bar — the compress action button.
 * Has entry animation on first composition.
 */
@Composable
fun AnimatedGradientBar(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    gradient: List<Color> = GradientPrimary,
    content: @Composable () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (isPressed) 0.97f else 1f,
        spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessHigh),
        label = "bar_scale"
    )

    // Subtle shimmer sweep on the bar
    val t = rememberInfiniteTransition(label = "bar_shimmer")
    val shimmer by t.animateFloat(
        -1f, 3f,
        infiniteRepeatable(tween(3000, easing = LinearEasing), RepeatMode.Restart),
        label = "bar_shimmer_val"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .scale(scale)
            .clip(RoundedCornerShape(28.dp))
            .background(Brush.horizontalGradient(gradient))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        // Shimmer overlay
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .graphicsLayer(alpha = 0.25f)
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.White.copy(alpha = 0.4f),
                            Color.Transparent,
                        ),
                        start = androidx.compose.ui.geometry.Offset(shimmer * 600f, 0f),
                        end = androidx.compose.ui.geometry.Offset(shimmer * 600f + 200f, 56f)
                    )
                )
        )
        content()
    }
}
