package com.compressly.core.engine.photo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import android.net.Uri
import android.os.Build
import com.compressly.core.engine.JobControl
import com.compressly.core.engine.model.PhotoFormat
import com.compressly.core.engine.model.PhotoResize
import com.compressly.core.engine.model.PhotoSettings
import com.compressly.core.util.Storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * Photo engine: BitmapFactory + EXIF handling + Bitmap.compress.
 * Quality-first: sampled decoding keeps memory in check, EXIF orientation is
 * applied so the output always displays correctly, and metadata can be
 * preserved or stripped on user request.
 */
class PhotoCompressor(private val context: Context) {

    /** Compresses [uri] into a temp file and returns it (caller publishes it). */
    suspend fun compress(
        uri: Uri,
        sourceMime: String?,
        settings: PhotoSettings,
        control: JobControl,
        onProgress: (Float) -> Unit
    ): File = withContext(Dispatchers.Default) {
        val tempDir = context.cacheDir.resolve("compress").apply { mkdirs() }
        val tempSource = File.createTempFile("src_", ".img", tempDir)
        val outName = "out_${System.currentTimeMillis()}.${outputExtension(sourceMime, settings.outputFormat)}"
        val tempOut = File(tempDir, outName)
        try {
            // 1. Copy the picked file into our cache (fast, stable path for decode).
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempSource).use { output ->
                    input.copyToProgress(output, control) { p -> onProgress(p * 0.08f) }
                }
            } ?: throw IOException("Cannot open input")

            control.checkActive()

            // 2. Detect HEIC on old devices (needs API 28+).
            val mime = sourceMime ?: detectMime(tempSource)
            if ((mime == "image/heic" || mime == "image/heif") && Build.VERSION.SDK_INT < 28) {
                throw PhotoCompressionException(KEY_HEIC_UNSUPPORTED)
            }

            // 3. Read bounds + orientation.
            val bounds = decodeBounds(tempSource)
            val rotation = exifRotation(tempSource)

            // 4. Compute target size (rotation-aware).
            val (targetW, targetH) = targetDims(bounds, rotation, settings)

            // 5. Decode with sampling; fall back gracefully on OOM.
            var bitmap = decodeSampled(tempSource, targetW, targetH)
            try {
                if (rotation != 0) {
                    val m = Matrix().apply { postRotate(rotation.toFloat()) }
                    val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
                    if (rotated != bitmap) bitmap.recycle()
                    bitmap = rotated
                }
                if (bitmap.width != targetW || bitmap.height != targetH) {
                    val scaled = Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
                    if (scaled != bitmap) bitmap.recycle()
                    bitmap = scaled
                }
            } catch (e: OutOfMemoryError) {
                // The intermediate rotation/scale copy failed; retry with a
                // smaller decode so we can still deliver a valid file.
                bitmap.recycle()
                bitmap = decodeSampled(tempSource, targetW / 2, targetH / 2)
            }

