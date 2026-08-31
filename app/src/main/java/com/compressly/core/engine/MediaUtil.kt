package com.compressly.core.engine

import android.media.MediaExtractor
import android.media.MediaFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder

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
                // MONO-R: a mono source has no right channel. Duplicating L
                // into R is what every stereo encoder expects; leaving R=0
                // produced a silent right ear on headphones.
                if (srcCh == 1) r = l
                target.putShort(l.toShort())
                target.putShort(r.toShort())
            }
        }
    }

    /**
     * Like [convertPcmToEncoder], but accepts either 16-bit or float PCM
     * (the two encodings Android audio decoders actually emit).
     */
    fun convertDecoderPcm(
        source: ByteBuffer,
        sourceSize: Int,
        target: ByteBuffer,
        sourceChannels: Int,
        targetChannels: Int,
        pcmFloat: Boolean
    ) {
        source.order(ByteOrder.LITTLE_ENDIAN)
        if (!pcmFloat) {
            convertPcmToEncoder(source, sourceSize, target, sourceChannels, targetChannels)
            return
        }
        val srcCh = sourceChannels.coerceIn(1, 8)
        val samples = sourceSize / (srcCh * 4)
        if (samples <= 0) return
        val pcm16 = ByteBuffer.allocate(samples * srcCh * 2).order(ByteOrder.LITTLE_ENDIAN)
        repeat(samples * srcCh) {
            val v = source.float
            val s = (v * 32767.0f).toInt().coerceIn(-32768, 32767)
            pcm16.putShort(s.toShort())
        }
        pcm16.flip()
        convertPcmToEncoder(pcm16, pcm16.remaining(), target, sourceChannels, targetChannels)
    }

    /** Bytes of 16-bit encoder PCM produced from one decoder output buffer. */
    fun encoderPcmBytes(sourceSize: Int, sourceChannels: Int, targetChannels: Int, pcmFloat: Boolean): Int {
        val srcCh = sourceChannels.coerceIn(1, 8)
        val dstCh = targetChannels.coerceIn(1, 2)
        val bytesPerSrcFrame = srcCh * if (pcmFloat) 4 else 2
        if (bytesPerSrcFrame <= 0) return 0
        val frames = sourceSize / bytesPerSrcFrame
        return frames * dstCh * 2
    }

    /**
     * Reads KEY_PCM_ENCODING (16-bit by default when absent), safely on every
     * supported API level.
     *
     * API-FIX: `MediaFormat.getInteger(key, defaultValue)` overload only exists
     * from API 29, but minSdk is 26 — on Android 8.x decoding any audio track
     * (or a video's soundtrack) crashed with NoSuchMethodError right here.
     * KEY_PCM_ENCODING itself exists since API 24, so the single-argument
     * getInteger() inside runCatching is valid on all supported API levels;
     * a missing key throws and falls back to 16-bit PCM.
     */
    fun pcmEncodingOf(format: MediaFormat): Int = runCatching {
        format.getInteger(MediaFormat.KEY_PCM_ENCODING)
    }.getOrDefault(android.media.AudioFormat.ENCODING_PCM_16BIT)
}
