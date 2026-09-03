package com.compressly.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.compressly.ui.theme.GradientPrimary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The signature gradient action button: pill-shaped, with a subtle press
 * animation and an optional loading spinner. Used for "Compress", "Share",
 * "Compress another" and friends.
 */
@Composable
fun ActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    gradient: List<Color> = GradientPrimary,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    loading: Boolean = false,
    contentColor: Color = Color.White
) {
    var pressed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, label = "btn")
    val interactionSource = remember { MutableInteractionSource() }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .scale(scale)
            .clip(RoundedCornerShape(28.dp))
            .background(if (enabled) Brush.horizontalGradient(gradient) else Brush.horizontalGradient(listOf(Color(0xFF9A9AAC), Color(0xFF9A9AAC))))
            .semantics {
                role = Role.Button
                // Ensure the clickable surface carries an accessible label
                // even when the inner Text composes as a separate node (the
                // a11y audit / TalkBack then never sees an anonymous button).
                if (text.isNotBlank()) contentDescription = text
            }
            .clickable(
                enabled = enabled && !loading,
                interactionSource = interactionSource,
                indication = null
            ) {
                pressed = true
                scope.launch {
                    delay(130)
                    pressed = false
                }
                onClick()
            },
        contentAlignment = Alignment.Center
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                color = contentColor,
                strokeWidth = 2.5.dp
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                icon?.let {
                    androidx.compose.material3.Icon(
                        imageVector = it,
                        contentDescription = null,
                        tint = contentColor,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    text = text,
                    color = contentColor,
                    style = MaterialTheme.typography.labelLarge,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

/** Small secondary (outlined) button. */
@Composable
fun GhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val outline = MaterialTheme.colorScheme.outline
    val onSurface = MaterialTheme.colorScheme.onSurface
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(MaterialTheme.colorScheme.surface)
            .semantics { role = Role.Button }
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (enabled) onSurface else outline,
            style = MaterialTheme.typography.labelLarge
        )
    }
}
