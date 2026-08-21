package com.compressly.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The settings gear. Two modes:
 *  - infinite: a slow, subtle continuous rotation (Settings screen header).
 *  - spinOnce: rotates 360 degrees whenever [spinKey] changes (Home header tap).
 */
@Composable
fun RotatingGear(
    modifier: Modifier = Modifier,
    tint: Color,
    size: Dp = 24.dp,
    infinite: Boolean = false,
    spinKey: Int = 0
) {
    if (infinite) {
        val transition = rememberInfiniteTransition(label = "gear")
        val angle by transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(durationMillis = 9000, easing = LinearEasing)),
            label = "gearAngle"
        )
        GearIcon(modifier, tint, size, angle)
    } else {
        // One-shot spin triggered by spinKey increments.
        val angle by animateFloatAsState(
            targetValue = spinKey * 360f,
            animationSpec = tween(durationMillis = 650),
            label = "gearSpin"
        )
        GearIcon(modifier, tint, size, angle)
    }
}

@Composable
private fun GearIcon(modifier: Modifier, tint: Color, size: Dp, angle: Float) {
    Icon(
        imageVector = Icons.Outlined.Settings,
        contentDescription = null,
        tint = tint,
        modifier = modifier
            .size(size)
            .rotate(angle)
    )
}
