package com.compressly.core.engine

import android.content.Context
import android.net.Uri
import com.compressly.core.engine.audio.AudioCompressionException
import com.compressly.core.engine.audio.AudioCompressor
import com.compressly.core.engine.model.AudioFormat
import com.compressly.core.engine.model.CompressionResult
import com.compressly.core.engine.model.CompressionSettings
import com.compressly.core.engine.model.InputItem
import com.compressly.core.engine.model.ItemPhase
import com.compressly.core.engine.model.MediaType
import com.compressly.core.engine.model.PhotoFormat
import com.compressly.core.engine.model.PhotoSettings
import com.compressly.core.engine.photo.PhotoCompressionException
import com.compressly.core.engine.photo.PhotoCompressor
import com.compressly.core.engine.video.MediaCodecTranscoder
import com.compressly.core.engine.video.VideoCompressionException
import com.compressly.core.data.OutputStore
import com.compressly.core.util.Storage
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Dispatches a single file to the right engine, then publishes the result to
 * MediaStore. All heavy work happens on Dispatchers.Default (never the main
 * thread); cancellation propagates as CompressionCancelledException.
 */
class Compressor(private val context: Context) {

    suspend fun compressItem(
        jobId: Long,
        item: InputItem,
        settings: CompressionSettings,
        control: JobControl,
        onProgress: (ItemPhase, Float) -> Unit
    ): CompressionResult {
        val startedAt = System.currentTimeMillis()
        onProgress(ItemPhase.PREPARING, 0f)
        try {
            val output = when (settings) {
                is CompressionSettings.Photo -> compressPhoto(item, settings.settings, control, onProgress)
                is CompressionSettings.Video -> compressVideo(item, settings.settings, settings.preset, control, onProgress)
                is CompressionSettings.Audio -> compressAudio(item, settings.settings, control, onProgress)
            }
            onProgress(ItemPhase.FINALIZING, 1f)
            return CompressionResult(
                itemId = item.itemId,
                jobId = jobId,
                fileName = item.displayName,
                inputUri = item.uri,
                inputSize = item.sizeBytes,
                outputUri = output.uri,
                outputSize = output.size,
                durationMs = System.currentTimeMillis() - startedAt,
                success = true,
                settingsSummary = output.summary
            )
        } finally {
            onProgress(ItemPhase.DONE, 1f)
        }
    }

    private data class EngineOutput(val uri: Uri, val size: Long, val summary: String)

    private suspend fun compressPhoto(
        item: InputItem,
        settings: PhotoSettings,
        control: JobControl,
        onProgress: (ItemPhase, Float) -> Unit
    ): EngineOutput {
        val mime = context.contentResolver.getType(item.uri)
        val temp = PhotoCompressor(context).compress(item.uri, mime, settings, control) {
            onProgress(ItemPhase.COMPRESSING, it)
        }
        val outMime = when {
            settings.outputFormat == PhotoFormat.PNG -> "image/png"
            settings.outputFormat == PhotoFormat.WEBP -> "image/webp"
            settings.outputFormat == PhotoFormat.JPEG -> "image/jpeg"
            mime == "image/png" -> "image/png"
            mime == "image/webp" -> "image/webp"
            else -> "image/jpeg"
        }
        val uri = OutputStore.publishTempFile(context, MediaType.PHOTO, temp, item.displayName, outMime)
        val summary = "${settings.quality}% quality, ${outMime.removePrefix("image/").uppercase()}"
        return EngineOutput(uri, sizeOf(uri), summary)
    }

    private suspend fun compressVideo(
        item: InputItem,
        settings: com.compressly.core.engine.model.VideoSettings,
        preset: com.compressly.core.engine.model.CompressionPreset,
        control: JobControl,
        onProgress: (ItemPhase, Float) -> Unit
    ): EngineOutput {
        val info = runCatching { MediaInspector.inspect(context, item.uri) }.getOrNull()
            ?: com.compressly.core.engine.model.MediaInfo(hasVideo = true)
        val temp = File.createTempFile("out_", ".mp4", context.cacheDir)
        try {
            val stats = MediaCodecTranscoder(context).transcode(
                inputUri = item.uri,
                outputPath = temp.absolutePath,
                info = info,
                settings = settings,
                preset = preset,
                control = control,
                onProgress = { onProgress(ItemPhase.COMPRESSING, it) }
            )
            val uri = OutputStore.publishTempFile(context, MediaType.VIDEO, temp, item.displayName, "video/mp4")
            val codecName = if (settings.codec == com.compressly.core.engine.model.VideoCodec.H265) "H.265" else "H.264"
            val summary = "${codecName}, ${stats.durationMs / 1000}s"
            return EngineOutput(uri, sizeOf(uri), summary)
        } finally {
            Storage.deleteQuietly(temp)
        }
    }

    private suspend fun compressAudio(
        item: InputItem,
        settings: com.compressly.core.engine.model.AudioSettings,
        control: JobControl,
        onProgress: (ItemPhase, Float) -> Unit
    ): EngineOutput {
        val info = runCatching { MediaInspector.inspect(context, item.uri) }.getOrNull()
            ?: com.compressly.core.engine.model.MediaInfo(hasAudio = true)
        val temp = AudioCompressor(context).compress(item.uri, info, settings, control) {
            onProgress(ItemPhase.COMPRESSING, it)
        }
        val isAac = settings.format == AudioFormat.AAC
        val outMime = if (isAac) "audio/mp4" else "audio/mpeg"
        val uri = OutputStore.publishTempFile(context, MediaType.AUDIO, temp, item.displayName, outMime)
        val formatName = if (isAac) "AAC" else "MP3"
        val summary = "$formatName ${settings.bitrate} kbps"
        return EngineOutput(uri, sizeOf(uri), summary)
    }

    private fun sizeOf(uri: Uri): Long =
        runCatching {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
        }.getOrNull() ?: 0L
}

/** Maps engine failures to a stable, user-facing error key. */
fun errorKeyOf(t: Throwable): String = when (t) {
    is CompressionCancelledException -> "cancelled"
    is PhotoCompressionException -> t.key
    is VideoCompressionException -> t.key
    is AudioCompressionException -> t.key
    is FileNotFoundException -> "file_not_found"
    is SecurityException -> "file_not_found"
    is android.media.MediaCodec.CodecException -> "encode_failed"
    is IllegalStateException -> "encode_failed"
    is IOException -> "output"
    else -> "generic"
}
