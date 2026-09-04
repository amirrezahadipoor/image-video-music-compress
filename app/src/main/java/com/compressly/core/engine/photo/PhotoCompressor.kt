package com.compressly.core.engine.photo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
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
 *
 * Key improvements vs. baseline:
 * - ICC / wide-gamut color space is preserved when decoding (API 26+). Without
 *   this, Display-P3 photos from modern phones (Pixel, Samsung S-series, iPhone
 *   sideloads) lose their vivid reds/greens because BitmapFactory silently
 *   converts to sRGB. Setting preferredColorSpace = null lets the decoder keep
 *   the source color space; the encoder then embeds it in the output.
 * - Smart mode adaptive loop skips PNG (lossless — quality param has no effect).
 * - OOM recovery is fully contained: both the primary and fallback bitmap paths
 *   recycle properly.
 */
class PhotoCompressor(private val context: Context) {

    /**
     * The quality rung the last Smart encode actually landed on (0 = unknown).
     * Smart descends its ladder until the size target is met, so the number
     * shown to the user must be the real one, not the ladder's first rung.
     */
    @Volatile
    var lastQualityUsed: Int = 0

    suspend fun compress(
        uri: Uri,
        sourceMime: String?,
        settings: PhotoSettings,
        control: JobControl,
        onProgress: (Float) -> Unit
    ): File = withContext(Dispatchers.Default) {
        val tempDir = context.cacheDir.resolve("compress").apply { mkdirs() }
        val tempSource = File.createTempFile("src_", ".img", tempDir)
        val outName = "out_${System.nanoTime()}.${outputExtension(sourceMime, settings.outputFormat)}"
        val tempOut = File(tempDir, outName)
        try {
            // 1. Copy into cache for stable file-path decoding.
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempSource).use { output ->
                    input.copyToProgress(output, control) { p -> onProgress(p * 0.08f) }
                }
            } ?: throw IOException("Cannot open input")

            control.checkActive()

            // 2. HEIC guard.
            val mime = sourceMime ?: detectMime(tempSource)
            if ((mime == "image/heic" || mime == "image/heif") && Build.VERSION.SDK_INT < 28) {
                throw PhotoCompressionException(KEY_HEIC_UNSUPPORTED)
            }

            // 3. Bounds + EXIF rotation.
            val bounds = decodeBounds(tempSource)
            val rotation = exifRotation(tempSource)

            // 4. Target dimensions.
            val (targetW, targetH) = targetDims(bounds, rotation, settings)

            // 5. Decode — preserving ICC/wide-gamut color space on API 26+.
            // PHOTO-L1 FIX: pass the already-computed bounds to decodeSampled so
            // it doesn't call decodeBounds() again internally (two file-open passes).
            val lossy = fmtIsLossy(sourceMime, settings.outputFormat)
            // PNG and WebP can carry an alpha channel. RGB_565 cannot represent
            // one, so decoding such a source into 565 throws the transparency
            // away at decode time and there is nothing left to composite later.
            val mayHaveAlpha = mime == "image/png" || mime == "image/webp"
            // SMART-FIX: Smart mode promises a perceptual quality floor of ~70%.
            // RGB_565 caps the palette at 65k colours — visible banding in
            // skies, skin and gradients — so Smart always decodes ARGB_8888 and
            // only explicit manual qualities below 90 may use 565.
            val use565 = lossy && !mayHaveAlpha && !settings.smart && settings.quality < 90
            var bitmap = decodeSampled(tempSource, targetW, targetH, use565, bounds)

