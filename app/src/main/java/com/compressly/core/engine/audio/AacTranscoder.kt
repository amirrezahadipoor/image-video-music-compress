package com.compressly.core.engine.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.util.Log
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
 *
 * ENGINE-ROBUST-FIX: this used to create one AAC encoder by type and configure
 * it once, with no fallback. A vendor encoder that rejects the requested
 * sample-rate / channel config threw out of configure(), and because the video
 * pipeline calls this to re-encode the soundtrack (the High and Maximum
 * tiers), the whole video job died as a cryptic "encode_failed". Now:
 *  - the encoder is picked from EVERY available AAC encoder, hardware/vendor
 *    first then software, and each is configured twice (full, then minimal);
 *  - if no encoder works, or the decoder cannot be created, the method returns
 *    false (never throws), so the caller can degrade to passthrough or drop
 *    the soundtrack instead of losing the video.
 */
object AacTranscoder {

    private const val MIME_AAC = "audio/mp4a-latm"
    private const val TIMEOUT_US = 10_000L
    private const val TAG = "AacTranscoder"

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
        // Hoisted outside try so finally block can read it safely.
        var muxerStarted = false
        try {
            extractor.setDataSource(context, inputUri, null)
            val audioIndex = findTrack(extractor, "audio/") ?: return false
            extractor.selectTrack(audioIndex)
            val inputFormat = extractor.getTrackFormat(audioIndex)
            val inputMime = inputFormat.getString(MediaFormat.KEY_MIME) ?: return false

            // Decoder: created/configured defensively. If this track's codec
            // cannot be decoded on this device (e.g. Opus/Vorbis with no
            // decoder) the job must not fail — the caller degrades gracefully.
            val srcChannels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            try {
                val d = MediaCodec.createDecoderByType(inputMime)
                try {
                    d.configure(inputFormat, null, null, 0)
                    d.start()
                    decoder = d
                } catch (t: Throwable) {
                    if (t is com.compressly.core.engine.CompressionCancelledException) throw t
                    runCatching { d.release() }
                    Log.w(TAG, "audio decoder configure failed for $inputMime @${sampleRate}Hz", t)
                    return false
                }
            } catch (t: Throwable) {
                if (t is com.compressly.core.engine.CompressionCancelledException) throw t
                Log.w(TAG, "no audio decoder for $inputMime", t)
                return false
            }

            val channels = if (srcChannels <= 2) srcChannels else 2
            // AAC-FLOAT: Android 8+ FLAC/WAV decoders often emit PCM_FLOAT.
            // convertPcmToEncoder reads 16-bit shorts, so float frames were
            // interpreted as garbage. Track the real encoding from the decoder.
            var pcmFloat = false

            val enc = createConfiguredAacEncoder(bitrate, sampleRate, channels)
            if (enc == null) {
                Log.w(TAG, "no working AAC encoder for ${sampleRate}Hz/$channels ch; caller will degrade")
                return false
            }
            encoder = enc

            muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var audioMuxerTrack = -1
            // muxerStarted is declared at the outer scope so finally can access it.
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
            // AAC-4 FIX: eliminated the intermediate pcmBuf — convertPcmToEncoder
            // now writes directly into pendingBuf, removing a full 64 KB memcpy
            // per decoded frame that was silent but measurable on long files.
            var pendingBuf = ByteBuffer.allocateDirect(64 * 1024).order(ByteOrder.LITTLE_ENDIAN)
            var pendingPcm = false
            var pendingPts = 0L
            // AAC-3 FIX: debounce progress callbacks — only emit when change ≥ 1 %
            // to avoid flooding the StateFlow with hundreds of identical values per
            // second on high-bitrate sources, which causes unnecessary recompositions.
            var lastReportedProgress = -1f

            while (!encEos) {
                control.checkActive()

                // Feed pending PCM to encoder first (from a previous iteration
                // where the encoder had no free input buffer). Loop until all
                // pending data is consumed or no encoder input buffer is free.
                while (pendingPcm) {
                    val inIndex = encoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex < 0) break // No free buffer; retry next iteration.
                    // NULL-GUARD-FIX: a null buffer on a valid index means the
                    // codec errored — degrade (false), never an NPE.
                    val encBuf = encoder.getInputBuffer(inIndex) ?: return false
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
                        val buf = decoder.getInputBuffer(inIndex) ?: return false
                        val sampleSize = extractor.readSampleData(buf, 0)
                        val pts = if (sampleSize >= 0) extractor.sampleTime else -1L
                        // Stop reading at the end of the trim window. Frames past
                        // it were already discarded on the output side, so the
                        // result was correct - but the whole file was still
                        // decoded and thrown away. Trimming one minute out of a
                        // two-hour recording decoded all two hours.
                        val pastTrimEnd = trimEndUs > 0 && pts > trimEndUs
                        if (sampleSize < 0 || pastTrimEnd) {
                            decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inIndex, 0, sampleSize, pts, 0)
                            extractor.advance()
                            // AAC-3 FIX: debounce — only emit progress when change ≥ 1%
                            if (sourceDurationUs > 0 && pts >= 0) {
                                val effectivePts = if (trimStartUs > 0) pts - trimStartUs else pts
                                val p = (effectivePts.toFloat() / sourceDurationUs).coerceIn(0f, 1f)
                                if (p - lastReportedProgress >= 0.01f) {
                                    onProgress(p)
                                    lastReportedProgress = p
                                }
                            }
                        }
                    }
                }

                if (!pendingPcm) {
                    var decOut = decoder.dequeueOutputBuffer(decInfo, 0)
                    while (decOut == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        pcmFloat = com.compressly.core.engine.MediaUtil.pcmEncodingOf(
                            decoder.outputFormat
                        ) == android.media.AudioFormat.ENCODING_PCM_FLOAT
                        decOut = decoder.dequeueOutputBuffer(decInfo, 0)
                    }
                    while (decOut >= 0) {
                        val framePts = decInfo.presentationTimeUs
                        val inTrimWindow = trimStartUs == 0L || framePts >= trimStartUs
                        if (decInfo.size > 0 &&
                            inTrimWindow &&
                            (trimEndUs <= 0 || framePts <= trimEndUs)
                        ) {
                            val pcm = decoder.getOutputBuffer(decOut) ?: return false
                            pcm.position(decInfo.offset)
                            pcm.limit(decInfo.offset + decInfo.size)

                            val outBytes = com.compressly.core.engine.MediaUtil.encoderPcmBytes(
                                decInfo.size, srcChannels, channels, pcmFloat
                            )
                            if (outBytes > pendingBuf.capacity()) {
                                pendingBuf = ByteBuffer.allocateDirect(outBytes)
                                    .order(ByteOrder.LITTLE_ENDIAN)
                            }
                            pendingBuf.clear()
                            convertDecoderPcm(
                                pcm, decInfo.size, pendingBuf, srcChannels, channels, pcmFloat
                            )
                            pendingBuf.flip()

                            var pts = (decInfo.presentationTimeUs - trimStartUs).coerceAtLeast(0)
                            if (pts <= lastPts) pts = lastPts + 1_000

                            pendingPcm = true
                            pendingPts = pts
                        }

                        val isEos = (decInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0)
                        decoder.releaseOutputBuffer(decOut, false)

                        if (isEos) {
                            decoderEosSeen = true
                            break
                        }
                        if (pendingPcm) break
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
                        val out = encoder.getOutputBuffer(encOut) ?: return false
                        out.position(encInfo.offset)
                        out.limit(encInfo.offset + encInfo.size)
                        muxer.writeSampleData(audioMuxerTrack, out, encInfo)
                    }
                    if (encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) encEos = true
                    encoder.releaseOutputBuffer(encOut, false)
                    encOut = encoder.dequeueOutputBuffer(encInfo, 0)
                }
            }

            // AUDIO-FIX: no sample ever reached the muxer (the encoder emitted
            // only CODEC_CONFIG then EOS). Success here would publish an empty
            // M4A (or silently strip the soundtrack of a video) — report
            // failure instead so the caller surfaces an encode error.
            if (!muxerStarted) return false
            return true
        } catch (t: Throwable) {
            // ENGINE-ROBUST-FIX: any failure in the re-encode pipeline must be
            // reported as "could not encode audio" (false) so the video job can
            // degrade gracefully, not as an exception that loses the video.
            // User cancellation still propagates — it is never a "failure".
            if (t is com.compressly.core.engine.CompressionCancelledException) throw t
            Log.e(TAG, "audio re-encode failed", t)
            return false
        } finally {
            runCatching { extractor.release() }
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            // Guard: muxer.stop() throws ISE if start() was never called.
            // This can happen if the encoder only output CODEC_CONFIG then EOS
            // with no real audio data (e.g. 0-byte input file or corrupt source).
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
        }
    }

    /**
     * Every AAC encoder this device advertises, hardware/vendor first then
     * software, so a vendor encoder that rejects the config never prevents the
     * search from reaching the software one.
     */
    private fun aacEncoderCandidates(): List<String> {
        val hardware = mutableListOf<String>()
        val software = mutableListOf<String>()
        runCatching {
            for (ci in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos) {
                if (!ci.isEncoder || !ci.supportedTypes.contains(MIME_AAC)) continue
                val name = ci.name.lowercase()
                val isSoft = name.startsWith("omx.google.") || name.startsWith("c2.android.")
                (if (isSoft) software else hardware).add(ci.name)
            }
        }
        return hardware + software
    }

    /**
     * Creates, configures and starts a working AAC-LC encoder, or null when
     * none accepts the config. Each candidate is tried twice — with the
     * optional profile/size keys, then a minimal format — mirroring the video
     * pipeline's two-attempt strategy, so one picky encoder cannot abort the
     * re-encode.
     */
    private fun createConfiguredAacEncoder(bitrate: Int, sampleRate: Int, channels: Int): MediaCodec? {
        for (name in aacEncoderCandidates()) {
            for (minimal in listOf(false, true)) {
                var enc: MediaCodec? = null
                try {
                    enc = MediaCodec.createByCodecName(name)
                    enc.configure(
                        aacFormat(bitrate, sampleRate, channels, minimal),
                        null, null,
                        MediaCodec.CONFIGURE_FLAG_ENCODE
                    )
                    enc.start()
                    return enc
                } catch (t: Throwable) {
                    if (t is com.compressly.core.engine.CompressionCancelledException) throw t
                    runCatching { enc?.release() }
                }
            }
        }
        return null
    }

    private fun aacFormat(bitrate: Int, sampleRate: Int, channels: Int, minimal: Boolean): MediaFormat =
        MediaFormat.createAudioFormat(MIME_AAC, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            if (!minimal) {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
            }
        }

    private fun findTrack(extractor: MediaExtractor, prefix: String): Int? =
        com.compressly.core.engine.MediaUtil.findTrack(extractor, prefix)

    private fun putLimited(dst: ByteBuffer, src: ByteBuffer) =
        com.compressly.core.engine.MediaUtil.putLimited(dst, src)

    private fun convertDecoderPcm(
        source: ByteBuffer,
        sourceSize: Int,
        target: ByteBuffer,
        sourceChannels: Int,
        targetChannels: Int,
        pcmFloat: Boolean
    ) {
        com.compressly.core.engine.MediaUtil.convertDecoderPcm(
            source, sourceSize, target, sourceChannels, targetChannels, pcmFloat
        )
    }
}
