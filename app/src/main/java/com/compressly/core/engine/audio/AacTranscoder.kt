package com.compressly.core.engine.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import com.compressly.core.engine.JobControl
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodes any audio track (MP3/WAV/FLAC/OGG/M4A...) and re-encodes it to
 * AAC-LC in an M4A container, using hardware-accelerated MediaCodec codecs.
 * Fully offline. Used by both the video pipeline and the standalone audio
 * compressor.
 *
 * Key fix: PCM data is never dropped when the encoder has no free input
 * buffer — a pending buffer holds the PCM and retries on the next iteration.
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
            var lastPts = 0L
            // Use extractor duration for progress when trim is not set.
            val sourceDurationUs = if (trimEndUs > trimStartUs) {
                trimEndUs - trimStartUs
            } else {
                runCatching { inputFormat.getLong(MediaFormat.KEY_DURATION) }.getOrDefault(0L).coerceAtLeast(0L)
            }

            if (trimStartUs > 0) {
                extractor.seekTo(trimStartUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            }

            val decInfo = MediaCodec.BufferInfo()
            val encInfo = MediaCodec.BufferInfo()
            val pcmBuf = ByteBuffer.allocateDirect(64 * 1024).order(ByteOrder.LITTLE_ENDIAN)
            // Pending PCM buffer: holds PCM data when the encoder had no free
            // input buffer, so nothing is ever dropped. Uses a compact pattern
            // so partial consumption is safe — remaining bytes are moved to the
            // front of the buffer on each iteration.
            val pendingBuf = ByteBuffer.allocateDirect(64 * 1024).order(ByteOrder.LITTLE_ENDIAN)
            var pendingPcm = false
            var pendingPts = 0L

            while (!encEos) {
                control.checkActive()

                // Feed pending PCM to encoder first (from a previous iteration
                // where the encoder had no free input buffer). Loop until all
                // pending data is consumed or no encoder input buffer is free.
                while (pendingPcm) {
                    val inIndex = encoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex < 0) break // No free buffer; retry next iteration.
                    val encBuf = encoder.getInputBuffer(inIndex)!!
                    encBuf.clear()
                    val bytesBefore = pendingBuf.remaining()
                    putLimited(encBuf, pendingBuf)
                    val fed = bytesBefore - pendingBuf.remaining()
                    if (fed > 0) {
                        lastPts = pendingPts
                        encoder.queueInputBuffer(inIndex, 0, encBuf.position(), pendingPts, 0)
                        val durationUs = (fed * 1_000_000L) / (sampleRate * channels * 2)
                        pendingPts += durationUs
                    }
                    if (!pendingBuf.hasRemaining()) {
                        // All pending data was consumed.
                        pendingPcm = false
                    } else {
                        // Partial consumption: compact remaining data to the front.
                        pendingBuf.compact()
                        pendingBuf.flip()
                    }
                }

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
                            if (sourceDurationUs > 0 && pts >= 0) {
                                val effectivePts = if (trimStartUs > 0) pts - trimStartUs else pts
                                onProgress((effectivePts.toFloat() / sourceDurationUs).coerceIn(0f, 1f))
                            }
                        }
                    }
                }

                if (!pendingPcm) {
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
                            pcmBuf.flip()
                            
                            var pts = (decInfo.presentationTimeUs - trimStartUs).coerceAtLeast(0)
                            if (pts <= lastPts) pts = lastPts + 1_000
                            
                            // Immediately store in pendingBuf to feed it safely through the while(pendingPcm) logic
                            pendingBuf.clear()
                            pendingBuf.put(pcmBuf)
                            pendingBuf.flip()
                            pendingPcm = true
                            pendingPts = pts
                        }
                        
                        val isEos = (decInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0)
                        decoder.releaseOutputBuffer(decOut, false)
                        
                        if (isEos) {
                            decoderEosSeen = true
                            break
                        }

                        // Break the decode loop if we generated new PCM data. 
                        // The next iterations of while(!encEos) will consume it.
                        if (pendingPcm) {
                            break
                        }
                        
                        decOut = decoder.dequeueOutputBuffer(decInfo, 0)
                    }
                }

                // Queue encoder EOS ONLY after all pending PCM has been fed.
                // Otherwise the last audio frame is lost.
                if (decoderEosSeen && !encEosQueued && !pendingPcm) {
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

    private fun findTrack(extractor: MediaExtractor, prefix: String): Int? =
        com.compressly.core.engine.MediaUtil.findTrack(extractor, prefix)

    private fun putLimited(dst: ByteBuffer, src: ByteBuffer) =
        com.compressly.core.engine.MediaUtil.putLimited(dst, src)

    private fun convertPcmToEncoder(
        source: ByteBuffer,
        sourceSize: Int,
        target: ByteBuffer,
        sourceChannels: Int,
        targetChannels: Int
    ) {
        // Decoder output byte order is not guaranteed; PCM16 is little-endian.
        source.order(ByteOrder.LITTLE_ENDIAN)
        com.compressly.core.engine.MediaUtil.convertPcmToEncoder(source, sourceSize, target, sourceChannels, targetChannels)
    }
}