            try {
                if (rotation != 0) {
                    val m = Matrix().apply { postRotate(rotation.toFloat()) }
                    val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
                    if (rotated != bitmap) bitmap.recycle()
                    bitmap = rotated
                }
                val finalW = minOf(targetW, bitmap.width)
                val finalH = minOf(targetH, bitmap.height)
                if (bitmap.width != finalW || bitmap.height != finalH) {
                    val scaled = Bitmap.createScaledBitmap(bitmap, finalW, finalH, true)
                    if (scaled != bitmap) bitmap.recycle()
                    bitmap = scaled
                }
            } catch (e: OutOfMemoryError) {
                bitmap.recycle()
                try {
                    bitmap = decodeSampled(tempSource, targetW / 2, targetH / 2, use565)
                } catch (oom2: OutOfMemoryError) {
                    throw e
                }
            }

            // JPEG and lossy WebP have no alpha channel, and Android's encoder
            // resolves transparent pixels to BLACK rather than to the background.
            // Converting a logo, a sticker or a screenshot with a transparent
            // background therefore produced an image on solid black. Composite
            // over white first, which is what every other image tool does.
            if (lossy && bitmap.hasAlpha()) {
                val flattened = Bitmap.createBitmap(
                    bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888
                )
                Canvas(flattened).apply {
                    drawColor(Color.WHITE)
                    drawBitmap(bitmap, 0f, 0f, null)
                }
                bitmap.recycle()
                bitmap = flattened
            }

            try {
                val fmt = resolveCompressFormat(sourceMime, settings.outputFormat)
                val estimate = estimatedOutputBytes(sourceMime, bounds.w, bounds.h, settings)
                val sourceBytes = tempSource.length().takeIf { it > 0 } ?: 0L
                val targetBytes = if (settings.smart) {
                    (sourceBytes * 0.5).toLong().coerceAtLeast(60_000)
                } else 0L

                // PNG is lossless — quality has no effect; one encode is enough.
                val isPng = fmt == Bitmap.CompressFormat.PNG
                // SMART-FIX: the quality ladder never drops below 72. The old
                // 85/75/65 ladder ended at 65, quietly violating Smart's
                // "never below ~70% perceptual quality" promise whenever the
                // 50%-of-source target needed the last rung.
                // SMART-PHOTO-ADVISOR: the ladder now starts on the rung this
                // image deserves (text high, smooth high, noisy lower) and still
                // descends until the 50 % target is met — content-aware like the
                // video engine, same size guarantee.
                val qualities = when {
                    isPng -> intArrayOf(0)
                    settings.smart -> SmartPhotoAdvisor.ladderFor(photoMetricsOf(bitmap))
                    else -> intArrayOf(settings.quality.coerceIn(1, 100))
                }
                val effectiveSmart = settings.smart && !isPng

                var encoded = false
                // PROGRESS-MONOTONIC-FIX: each ladder rung restarts its own
                // 0..1 encode progress, so without a clamp the bar would jump
                // backwards (0.90 -> 0.15) on every re-encode. Carry the
                // highest value reported so far (within the 0.15..0.90 encode
                // band) into the next rung; the metadata step (0.93) and the
                // final 1.0 both sit above the band and keep the curve
                // monotonically increasing.
                var lastReportedProgress = 0f
                for (q in qualities) {
                    control.checkActive()
                    FileOutputStream(tempOut).use { out ->
                        val counting = ProgressStream(out, estimate, control) { p ->
                            val mapped = (0.15f + p * 0.75f).coerceAtMost(0.90f)
                            val monotonic = if (mapped > lastReportedProgress) mapped else lastReportedProgress
                            lastReportedProgress = monotonic
                            onProgress(monotonic)
                        }
                        if (!bitmap.compress(fmt, q, counting)) {
                            throw PhotoCompressionException(KEY_ENCODE)
                        }
                    }
                    encoded = true
                    lastQualityUsed = q
                    if (!effectiveSmart) break
                    if (tempOut.length() <= targetBytes) break
                }
                if (!encoded) throw PhotoCompressionException(KEY_ENCODE)

                // 7. Metadata.
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

    // ── Bounds ──────────────────────────────────────────────────────────────

    private data class Bounds(val w: Int, val h: Int)

    private fun decodeBounds(file: File): Bounds {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        return Bounds(opts.outWidth.coerceAtLeast(1), opts.outHeight.coerceAtLeast(1))
    }

    // ── Target dimensions ────────────────────────────────────────────────────

    private fun targetDims(bounds: Bounds, rotation: Int, settings: PhotoSettings): Pair<Int, Int> {
        val rotated = rotation == 90 || rotation == 270
        val srcW = if (rotated) bounds.h else bounds.w
        val srcH = if (rotated) bounds.w else bounds.h
        val maxW = when (settings.resize) {
            PhotoResize.NONE -> srcW
            PhotoResize.CUSTOM -> settings.customMaxWidth.coerceIn(64, 8000)
            else -> settings.resize.maxWidth
        }
        return if (srcW <= maxW) {
            srcW to srcH
        } else {
            val ratio = maxW.toDouble() / srcW
            maxW to (srcH * ratio).toInt().coerceAtLeast(1)
        }
    }

    // ── Sampled decode (ICC-preserving) ─────────────────────────────────────

    // PHOTO-L1 FIX: accept pre-computed bounds so the caller does not pay for
    // a second full BitmapFactory.decodeFile(inJustDecodeBounds=true) pass.
    // The original signature is kept as an internal overload for the OOM-retry
    // path where we don't have the bounds readily available.
    private fun decodeSampled(file: File, targetW: Int, targetH: Int, use565: Boolean): Bitmap =
        decodeSampled(file, targetW, targetH, use565, decodeBounds(file))

    private fun decodeSampled(
        file: File,
        targetW: Int,
        targetH: Int,
        use565: Boolean,
        bounds: Bounds
    ): Bitmap {
        var sample = 1
        var w = bounds.w
        var h = bounds.h
        while (w / 2 >= targetW && h / 2 >= targetH) {
            w /= 2; h /= 2; sample *= 2
        }
        // Cap single-decode at 4096 px to bound memory — very large photos
        // (50 MP+) would OOM otherwise.
        while (w > 4096 || h > 4096) {
            w /= 2; h /= 2; sample *= 2
        }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = if (use565) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888
            // ICC / wide-gamut color-space preservation (API 26+).
            // Setting inPreferredColorSpace = null tells BitmapFactory to keep
            // whatever color space is embedded in the source (sRGB, Display-P3,
            // AdobeRGB…). Without this flag the decoder silently converts
            // everything to sRGB, causing vivid colors on modern phones to fade.
            if (Build.VERSION.SDK_INT >= 26) {
                inPreferredColorSpace = null // keep source ICC profile
            }
        }
        return BitmapFactory.decodeFile(file.absolutePath, opts)
            ?: throw PhotoCompressionException(KEY_DECODE)
    }

