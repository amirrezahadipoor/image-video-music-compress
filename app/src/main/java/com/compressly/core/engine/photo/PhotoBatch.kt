package com.compressly.core.engine.photo

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri

/**
 * Scheduling policy for photo batches.
 *
 * The engine processes up to [MAX_PHOTOS_IN_FLIGHT] files at once (the product
 * target; the budget itself lives in `EngineGate` so that two jobs cannot each
 * take a full share). Five *ordinary* photos are safe: a 12 MP decode is a few
 * MB of pixels once sampled down.
 *
 * What is not safe is five *huge* decodes. PhotoCompressor clamps a single
 * decode at 4096 px (~67 MB ARGB_8888), and with its rotation copy one item
 * peaks at ~134 MB — five of those would be ~670 MB. A source that can hit
 * that clamp (or that cannot be probed at all) therefore asks for the WHOLE
 * budget and runs alone, and the 2 GB / 192 MB heap class drops ordinary
 * batches to two at a time.
 */
object PhotoBatch {

    /** How many ordinary photos run at once (== the global budget). */
    const val MAX_PHOTOS_IN_FLIGHT = com.compressly.core.service.EngineGate.MAX_CONCURRENT_ITEMS

    /** Slope taken by one heavy decode: the whole budget, i.e. run alone. */
    const val HEAVY_WEIGHT = com.compressly.core.service.EngineGate.MAX_CONCURRENT_ITEMS

    /** On a low-memory phone with a big batch: at most two at a time. */
    const val LOW_MEMORY_WEIGHT = 2

    /**
     * A source larger than ~16 MP will decode at the 4096 px clamp and should
     * never share a batch slot with another heavy decode.
     */
    const val MAX_PIXELS_FOR_PARALLEL = 16_000_000L

    /**
     * How much of the global budget ONE photo takes.
     *
     * `null` pixel counts (unprobeable source) are treated conservatively as
     * "too big" — correct is preferred over fast.
     */
    fun weightFor(pixelCount: Long?, lowMemoryLargeBatch: Boolean = false): Int = when {
        pixelCount == null || pixelCount >= MAX_PIXELS_FOR_PARALLEL -> HEAVY_WEIGHT
        lowMemoryLargeBatch -> LOW_MEMORY_WEIGHT
        else -> 1
    }

    /**
     * MEM-BOUND: on the 2 GB / 192 MB heap class still common on Android 8-9, a
     * big batch (>= [LARGE_BATCH_ITEMS] photos) also accumulates ImageLoader/Coil
     * caches and history writes on top of the bitmaps, so it runs two at a time.
     */
    fun isLowMemoryLargeBatch(memoryClassMb: Int?, batchSize: Int): Boolean =
        memoryClassMb != null && memoryClassMb <= LOW_MEMORY_CLASS_MB && batchSize >= LARGE_BATCH_ITEMS

    /** How many items a weight lets through at once (for docs and tests). */
    fun activeAtOnce(weight: Int): Int =
        (MAX_PHOTOS_IN_FLIGHT / weight.coerceAtLeast(1)).coerceAtLeast(1)

    /** Heap class (ActivityManager.getMemoryClass) at or below this value is "low memory". */
    const val LOW_MEMORY_CLASS_MB = 192

    /** Batches of this many photos or more are considered large. */
    const val LARGE_BATCH_ITEMS = 100

    /**
     * Heaviest first, stable: the long-running files land in the parallel slots
     * at the start instead of queueing behind small ones, so the batch drains
     * smoothly instead of stalling on a huge tail. Ties keep their original
     * order.
     */
    fun <T> heaviestFirst(items: List<T>, sizeOf: (T) -> Long): List<T> {
        if (items.size < 2) return items
        return items.withIndex()
            .sortedWith(
                compareByDescending<IndexedValue<T>> { sizeOf(it.value) }
                    .thenBy { it.index }
            )
            .map { it.value }
    }

    /**
     * Header-only pixel count (no pixel decode). Returns `null` when the
     * source cannot be probed. Never throws.
     */
    fun pixelCountOf(context: Context, uri: Uri): Long? = runCatching {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, opts)
        }
        val w = opts.outWidth
        val h = opts.outHeight
        if (w <= 0 || h <= 0) null else w.toLong() * h.toLong()
    }.getOrNull()
}
