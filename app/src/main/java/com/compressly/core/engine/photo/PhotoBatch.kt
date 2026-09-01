package com.compressly.core.engine.photo

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri

/**
 * Scheduling policy for parallel photo batches.
 *
 * The parallel engine runs up to [MAX_PHOTOS_IN_FLIGHT] photos at once, but
 * two huge decodes must never be in flight together: PhotoCompressor clamps
 * a single decode at 4096 px (~67 MB ARGB_8888), and with its rotation copy
 * one item peaks at ~134 MB — two of them at ~270 MB, which can exceed the
 * heap of the 2 GB / 256 MB class still common on Android 8-9. Sources that
 * are big enough to hit that clamp (or that cannot be probed) therefore run
 * one at a time; ordinary 12 MP photos keep the requested 2-way parallelism.
 */
object PhotoBatch {

    /** Ordinary photos (e.g. 12 MP) stay fully parallel. */
    const val MAX_PHOTOS_IN_FLIGHT = 2

    /**
     * A source larger than ~16 MP will decode at the 4096 px clamp and should
     * never share a batch slot with another heavy decode.
     */
    const val MAX_PIXELS_FOR_PARALLEL = 16_000_000L

    /**
     * Slot count for a photo batch. `null` counts (unprobeable source) are
     * treated conservatively as "too big" — correct is preferred over fast.
     */
    fun concurrencyFor(pixelCounts: List<Long?>): Int =
        if (pixelCounts.any { it == null || it >= MAX_PIXELS_FOR_PARALLEL }) 1
        else MAX_PHOTOS_IN_FLIGHT

    /**
     * Heaviest first, stable: the two long-running photos land in the
     * parallel slots at the start instead of queueing behind small ones, so
     * the batch drains smoothly instead of stalling on a huge tail. Ties keep
     * their original order.
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
