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
        val perSampleBytes = sourceChannels * 2
        val samples = sourceSize / perSampleBytes
        for (i in 0 until samples) {
            var l = 0
            var r = 0
            for (c in 0 until sourceChannels) {
                val v = source.getShort()
                if (c == 0) l = v.toInt()
                else if (c == 1) r = v.toInt()
                else if (c == 2) { // Center
                    l = (l + v) / 2
                    r = (r + v) / 2
                } else if (c == 3) { // LFE (ignore or add slightly)
                    // omit or just do nothing
                } else if (c == 4) { // Rear Left
                    l = (l + v) / 2
                } else if (c == 5) { // Rear Right
                    r = (r + v) / 2
                } else {
                    l = (l + v) / 2
                    r = (r + v) / 2
                }
            }
            if (targetChannels == 1) {
                target.putShort(((l + r) / 2).toShort())
            } else {
                target.putShort(l.toShort())
                target.putShort(r.toShort())
            }
        }
    }
}