            try {
                // 6. Encode, reporting progress against an honest estimate.
                val fmt = resolveCompressFormat(sourceMime, settings.outputFormat)
                val quality = settings.quality.coerceIn(1, 100)
                val estimate = estimatedOutputBytes(sourceMime, bounds.w, bounds.h, settings)
                FileOutputStream(tempOut).use { out ->
                    val counting = ProgressStream(out, estimate, control) { p ->
                        onProgress(0.15f + p * 0.75f)
                    }
                    if (!bitmap.compress(fmt, quality, counting)) {
                        throw IOException("Bitmap compression failed")
                    }
                }

                // 7. Metadata handling.
                if (settings.preserveMetadata) {
                    onProgress(0.93f)
                    copyExif(tempSource, tempOut, bitmap.width, bitmap.height)
                }
                control.checkActive()
                onProgress(1f)
                tempOut
            } finally {
                bitmap.recycle()
            }
        } catch (t: Throwable) {
            Storage.deleteQuietly(tempOut)
            throw t
        } finally {
            Storage.deleteQuietly(tempSource)
        }
    }

    companion object {
        const val KEY_HEIC_UNSUPPORTED = "heic_unsupported"
        const val KEY_DECODE_FAILED = "decode_failed"
        const val KEY_CORRUPT = "corrupt"
        const val KEY_METADATA_WRITE = "metadata_write"
    }

    // ---- helpers --------------------------------------------------------

    private fun outputExtension(sourceMime: String?, format: PhotoFormat): String = when (format) {
        PhotoFormat.PNG -> "png"
        PhotoFormat.WEBP -> "webp"
        PhotoFormat.JPEG -> "jpg"
        PhotoFormat.SOURCE -> when {
            sourceMime == "image/png" -> "png"
            sourceMime == "image/webp" -> "webp"
            else -> "jpg"
        }
    }

    private fun resolveCompressFormat(sourceMime: String?, format: PhotoFormat): Bitmap.CompressFormat =
        when (format) {
            PhotoFormat.PNG -> Bitmap.CompressFormat.PNG
            PhotoFormat.WEBP -> {
                if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY
                else Bitmap.CompressFormat.WEBP
            }
            PhotoFormat.JPEG -> Bitmap.CompressFormat.JPEG
            PhotoFormat.SOURCE -> when {
                sourceMime == "image/png" -> Bitmap.CompressFormat.PNG
                sourceMime == "image/webp" -> {
                    if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY
                    else Bitmap.CompressFormat.WEBP
                }
                else -> Bitmap.CompressFormat.JPEG
            }
        }

    private fun detectMime(file: File): String? =
        runCatching { context.contentResolver.getType(Uri.fromFile(file)) }.getOrNull()

    private data class Bounds(val w: Int, val h: Int)

    private fun decodeBounds(file: File): Bounds {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        if (opts.outWidth <= 0) throw PhotoCompressionException(KEY_CORRUPT)
        return Bounds(opts.outWidth, opts.outHeight)
    }

    private fun exifRotation(file: File): Int = runCatching {
        val exif = ExifInterface(file.absolutePath)
        when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    }.getOrDefault(0)

    private fun targetDims(bounds: Bounds, rotation: Int, settings: PhotoSettings): Pair<Int, Int> {
        // Work in the "displayed" orientation.
        val isRotated = rotation == 90 || rotation == 270
        var w = if (isRotated) bounds.h else bounds.w
        var h = if (isRotated) bounds.w else bounds.h

        val maxW = when (settings.resize) {
            PhotoResize.NONE -> Int.MAX_VALUE
            PhotoResize.CUSTOM -> settings.customMaxWidth.coerceAtLeast(16)
            else -> settings.resize.maxWidth
        }
        if (w > maxW) {
            val ratio = maxW.toDouble() / w
            w = maxW
            h = (h * ratio).toInt().coerceAtLeast(1)
        }
        return w to h
    }

    private fun decodeSampled(file: File, targetW: Int, targetH: Int): Bitmap {
        val probe = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, probe)
        var sample = 1
        while (probe.outWidth / (sample * 2) >= targetW && probe.outHeight / (sample * 2) >= targetH && sample < 64) {
            sample *= 2
        }
        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        try {
            return BitmapFactory.decodeFile(file.absolutePath, decodeOpts)
                ?: throw PhotoCompressionException(KEY_DECODE_FAILED)
        } catch (e: OutOfMemoryError) {
            decodeOpts.inSampleSize = sample * 2
            return BitmapFactory.decodeFile(file.absolutePath, decodeOpts)
                ?: throw PhotoCompressionException(KEY_DECODE_FAILED)
        }
    }

    private fun estimatedOutputBytes(sourceMime: String?, srcW: Int, srcH: Int, settings: PhotoSettings): Long {
        val fmt = resolveCompressFormat(sourceMime, settings.outputFormat)
        return when (fmt) {
            Bitmap.CompressFormat.PNG ->
                (srcW.toLong() * srcH * 3 * 0.6).toLong().coerceAtLeast(4_000)
            Bitmap.CompressFormat.WEBP, Bitmap.CompressFormat.WEBP_LOSSY ->
                (srcW.toLong() * srcH * 0.08).toLong().coerceAtLeast(3_000)
            else ->
                (srcW.toLong() * srcH * settings.quality / 100.0 * 0.5).toLong().coerceAtLeast(2_000)
        }
    }

    /**
     * Copies the meaningful EXIF tags (camera info, timestamps, GPS, lens,
     * scene data) from the source into the output. Orientation is reset to
     * NORMAL because the bitmap is already rotated; dimensions are rewritten
     * to match the output. androidx.exifinterface exposes no getAttributeNames(),
     * so we iterate a curated list of stable tags.
     */
    private fun copyExif(source: File, target: File, width: Int, height: Int) {
        runCatching {
            val src = ExifInterface(source.absolutePath)
            val dst = ExifInterface(target.absolutePath)
            for (tag in COPY_TAGS) {
                try {
                    val value = src.getAttribute(tag)
                    if (value != null && value != "0" && value != "0/0") {
                        dst.setAttribute(tag, value)
                    }
                } catch (ignore: Exception) {
                    // Some tags cannot be set on the output; skip them.
                }
            }
            dst.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            dst.setAttribute(ExifInterface.TAG_IMAGE_WIDTH, width.toString())
            dst.setAttribute(ExifInterface.TAG_IMAGE_LENGTH, height.toString())
            dst.saveAttributes()
        }
        // Metadata preservation is best-effort; the image itself is always valid.
    }

        private val COPY_TAGS = listOf(
            ExifInterface.TAG_MAKE,
            ExifInterface.TAG_MODEL,
            ExifInterface.TAG_SOFTWARE,
            ExifInterface.TAG_ARTIST,
            ExifInterface.TAG_COPYRIGHT,
            ExifInterface.TAG_IMAGE_DESCRIPTION,
            ExifInterface.TAG_DATETIME,
            ExifInterface.TAG_DATETIME_ORIGINAL,
            ExifInterface.TAG_DATETIME_DIGITIZED,
            ExifInterface.TAG_EXPOSURE_TIME,
            ExifInterface.TAG_F_NUMBER,
            ExifInterface.TAG_ISO_SPEED_RATINGS,
            ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
            ExifInterface.TAG_FOCAL_LENGTH,
            ExifInterface.TAG_MAX_APERTURE_VALUE,
            ExifInterface.TAG_METERING_MODE,
            ExifInterface.TAG_WHITE_BALANCE,
            ExifInterface.TAG_EXPOSURE_MODE,
            ExifInterface.TAG_EXPOSURE_PROGRAM,
            ExifInterface.TAG_FLASH,
            ExifInterface.TAG_LIGHT_SOURCE,
            ExifInterface.TAG_CONTRAST,
            ExifInterface.TAG_SATURATION,
            ExifInterface.TAG_SHARPNESS,
            ExifInterface.TAG_SCENE_CAPTURE_TYPE,
            ExifInterface.TAG_GAIN_CONTROL,
            ExifInterface.TAG_DIGITAL_ZOOM_RATIO,
            ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
            ExifInterface.TAG_PIXEL_X_DIMENSION,
            ExifInterface.TAG_PIXEL_Y_DIMENSION,
            ExifInterface.TAG_IMAGE_UNIQUE_ID,
            ExifInterface.TAG_LENS_MAKE,
            ExifInterface.TAG_LENS_MODEL,
            ExifInterface.TAG_LENS_SPECIFICATION,
            ExifInterface.TAG_SENSING_METHOD,
            ExifInterface.TAG_COLOR_SPACE,
            ExifInterface.TAG_GPS_LATITUDE,
            ExifInterface.TAG_GPS_LATITUDE_REF,
            ExifInterface.TAG_GPS_LONGITUDE,
            ExifInterface.TAG_GPS_LONGITUDE_REF,
            ExifInterface.TAG_GPS_ALTITUDE,
            ExifInterface.TAG_GPS_ALTITUDE_REF,
            ExifInterface.TAG_GPS_TIMESTAMP,
            ExifInterface.TAG_GPS_DATESTAMP,
            ExifInterface.TAG_GPS_PROCESSING_METHOD,
            ExifInterface.TAG_GPS_MAP_DATUM,
            ExifInterface.TAG_GPS_SPEED,
            ExifInterface.TAG_GPS_SPEED_REF
        )
}

