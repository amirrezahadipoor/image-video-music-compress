package com.compressly.core.engine.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import com.compressly.core.engine.CompressionCancelledException
import com.compressly.core.engine.JobControl
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodes any audio track (MP3/WAV/FLAC/OGG/M4A...) and re-encodes it to
 * AAC-LC in an M4A container, using hardware-accelerated MediaCodec codecs.
 * Fully offline. Used by both the video pipeline and the standalone audio
 * compressor.
 */
object AacTranscoder {

    private const val MIME_AAC = "audio/mp4a-latm"
    private const val TIMEOUT_US = 10_000L

    /**
     * @return false when the source has no audio track; otherwise transcodes
     *         into [outputPath] and returns true.
     */
    suspend fun transcode(
        context: Context,
        inputUri: Uri,
        outputPath: String,
        bitrate: Int,
        trimStartUs: Long,
        trimEndUs: Long,
        control: JobControl,
        onProgress: (Float) -> Unit
    ): Boolean {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        try {
            extractor.setDataSource(context, inputUri, null)
            val audioIndex = findTrack(extractor, "audio/") ?: return false
            extractor.selectTrack(audioIndex)
            val inputFormat = extractor.getTrackFormat(audioIndex)
            val inputMime = inputFormat.getString(MediaFormat.KEY_MIME) ?: return false

            decoder = MediaCodec.createDecoderByType(inputMime)
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()

            val srcChannels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = if (srcChannels <= 2) srcChannels else 2

            encoder = MediaCodec.createEncoderByType(MIME_AAC)
            val encFormat = MediaFormat.createAudioFormat(MIME_AAC, sampleRate, channels).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
            }
            encoder.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var audioMuxerTrack = -1
            var muxerStarted = false
            var encEos = false
            var encEosQueued = false
            var decoderEosSeen = false
            var inputDone = false
            var lastPts = -1L
            val durationUs = trimEndUs - trimStartUs

            if (trimStartUs > 0) {
                extractor.seekTo(trimStartUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            }

            val decInfo = MediaCodec.BufferInfo()
            val encInfo = MediaCodec.BufferInfo()
            val pcmBuf = ByteBuffer.allocateDirect(64 * 1024).order(ByteOrder.LITTLE_ENDIAN)

            while (!encEos) {
                control.checkActive()

                if (!inputDone) {
                    val inIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val buf = decoder.getInputBuffer(inIndex)!!
                        val sampleSize = extractor.readSampleData(buf, 0)
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val pts = extractor.sampleTime
                            decoder.queueInputBuffer(inIndex, 0, sampleSize, pts, 0)
                            extractor.advance()
                            if (durationUs > 0 && pts >= 0) {
                                onProgress(((pts - trimStartUs).toFloat() / durationUs).coerceIn(0f, 1f))
                            }
                        }
                    }
                }

                var decOut = decoder.dequeueOutputBuffer(decInfo, 0)
                while (decOut >= 0) {
                    if (decInfo.size > 0 &&
                        decInfo.presentationTimeUs >= trimStartUs - 20_000 &&
                        (trimEndUs <= 0 || decInfo.presentationTimeUs <= trimEndUs)
                    ) {
                        val pcm = decoder.getOutputBuffer(decOut)!!
                        pcm.position(decInfo.offset)
                        pcm.limit(decInfo.offset + decInfo.size)
                        pcmBuf.clear()
                        convertPcmToEncoder(pcm, decInfo.size, pcmBuf, srcChannels, channels)
                        val inIndex = encoder.dequeueInputBuffer(TIMEOUT_US)
                        if (inIndex >= 0) {
                            val encBuf = encoder.getInputBuffer(inIndex)!!
                            encBuf.clear()
                            pcmBuf.flip()
                            putLimited(encBuf, pcmBuf)
                            var pts = (decInfo.presentationTimeUs - trimStartUs).coerceAtLeast(0)
                            if (pts <= lastPts) pts = lastPts + 1_000
                            lastPts = pts
                            encoder.queueInputBuffer(inIndex, 0, encBuf.position(), pts, 0)
                        }
                    }
                    if (decInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        decoderEosSeen = true
                    }
                    decoder.releaseOutputBuffer(decOut, false)
                    decOut = decoder.dequeueOutputBuffer(decInfo, 0)
                }

                if (decoderEosSeen && !encEosQueued) {
                    val inIndex = encoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        encoder.queueInputBuffer(
                            inIndex, 0, 0,
                            (lastPts + 1_000).coerceAtLeast(0),
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                        encEosQueued = true
                    }
                }

                var encOut = encoder.dequeueOutputBuffer(encInfo, 0)
                while (encOut >= 0) {
                    if (encInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) encInfo.size = 0
                    if (encInfo.size > 0) {
                        if (!muxerStarted) {
                            audioMuxerTrack = muxer.addTrack(encoder.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                        val out = encoder.getOutputBuffer(encOut)!!
                        out.position(encInfo.offset)
                        out.limit(encInfo.offset + encInfo.size)
                        muxer.writeSampleData(audioMuxerTrack, out, encInfo)
                    }
                    if (encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) encEos = true
                    encoder.releaseOutputBuffer(encOut, false)
                    encOut = encoder.dequeueOutputBuffer(encInfo, 0)
                }
            }

            if (muxerStarted) muxer.stop()
            muxer.release()
            decoder.stop()
            decoder.release()
            encoder.stop()
            encoder.release()
            return true
        } finally {
            runCatching { extractor.release() }
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
        }
    }

    private fun findTrack(extractor: MediaExtractor, prefix: String): Int? {
        for (i in 0 until extractor.trackCount) {
            if (extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith(prefix) == true) return i
        }
        return null
    }

    /** Copies at most the remaining capacity of [dst] from [src] (no overflow). */
    private fun putLimited(dst: ByteBuffer, src: ByteBuffer) {
        val n = dst.remaining().coerceAtMost(src.remaining())
        val oldLimit = src.limit()
        src.limit(src.position() + n)
        dst.put(src)
        src.limit(oldLimit)
    }

    /** 16-bit little-endian PCM into [target], down-mixing to at most [targetChannels]. */
    private fun convertPcmToEncoder(
        source: ByteBuffer,
        sourceSize: Int,
        target: ByteBuffer,
        sourceChannels: Int,
        targetChannels: Int
    ) {
        val ch = if (sourceChannels <= 2) sourceChannels else 2
        val perSampleBytes = ch * 2
        val samples = sourceSize / perSampleBytes
        for (i in 0 until samples) {
            var l = 0
            var r = 0
            for (c in 0 until ch) {
                val v = source.getShort()
                if (c == 0) l = v.toInt()
                else if (c == 1) r = v.toInt()
                else {
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
