package com.compressly.ui.components

import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.compressly.R

/**
 * Draggable before/after comparison slider for photos.
 * Left of the divider shows the ORIGINAL, right shows the COMPRESSED preview.
 * Dragging the handle reveals more of either side with real images.
 */
@Composable
fun BeforeAfterSlider(
    beforeUri: Uri?,
    afterUri: Uri?,
    modifier: Modifier = Modifier
) {
    var divider by remember { mutableFloatStateOf(0.5f) }
    val density = LocalDensity.current
    val onSurface = MaterialTheme.colorScheme.onSurface

    // The comparison slider is always left-to-right (original on the left,
    // compressed on the right) even in RTL/Persian, so the drag math stays
    // intuitive and identical in both languages.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
    BoxWithConstraints(
        modifier = modifier
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, dragAmount ->
                    change.consume()
                    divider = (divider + dragAmount / size.width).coerceIn(0.08f, 0.92f)
                }
            }
    ) {
        val fullW = maxWidth
        val fullH = maxHeight
        val fullWPx = with(density) { fullW.toPx() }

        // Original image (full).
        AsyncImage(
            model = beforeUri,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )

        // Compressed image, clipped to the right of the divider.
        Box(
            modifier = Modifier
                .offset(x = fullW * divider)
                .width(fullW * (1f - divider))
                .height(fullH)
                .clipToBounds()
        ) {
            AsyncImage(
                model = afterUri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .width(fullW)
                    .height(fullH)
                    .graphicsLayer {
                        translationX = -fullWPx * divider
                    }
            )
        }

        // Divider line.
        Canvas(Modifier.fillMaxSize()) {
            val x = size.width * divider
            drawLine(
                color = Color.White.copy(alpha = 0.95f),
                start = Offset(x, 0f),
                end = Offset(x, size.height),
                strokeWidth = 3f
            )
        }

        // Handle.
        Surface(
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
            shape = CircleShape,
            shadowElevation = 4.dp,
            modifier = Modifier
                .align(Alignment.Center)
                .size(42.dp)
                .graphicsLayer {
                    translationX = fullWPx * (divider - 0.5f)
                }
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = "\u2194",
                    color = onSurface,
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }

        LabelChip(stringResource(R.string.result_before_size), Modifier.align(Alignment.TopStart))
        LabelChip(stringResource(R.string.result_after_size), Modifier.align(Alignment.TopEnd))
    }
    }
}

@Composable
private fun LabelChip(text: String, modifier: Modifier = Modifier) {
    Surface(
        color = Color.Black.copy(alpha = 0.55f),
        shape = CircleShape,
        modifier = modifier.padding(10.dp)
    ) {
        Text(
            text = text,
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}