    // ── Format helpers ───────────────────────────────────────────────────────

    /**
     * The output container kind, decided without touching any
     * [Bitmap.CompressFormat] constant.
     *
     * This used to be a `when` over CompressFormat, which is a crash on
     * Android 8-9: Kotlin compiles an enum `when` into a static switch-mapping
     * table that reads *every* referenced constant at class-init, and
     * `CompressFormat.WEBP_LOSSY` only exists from API 30. Any photo compressed
     * on a minSdk-26 device therefore died with NoSuchFieldError before it ever
     * wrote a byte. Resolving to a plain string first keeps the API-30 constant
     * behind the runtime SDK check where it belongs.
     */
    private fun outputKind(sourceMime: String?, format: PhotoFormat): String = when (format) {
        PhotoFormat.PNG  -> KIND_PNG
        PhotoFormat.WEBP -> KIND_WEBP
        PhotoFormat.JPEG -> KIND_JPEG
        PhotoFormat.SOURCE -> when (sourceMime) {
            "image/png"  -> KIND_PNG
            "image/webp" -> KIND_WEBP
            else         -> KIND_JPEG
        }
    }

    private fun resolveCompressFormat(sourceMime: String?, format: PhotoFormat): Bitmap.CompressFormat =
        when (outputKind(sourceMime, format)) {
            KIND_PNG -> Bitmap.CompressFormat.PNG
            KIND_WEBP -> if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY
                         else @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
            else -> Bitmap.CompressFormat.JPEG
        }