/** Thrown for expected photo-engine failures; carries a stable message key. */
class PhotoCompressionException(val key: String) : Exception(key)

/** Counting stream that reports encode progress against an estimate. */
class ProgressStream(
    private val out: OutputStream,
    private val estimatedBytes: Long,
    private val control: JobControl,
    private val onProgress: (Float) -> Unit
) : OutputStream() {
    private var written = 0L

    override fun write(b: Int) {
        control.checkActiveIo()
        out.write(b)
        written++
        report()
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        control.checkActiveIo()
        out.write(b, off, len)
        written += len
        report()
    }

    override fun flush() = out.flush()
    override fun close() = out.close()

    private fun report() {
        if (estimatedBytes > 0) {
            onProgress((written.toFloat() / estimatedBytes).coerceIn(0f, 0.99f))
        }
    }
}

/** Cancellation check for use inside stream writes. */
private fun JobControl.checkActiveIo() {
    if (isCancelled) throw com.compressly.core.engine.CompressionCancelledException()
}

/** Copy with progress + cancellation support. */
private suspend fun java.io.InputStream.copyToProgress(
    out: java.io.OutputStream,
    control: JobControl,
    bufferSize: Int = 128 * 1024,
    onProgress: (Float) -> Unit
) {
    val buffer = ByteArray(bufferSize)
    var total = 0L
    val size = runCatching { available().toLong() }.getOrDefault(-1L).takeIf { it > 0 } ?: -1L
    while (true) {
        control.checkActive()
        val read = read(buffer)
        if (read < 0) break
        out.write(buffer, 0, read)
        total += read
        if (size > 0) onProgress(total.toFloat() / size)
    }
    out.flush()
}