package com.compressly.core.engine.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.compressly.core.engine.JobControl
import java.nio.ByteOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Samples a lightweight waveform (peak per bucket) from the first few seconds
 * of an audio file, fully offline. Used for the nice-to-have waveform preview.
 *
 * Key fixes from microscopic review:
 * - ByteBuffer position/limit is correctly set for each decoder output buffer
 * - extractor.advance() is not called after queuing EOS (no wasted seek)
 * - peaks.maxOrNull() used for API safety across all Android versions
 * - inner loops use for-loop instead of while to avoid stale position bugs
 */
object WaveformSampler {

    private const val MAX_PREVIEW_US = 4_000_000L // 4 seconds of analysis

    suspend fun samplePeaks(
        context: Context,
        uri: Uri,
        buckets: Int = 96,
        control: JobControl = JobControl()
    ): List<Float> = withContext(Dispatchers.Default) {
        val peaks = FloatArray(buckets)
        var sawAny = false
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            var track = -1
            for (i in 0 until extractor.trackCount) {
                if (extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    track = i
                    break
                }
            }
            if (track < 0) return@withContext emptyList()
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            decoder.configure(format, null, null, 0)
            decoder.start()

            var pcmFloat = false
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var eos = false

            while (!eos) {
                control.checkActive()
                if (!inputDone) {
                    val inIdx = decoder.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = decoder.getInputBuffer(inIdx)!!
                        val sz = extractor.readSampleData(buf, 0)
                        if (sz < 0) {
                            decoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else if (extractor.sampleTime > MAX_PREVIEW_US) {
                            // Enough data for a preview; signal EOS and stop feeding.
                            decoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                            // Do NOT call extractor.advance() after queuing EOS.
                        } else {
                            decoder.queueInputBuffer(inIdx, 0, sz, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                var outIdx = decoder.dequeueOutputBuffer(info, 0)
                while (outIdx >= 0 || outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        pcmFloat = decoder.outputFormat.getInteger(
                            MediaFormat.KEY_PCM_ENCODING,
                            android.media.AudioFormat.ENCODING_PCM_16BIT
                        ) == android.media.AudioFormat.ENCODING_PCM_FLOAT
                    } else if (info.size > 0) {
                        val buf = decoder.getOutputBuffer(outIdx)!!
                        // Set position/limit for this specific output buffer.
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        buf.order(ByteOrder.LITTLE_ENDIAN)
                        val pts = info.presentationTimeUs
                        if (pts <= MAX_PREVIEW_US) {
                            // WAV-3 FIX: use Float throughout — eliminates double→float
                            // conversions in the inner loop. For ~176 k samples over
                            // 4 s this saves ~700 k unnecessary float↔double casts.
                            val bucket = ((pts.toFloat() / MAX_PREVIEW_US) * buckets).toInt().coerceIn(0, buckets - 1)
                            var peak = 0f
                            if (pcmFloat) {
                                val n = info.size / 4
                                for (i in 0 until n) {
                                    val v = abs(buf.float)
                                    if (v > peak) peak = v
                                }
                            } else {
                                val n = info.size / 2
                                for (i in 0 until n) {
                                    val v = abs(buf.short.toFloat()) * (1f / 32768f)
                                    if (v > peak) peak = v
                                }
                            }
                            if (peak > peaks[bucket]) peaks[bucket] = peak
                            sawAny = true
                        }
                    }
                    if (outIdx >= 0) {
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) eos = true
                        decoder.releaseOutputBuffer(outIdx, false)
                    }
                    outIdx = decoder.dequeueOutputBuffer(info, 0)
                }
            }
        } finally {
            runCatching { extractor.release() }
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
        }
        if (!sawAny) return@withContext emptyList()
        // Normalize: scale to the loudest bucket; keep a soft perceptual curve.
        // Use maxOrNull() for API safety across all Android versions.
        val maxPeak = (peaks.maxOrNull() ?: 0f).coerceAtLeast(0.001f)
        peaks.map { p ->
            val n = (p / maxPeak).coerceIn(0.02f, 1f)
            sqrt(n)
        }
    }
}
