package com.compressly.core.engine

import android.media.MediaExtractor
import android.media.MediaFormat
import java.nio.ByteBuffer

/**
 * Shared media codec utilities used by both the audio and video pipelines.
 * Eliminates code duplication of findTrack, putLimited, and convertPcmToEncoder.
 */
object MediaUtil {

    /**
     * Finds the first track index whose MIME type starts with [prefix]
     * (e.g. "audio/" or "video/"). Returns null if no such track exists.
     */
    fun findTrack(extractor: MediaExtractor, prefix: String): Int? {
        for (i in 0 until extractor.trackCount) {
            if (extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith(prefix) == true) {
                return i
            }
        }
        return null
    }

    /**
     * Copies at most the remaining capacity of [dst] from [src] (no overflow).
     * Used by both AacTranscoder and MediaCodecTranscoder to feed encoder
     * input buffers safely.
     */
    fun putLimited(dst: ByteBuffer, src: ByteBuffer) {
        val n = dst.remaining().coerceAtMost(src.remaining())
        val oldLimit = src.limit()
        src.limit(src.position() + n)
        dst.put(src)
        src.limit(oldLimit)
    }

    /**
     * Copies PCM from a decoder output buffer into [target] (16-bit signed,
     * little-endian), down-mixing to at most [targetChannels] channels.
     * Shared by the AAC encoder and the video transcoder's audio pass.
     */
    fun convertPcmToEncoder(
        source: ByteBuffer,
        sourceSize: Int,
        target: ByteBuffer,
        sourceChannels: Int,
        targetChannels: Int
    ) {
        // UTIL-2 FIX: clamp channel counts defensively. If the caller passes an
        // incorrect sourceChannels (e.g. from a buggy MediaMetadataRetriever key),
        // perSampleBytes would be wrong and source.getShort() would throw
        // BufferUnderflowException mid-loop. Guard both ends of the valid range.
        val srcCh = sourceChannels.coerceIn(1, 8)
        val dstCh = targetChannels.coerceIn(1, 2)
        val perSampleBytes = srcCh * 2
        val samples = sourceSize / perSampleBytes
        for (i in 0 until samples) {
            var l = 0
            var r = 0
            for (c in 0 until srcCh) {
                val v = source.getShort().toInt()
                when (c) {
                    0 -> l = v
                    1 -> r = v
                    2 -> { l = (l + v) / 2; r = (r + v) / 2 } // Center
                    3 -> { /* LFE — perceptually negligible at 80 Hz, omit */ }
                    4 -> l = (l + v) / 2                        // Rear Left
                    5 -> r = (r + v) / 2                        // Rear Right
                    else -> { l = (l + v) / 2; r = (r + v) / 2 }
                }
            }
            if (dstCh == 1) {
                target.putShort(((l + r) / 2).toShort())
            } else {
                target.putShort(l.toShort())
                target.putShort(r.toShort())
            }
        }
    }
}
