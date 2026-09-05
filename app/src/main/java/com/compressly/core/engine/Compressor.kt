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
import com.compressly.core.engine.video.GifToMp4Converter
import com.compressly.core.engine.video.MediaCodecTranscoder
import com.compressly.core.engine.video.GifConversionException
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
            color = analysis.color,
            sceneCuts = analysis.sceneCuts
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
            // OUTPUT-LOCATION: where the result goes (default / same folder as
            // the source / a per-job chosen folder) applies equally to all
            // engines, so it is resolved once here and handed to each.
            val location = settings.outputLocation
            val outputFolder = settings.outputFolder
            val replace = settings.replaceOriginal
            val output = when (settings) {
                is CompressionSettings.Photo ->
                    compressPhoto(item, settings.settings, location, outputFolder, replace, control, onProgress)
                is CompressionSettings.Video ->
                    compressVideo(item, settings.settings, settings.preset, location, outputFolder, replace, control, onProgress)
                is CompressionSettings.Audio ->
                    compressAudio(item, settings.settings, location, outputFolder, replace, control, onProgress)
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
        location: com.compressly.core.engine.model.OutputLocation,
        outputFolder: String?,
        replaceOriginal: Boolean,
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
        // REPLACE-ORIGINAL-FIX: overwrite the SOURCE document in place (same
        // URI/path) so no duplicate is ever created — this is why the old
        // "publish a new row + delete the original" produced thousands of
        // extra photos: contentResolver.delete() silently fails on many SAF
        // tree / picker URIs, so the original stayed and a new row appeared.
        // Only falls back to the publish+delete path when the source can't be
        // written (read-only picker grant).
        if (replaceOriginal) {
            OutputStore.replaceInPlace(context, item.uri, temp)?.let { replaced ->
                return EngineOutput(replaced, sizeOf(replaced, encoded), summary)
            }
        }
        // OUTPUT-LOCATION: publish into the requested folder (or the source's
        // own folder when replacing), honoring the per-job custom tree.
        val uri = OutputStore.publishTempFile(
            context, mediaType, temp, item.displayName, mime,
            location = location, sourceUri = item.uri, customTreeUri = outputFolder
        )
        return EngineOutput(uri, sizeOf(uri), summary)
    }

    private suspend fun compressPhoto(
        item: InputItem,
        settings: PhotoSettings,
        location: com.compressly.core.engine.model.OutputLocation,
        outputFolder: String?,
        replaceOriginal: Boolean,
        control: JobControl,
        onProgress: (ItemPhase, Float) -> Unit
    ): EngineOutput {
        val mime = context.contentResolver.getType(item.uri)
        // GIF-FIX: an animated GIF is not a static photo — flattening it to a
        // single JPEG/WebP frame loses the animation. Convert it to a playable
        // MP4 instead (and never modify the source GIF).
        if (mime == "image/gif") return compressGif(item, control, onProgress)
        val photoEngine = PhotoCompressor(context)
        val temp = photoEngine.compress(item.uri, mime, settings, control) {
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
        // The ladder may have descended below the nominal rung; report the
        // quality the encoder ACTUALLY used (photoEngine.lastQualityUsed).
        val realQuality = photoEngine.lastQualityUsed.takeIf { it > 0 } ?: settings.quality
        val summary = when {
            settings.outputFormat == PhotoFormat.PNG ||
                (settings.outputFormat == PhotoFormat.SOURCE && mime == "image/png") ->
                "${outMime.removePrefix("image/").uppercase()} (lossless)"
            else -> "$realQuality% quality, ${outMime.removePrefix("image/").uppercase()}"
        }
        return publishOrKeepOriginal(item, MediaType.PHOTO, temp, outMime, summary, location, outputFolder, replaceOriginal, onProgress)
    }

    private suspend fun compressVideo(
        item: InputItem,
        settings: com.compressly.core.engine.model.VideoSettings,
        preset: com.compressly.core.engine.model.CompressionPreset,
        location: com.compressly.core.engine.model.OutputLocation,
        outputFolder: String?,
        replaceOriginal: Boolean,
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
        // SIZE-GUARD-FIX: item.sizeBytes can be -1 (MediaStore query failed at
        // scan time) — measure the real size so the no-gain guard actually
        // works instead of silently skipping itself.
        val inputSize = item.sizeBytes.takeIf { it > 0 } ?: sizeOf(item.uri)
        if (com.compressly.core.engine.video.VideoPlanner.isNoOpTranscode(info, settings, preset) &&
            com.compressly.core.engine.video.VideoPlanner.shouldKeepOriginal(estimate, inputSize)
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
            // result must not be presented as an H.265 file. AV1 is labeled
            // too, so a software-AV1 output is never shown as H.264.
            val codecName = when (stats.codec) {
                "h265" -> "H.265"
                "av1" -> "AV1"
                else -> "H.264"
            }
            // BUG-5 FIX: Integer division of durationMs < 1000 produces "0s".
            // Use humanDuration for a proper "0:XX" display for short clips.
            val durationLabel = com.compressly.core.util.Formats.humanDuration(stats.durationMs)
            val summary = "$codecName, $durationLabel"
            return publishOrKeepOriginal(item, MediaType.VIDEO, temp, "video/mp4", summary, location, outputFolder, replaceOriginal, onProgress)
        } finally {
            Storage.deleteQuietly(temp)
        }
    }

    /**
     * Publishes the input file unchanged. Used when the planned transcode would
     * not shrink it: re-encoding at the rate the source already carries only
     * costs quality, so the file is reported honestly as-is.
     *
     * KEEP-ORIGINAL-FIX: no copy is made and no new MediaStore row is created.
     * The old implementation copied the WHOLE file into a temp and published a
     * second, byte-identical file into the gallery — for a multi-hundred-MB
     * video that was minutes of needless I/O, doubled storage, and a duplicate
     * entry in the user's photo app. The input URI is already a stable,
     * readable, shareable content URI, so it is returned directly.
     */
    private suspend fun compressGif(
        item: InputItem,
        control: JobControl,
        onProgress: (ItemPhase, Float) -> Unit
    ): EngineOutput {
        val temp = File.createTempFile("out_", ".mp4", context.cacheDir)
        try {
            GifToMp4Converter(context).convert(item.uri, temp.absolutePath, control) {
                onProgress(ItemPhase.COMPRESSING, it)
            }
            // Deliberate conversion (GIF -> MP4), so publish the MP4 even if
            // it happens to be a byte or two larger than the source.
            val uri = OutputStore.publishTempFile(
                context, MediaType.VIDEO, temp, item.displayName, "video/mp4"
            )
            return EngineOutput(uri, sizeOf(uri), context.getString(ir.siliksama.hajmino.R.string.gif_to_mp4))
        } catch (e: VideoCompressionException) {
            throw e
        } finally {
            Storage.deleteQuietly(temp)
        }
    }

    private suspend fun keepOriginal(
        item: InputItem,
        mediaType: MediaType,
        onProgress: (ItemPhase, Float) -> Unit
    ): EngineOutput = withContext(Dispatchers.IO) {
        val size = item.sizeBytes.takeIf { it > 0 } ?: sizeOf(item.uri)
        onProgress(ItemPhase.COMPRESSING, 1f)
        EngineOutput(item.uri, size, context.getString(ir.siliksama.hajmino.R.string.already_optimized))
    }

    private suspend fun compressAudio(
        item: InputItem,
        settings: com.compressly.core.engine.model.AudioSettings,
        location: com.compressly.core.engine.model.OutputLocation,
        outputFolder: String?,
        replaceOriginal: Boolean,
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
        return publishOrKeepOriginal(item, MediaType.AUDIO, temp, outMime, summary, location, outputFolder, replaceOriginal, onProgress)
    }

    private fun sizeOf(uri: Uri): Long =
        sizeOf(uri, -1L)

    /**
     * Size of the published output, preferring the known encoded length
     * (exact) over an asset-file descriptor probe (which can be stale or fail
     * for some documents).
     */
    private fun sizeOf(uri: Uri, known: Long): Long =
        if (known > 0) known else runCatching {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
        }.getOrNull() ?: 0L
}

/** Maps engine failures to a stable, user-facing error key. */
fun errorKeyOf(t: Throwable): String = when (t) {
    is CompressionCancelledException -> "cancelled"
    is GifConversionException -> t.key
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