    private fun outputExtension(sourceMime: String?, format: PhotoFormat): String =
        when (outputKind(sourceMime, format)) {
            KIND_PNG -> "png"
            // WEBP_LOSSY (API 30+) and the deprecated WEBP (API 26-29) both
            // produce WebP bytes, so the temp file must carry .webp for
            // ExifInterface to recognise it (BUG-FMT-2).
            KIND_WEBP -> "webp"
            else -> "jpg"
        }

    private fun fmtIsLossy(sourceMime: String?, format: PhotoFormat): Boolean =
        outputKind(sourceMime, format) != KIND_PNG

    // ── EXIF ─────────────────────────────────────────────────────────────────

    private fun exifRotation(file: File): Int {
        val exif = runCatching { ExifInterface(file.absolutePath) }.getOrNull() ?: return 0
        return when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90  -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    }


    /** Strided ARGB sample of [bitmap] for the Smart ladder — a few thousand pixels are plenty. */
    private fun photoMetricsOf(bitmap: android.graphics.Bitmap): SmartPhotoAdvisor.Metrics {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return SmartPhotoAdvisor.Metrics(0f, 0, 0f)
        val target = 6_000
        // SAMPLE-FIX: the old stride `step = (w*h)/target` produced only
        // ~target²/(w·h) samples — for a 12 MP photo that was 2-4 corner
        // pixels, so the Smart ladder's detail/noise metrics were noise.
        // The correct stride (see SmartSample) samples ~target pixels across
        // the WHOLE bitmap regardless of resolution.
        val step = SmartSample.strideFor(w, h, target)
        val sample = ArrayList<Int>(target)
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                sample += bitmap.getPixel(x, y)
                x += step
            }
            y += step
        }
        return try {
            SmartPhotoAdvisor.metricsOf(sample.toIntArray())
        } catch (t: Throwable) {
            // Measurement must never break the encode; fall back to neutral.
            SmartPhotoAdvisor.Metrics(0.25f, 16, 0.2f)
        }
    }

    private fun detectMime(file: File): String? = runCatching {
        val bytes = ByteArray(12)
        file.inputStream().use { it.read(bytes) }
        when {
            bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "image/jpeg"
            bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() -> "image/png"
            bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() -> "image/webp"
            // HEIC/HEIF: ISO-BMFF "ftyp" box — size at 0..3, brand at 8..11.
            // Without this, a HEIC whose resolver mime came back null fell to
            // a generic decode error instead of the clear "old device" one.
            bytes.size >= 12 &&
                bytes[4].toInt() == 0 && bytes[5].toInt() == 0 &&
                bytes[6].toInt() == 0 &&
                (bytes[7].toInt() == 0x18 || bytes[7].toInt() == 0x20) -> {
                val brand = String(bytes, 8, 4, Charsets.US_ASCII)
                when (brand) {
                    "heic", "heix", "hevc", "hevx", "heif", "mif1", "msf1" -> "image/heic"
                    else -> null
                }
            }
            else -> null
        }
    }.getOrNull()

    private fun estimatedOutputBytes(
        sourceMime: String?,
        srcW: Int,
        srcH: Int,
        settings: PhotoSettings
    ): Long {
        return if (outputKind(sourceMime, settings.outputFormat) == KIND_PNG) {
            (srcW.toLong() * srcH * 3 * 0.6).toLong().coerceAtLeast(4_000)
        } else {
            (srcW.toLong() * srcH * settings.quality / 100.0 * 0.5).toLong().coerceAtLeast(2_000)
        }
    }

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
                } catch (_: Exception) { }
            }
            // COLOR-4 FIX: the source's colour-space tag (e.g. Display-P3 vs
            // sRGB) was silently dropped, so a wide-gamut photo came out with
            // sRGB metadata even though its pixels were still P3-tagged in the
            // decode. Copy it so the pairing of pixels + metadata stays true.
            // (Android's Bitmap.compress does not embed a full ICC profile in
            // JPEG, so the remaining gap — true ICC bytes — is an encoder
            // limitation; the metadata tag is now at least carried through.)
            runCatching {
                src.getAttribute(ExifInterface.TAG_COLOR_SPACE)?.takeIf { it.isNotBlank() && it != "0" }
                    ?.let { dst.setAttribute(ExifInterface.TAG_COLOR_SPACE, it) }
            }
            dst.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            dst.setAttribute(ExifInterface.TAG_IMAGE_WIDTH, width.toString())
            dst.setAttribute(ExifInterface.TAG_IMAGE_LENGTH, height.toString())
            dst.saveAttributes()
        }
    }

    // ExifInterface.TAG_ISO_SPEED_RATINGS is deprecated as a constant, but the
    // string tag it names ("ISOSpeedRatings") is the standard field cameras
    // write and has no non-deprecated constant to copy verbatim — we preserve
    // EXIF metadata here, so keep copying the string and silence the symbol.
    @Suppress("DEPRECATION")
    private val COPY_TAGS = listOf(
        ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_SOFTWARE,
        ExifInterface.TAG_ARTIST, ExifInterface.TAG_COPYRIGHT,
        ExifInterface.TAG_IMAGE_DESCRIPTION, ExifInterface.TAG_DATETIME,
        ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_F_NUMBER,
        ExifInterface.TAG_ISO_SPEED_RATINGS, ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
        ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_MAX_APERTURE_VALUE,
        ExifInterface.TAG_METERING_MODE, ExifInterface.TAG_WHITE_BALANCE,
        ExifInterface.TAG_EXPOSURE_MODE, ExifInterface.TAG_EXPOSURE_PROGRAM,
        ExifInterface.TAG_FLASH, ExifInterface.TAG_LIGHT_SOURCE,
        ExifInterface.TAG_CONTRAST, ExifInterface.TAG_SATURATION,
        ExifInterface.TAG_SHARPNESS, ExifInterface.TAG_SCENE_CAPTURE_TYPE,
        ExifInterface.TAG_DIGITAL_ZOOM_RATIO, ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
        ExifInterface.TAG_PIXEL_X_DIMENSION, ExifInterface.TAG_PIXEL_Y_DIMENSION,
        ExifInterface.TAG_LENS_MAKE, ExifInterface.TAG_LENS_MODEL,
        ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP
    )

    companion object {
        const val KEY_HEIC_UNSUPPORTED = "heic_old_device"
        const val KIND_PNG = "png"
        const val KIND_WEBP = "webp"
        const val KIND_JPEG = "jpg"
        const val KEY_DECODE = "decode_failed"
        const val KEY_ENCODE = "encode_failed"
    }
}

class PhotoCompressionException(val key: String) : Exception(key)

class ProgressStream(
    private val out: OutputStream,
    private val estimatedBytes: Long,
    private val control: JobControl,
    private val onProgress: (Float) -> Unit
) : OutputStream() {
    private var written = 0L
    override fun write(b: Int) { control.checkActiveIo(); out.write(b); written++; report() }
    override fun write(b: ByteArray, off: Int, len: Int) {
        control.checkActiveIo(); out.write(b, off, len); written += len; report()
    }
    override fun flush() = out.flush()
    override fun close() = out.close()
    private fun report() {
        if (estimatedBytes > 0) onProgress((written.toFloat() / estimatedBytes).coerceIn(0f, 0.99f))
    }
}

private fun JobControl.checkActiveIo() {
    if (isCancelled) throw com.compressly.core.engine.CompressionCancelledException()
}

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
