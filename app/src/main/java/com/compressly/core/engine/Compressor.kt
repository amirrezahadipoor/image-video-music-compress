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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Dispatches a single file to the right engine, then publishes the result to
 * MediaStore. All heavy work happens on Dispatchers.Default (never the main
 * thread); cancellation propagates as CompressionCancelledException.
 */
class Compressor(private val context: Context) {

    // Inspecting media metadata is expensive (a full MediaMetadataRetriever
    // pass); cache per URI so a batch never inspects the same file twice.
    private val infoCache = java.util.concurrent.ConcurrentHashMap<String, com.compressly.core.engine.model.MediaInfo>()

    private fun mediaInfoOf(uri: Uri, fallbackHasVideo: Boolean): com.compressly.core.engine.model.MediaInfo =
        infoCache.getOrPut(uri.toString()) {
            runCatching { MediaInspector.inspect(context, uri) }.getOrNull()
                ?: com.compressly.core.engine.model.MediaInfo(hasVideo = fallbackHasVideo)
        }

    /**
     * ANALYSIS-FIX: the same MediaInfo the encoder plans from must also carry
     * the measured content complexity, or the live estimate and the encoded
     * result would disagree. Runs once per video file (cached above), costs a
     * few hundred ms off the main thread, and silently degrades to "unknown"
     * — the planner then uses the neutral path.
     */
    private fun analysedInfoOf(uri: Uri, fallbackHasVideo: Boolean): com.compressly.core.engine.model.MediaInfo {
        val cached = infoCache[uri.toString()]
        if (cached != null && (cached.hasComplexity || !cached.hasVideo)) return cached
        val base = mediaInfoOf(uri, fallbackHasVideo)
        if (!base.hasVideo || base.durationMs <= 0) return base
        val analysis = com.compressly.core.engine.analysis.ComplexityAnalyzer.analyze(
            context, uri, base.durationMs
        ) ?: return base
        return base.copy(
            complexity = analysis.complexity,
            motion = analysis.motion,
            detail = analysis.detail,
            color = analysis.color
        ).also { infoCache[uri.toString()] = it }
    }

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
            onProgress(ItemPhase.DONE, 1f)
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
        } catch (t: Throwable) {
            // Don't report DONE on failure — the caller will set FAILED.
            throw t
        }
    }

    private data class EngineOutput(val uri: Uri, val size: Long, val summary: String)

    /**
     * Publishes the engine's temp file - unless it did not actually shrink, in
     * which case the original is handed back untouched.
     *
     * This is the last line of defence for all three engines and it works on the
     * real encoded byte count rather than an estimate, so it can never wrongly
     * skip a compression: it only ever fires when re-encoding demonstrably made
     * the file no smaller.
     */
    private suspend fun publishOrKeepOriginal(
        item: InputItem,
        mediaType: MediaType,
        temp: File,
        mime: String,
        summary: String,
        onProgress: (ItemPhase, Float) -> Unit
    ): EngineOutput {
        val encoded = temp.length()
        // OUTPUT-FIX: a 0-byte result is never a successful compression. The
        // size comparison below would pass it (0 < 95% of input) and publish
        // an empty file into the user's gallery as a "done" item.
        if (encoded <= 0) {
            Storage.deleteQuietly(temp)
            throw when (mediaType) {
                MediaType.PHOTO -> PhotoCompressionException(PhotoCompressor.KEY_ENCODE)
                MediaType.VIDEO -> VideoCompressionException("encode_failed")
                MediaType.AUDIO -> AudioCompressionException(AudioCompressor.KEY_ENCODE)
            }
        }
        if (item.sizeBytes > 0 && encoded >= (item.sizeBytes * 0.95).toLong()) {
            Storage.deleteQuietly(temp)
            return keepOriginal(item, mediaType, onProgress)
        }
        val uri = OutputStore.publishTempFile(context, mediaType, temp, item.displayName, mime)
        return EngineOutput(uri, sizeOf(uri), summary)
    }

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
        // SUMMARY-HONESTY-FIX: PNG is lossless — "85% quality, PNG" claims a
        // quality level the encoder never applied. Report it as lossless.
        val summary = when {
            settings.outputFormat == PhotoFormat.PNG ||
                (settings.outputFormat == PhotoFormat.SOURCE && mime == "image/png") ->
                "${outMime.removePrefix("image/").uppercase()} (lossless)"
            else -> "${settings.quality}% quality, ${outMime.removePrefix("image/").uppercase()}"
        }
        return publishOrKeepOriginal(item, MediaType.PHOTO, temp, outMime, summary, onProgress)
    }

    private suspend fun compressVideo(
        item: InputItem,
        settings: com.compressly.core.engine.model.VideoSettings,
        preset: com.compressly.core.engine.model.CompressionPreset,
        control: JobControl,
        onProgress: (ItemPhase, Float) -> Unit
    ): EngineOutput {
        // ANALYSIS-FIX: content-aware planning — the MediaInfo carries the
        // measured complexity so Smart prices THIS clip, not "an average clip".
        val info = analysedInfoOf(item.uri, fallbackHasVideo = true)
        // Nothing is being changed about the video AND re-encoding would not
        // shrink it: hand the original file straight back instead of spending a
        // decode+encode pass (and a generation of quality) for nothing.
        val estimate = com.compressly.core.engine.estimate.SizeEstimator
            .estimateVideo(info, settings, preset)
        if (com.compressly.core.engine.video.VideoPlanner.isNoOpTranscode(info, settings, preset) &&
            com.compressly.core.engine.video.VideoPlanner.shouldKeepOriginal(estimate, item.sizeBytes)
        ) {
            return keepOriginal(item, MediaType.VIDEO, onProgress)
        }
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
            // SUMMARY-FIX: report the codec that was ACTUALLY written. If the
            // device has no HEVC encoder the engine falls back to H.264 — that
            // result must not be presented as an H.265 file.
            val codecName = if (stats.codec == "h265") "H.265" else "H.264"
            // BUG-5 FIX: Integer division of durationMs < 1000 produces "0s".
            // Use humanDuration for a proper "0:XX" display for short clips.
            val durationLabel = com.compressly.core.util.Formats.humanDuration(stats.durationMs)
            val summary = "$codecName, $durationLabel"
            return publishOrKeepOriginal(item, MediaType.VIDEO, temp, "video/mp4", summary, onProgress)
        } finally {
            Storage.deleteQuietly(temp)
        }
    }

    /**
     * Publishes the input file unchanged. Used when the planned transcode would
     * not shrink it: re-encoding at the rate the source already carries only
     * costs quality, so the file is copied through and reported honestly.
     */
    private suspend fun keepOriginal(
        item: InputItem,
        mediaType: MediaType,
        onProgress: (ItemPhase, Float) -> Unit
    ): EngineOutput = withContext(Dispatchers.IO) {
        val fallbackMime = when (mediaType) {
            MediaType.PHOTO -> "image/jpeg"
            MediaType.VIDEO -> "video/mp4"
            MediaType.AUDIO -> "audio/mpeg"
        }
        val mime = context.contentResolver.getType(item.uri) ?: fallbackMime
        val ext = when (mediaType) {
            MediaType.VIDEO -> com.compressly.core.util.Mime.videoExtension(mime)
            MediaType.PHOTO -> com.compressly.core.util.Mime.photoExtension(mime)
            MediaType.AUDIO -> if (mime.contains("mp4") || mime.contains("m4a")) "m4a" else "mp3"
        }
        val temp = File.createTempFile("keep_", ".$ext", context.cacheDir)
        try {
            context.contentResolver.openInputStream(item.uri)?.use { input ->
                temp.outputStream().use { out -> input.copyTo(out, 256 * 1024) }
            } ?: throw FileNotFoundException("Cannot read source")
            onProgress(ItemPhase.COMPRESSING, 1f)
            val uri = OutputStore.publishTempFile(context, mediaType, temp, item.displayName, mime)
            EngineOutput(uri, sizeOf(uri), context.getString(ir.siliksama.hajmino.R.string.already_optimized))
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
        val info = mediaInfoOf(item.uri, fallbackHasVideo = false)
        // No rate-based pre-check here on purpose: unlike a video transcode an
        // audio encode costs seconds, and the user may be changing container
        // (MP3 -> M4A) rather than chasing bytes. publishOrKeepOriginal() below
        // still guarantees a bigger file is never handed back.
        val temp = AudioCompressor(context).compress(item.uri, info, settings, control) {
            onProgress(ItemPhase.COMPRESSING, it)
        }
        val isAac = settings.format == AudioFormat.AAC
        val outMime = if (isAac) "audio/mp4" else "audio/mpeg"
        val formatName = if (isAac) "AAC" else "MP3"
        val usedKbps = com.compressly.core.engine.audio.AudioPlanner
            .targetBitrateKbps(settings.bitrate, info.audioBitrate)
        val summary = "$formatName $usedKbps kbps"
        return publishOrKeepOriginal(item, MediaType.AUDIO, temp, outMime, summary, onProgress)
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
