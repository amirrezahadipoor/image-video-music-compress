@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.compressly.ui.components

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.foundation.clickable
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.compressly.core.data.db.HistoryEntry
import com.compressly.core.engine.model.MediaType
import ir.siliksama.hajmino.R

/**
 * In-app preview of the compressed result, with a before/after comparison.
 *
 * - PHOTO  -> [BeforeAfterSlider] (drag the handle to reveal original vs output)
 * - VIDEO  -> [BeforeAfterPlayback] (ExoPlayer; toggle Original / Compressed)
 * - AUDIO  -> [BeforeAfterPlayback] (ExoPlayer; toggle Original / Compressed)
 *
 * The player is built for LOCAL content:// URIs only, so previewing never hits
 * the network — the app remains fully offline-capable.
 */
@Composable
fun ResultMediaPreview(entry: HistoryEntry, modifier: Modifier = Modifier) {
    val type = MediaType.fromName(entry.mediaType)
    val beforeUri = entry.inputUri.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }
    val afterUri = entry.outputUri?.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }
    if (beforeUri == null && afterUri == null) return

    Column(modifier = modifier.fillMaxWidth()) {
        when (type) {
            MediaType.PHOTO -> BeforeAfterSlider(
                beforeUri = beforeUri,
                afterUri = afterUri,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(280.dp)
                    .clip(RoundedCornerShape(18.dp))
            )
            MediaType.VIDEO, MediaType.AUDIO -> BeforeAfterPlayback(
                mediaType = type,
                beforeUri = beforeUri,
                afterUri = afterUri,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (type == MediaType.VIDEO) 230.dp else 110.dp)
                    .clip(RoundedCornerShape(18.dp))
            )
        }
    }
}

/** A playable video/audio preview with an Original/Compressed toggle. */
@Composable
private fun BeforeAfterPlayback(
    mediaType: MediaType,
    beforeUri: Uri?,
    afterUri: Uri?,
    modifier: Modifier = Modifier
) {
    // 0 = compressed (default), 1 = original. Only offered when both exist.
    var side by remember { mutableIntStateOf(0) }
    val hasBefore = beforeUri != null
    val uri = when (side) {
        1 -> beforeUri
        else -> afterUri
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (hasBefore) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TogglePill(
                    text = stringResource(R.string.result_compressed_side),
                    selected = side == 0,
                    onClick = { side = 0 }
                )
                TogglePill(
                    text = stringResource(R.string.result_original_side),
                    selected = side == 1,
                    onClick = { side = 1 }
                )
            }
        }

        PlayablePreview(uri = uri, mediaType = mediaType)
    }
}

@Composable
private fun PlayablePreview(uri: Uri?, mediaType: MediaType) {
    val context = LocalContext.current
    var error by remember(uri) { mutableStateOf<String?>(null) }

    val player = remember(uri) {
        uri?.let { u ->
            ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.fromUri(u))
                prepare()
                playWhenReady = true
                // Surface errors to the user instead of a silent black box.
                addListener(object : Player.Listener {
                    override fun onPlayerError(error2: PlaybackException) {
                        error = error2.message
                    }
                })
            }
        }
    }

    DisposableEffect(uri) {
        onDispose { player?.release() }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
        contentAlignment = Alignment.Center
    ) {
        when {
            uri == null -> Label(text = stringResource(R.string.result_preview_unavailable))
            error != null -> Label(text = stringResource(R.string.result_preview_error))
            player == null -> Label(text = stringResource(R.string.result_preview_unavailable))
            mediaType == MediaType.VIDEO -> VideoSurface(player)
            mediaType == MediaType.AUDIO -> AudioSurface(player)
        }
    }
}

@Composable
private fun VideoSurface(player: ExoPlayer) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            PlayerView(ctx).apply {
                this.player = player
                useController = true
                setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
                resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
        },
        update = { it.player = player }
    )
}

@Composable
private fun AudioSurface(player: ExoPlayer) {
    Box(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = true
                    controllerShowTimeoutMs = 0
                    setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
                }
            },
            update = { it.player = player }
        )
    }
}

@Composable
private fun Label(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 20.dp)
    )
}

@Composable
private fun TogglePill(text: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
        )
    }
}
