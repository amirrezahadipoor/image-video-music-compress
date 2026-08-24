package com.compressly.core.engine.video

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import com.compressly.core.engine.JobControl
import com.compressly.core.engine.model.MediaInfo
import com.compressly.core.engine.model.VideoAudioMode
import com.compressly.core.engine.model.VideoCodec
import com.compressly.core.engine.model.VideoResolution
import com.compressly.core.engine.model.VideoSettings
import com.compressly.core.util.Storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer

/**
 * Fully offline, hardware-accelerated video transcoder built on
 * MediaCodec + MediaExtractor + MediaMuxer.
 *
 * Pipeline: extractor -> decoder -> (surface) -> encoder -> muxer, which is the
 * GPU-accelerated A-to-A path. If the chosen encoder cannot provide a surface
 * input, a software encoder is attempted before giving up with a clear error.
 */
class MediaCodecTranscoder(private val context: Context) {

    companion object {
        private const val MIME_H264 = "video/avc"
        private const val MIME_H265 = "video/hevc"
        private const val MIME_AAC = "audio/mp4a-latm"
        private const val TIMEOUT_US = 10_000L

        const val ERR_NO_VIDEO = "no_video"
        const val ERR_NO_ENCODER = "no_encoder"
        const val ERR_ENCODE = "encode_failed"
        const val ERR_DECODE = "decode_failed"
    }

    data class Stats(val outputSize: Long, val durationMs: Long)

    /**
     * Transcodes [inputUri] into [outputPath]. Honors trim, resolution,
     * bitrate, frame rate, codec and audio-mode settings.
     */
    suspend fun transcode(
        inputUri: Uri,
        outputPath: String,
        info: MediaInfo,
        settings: VideoSettings,
        preset: com.compressly.core.engine.model.CompressionPreset,
        control: JobControl,
        onProgress: (Float) -> Unit
    ): Stats = withContext(Dispatchers.Default) {
        val rotation = info.rotation
        val trimStartUs = if (settings.trimEnabled) settings.trimStartMs * 1000L else 0L
        val trimEndUs = if (settings.trimEnabled && settings.trimEndMs > settings.trimStartMs) {
            settings.trimEndMs * 1000L
        } else 0L

        // Resolve encoder (H.264/H.265 with hardware-first, software fallback).
        val requestedMime = if (settings.codec == VideoCodec.H265) MIME_H265 else MIME_H264
        val choice = resolveEncoder(requestedMime)

        val (outW, outH) = computeOutputDims(info, settings, preset)
        val targetBitrate = settings.bitrate
            ?: com.compressly.core.engine.estimate.SizeEstimator.targetVideoBitrate(info, settings, preset)
        val targetFps = settings.frameRate ?: 30

        val tempVideo = File(context.cacheDir, "tmp_${System.currentTimeMillis()}_video.mp4")
        var tempAudio: File? = null
        var tempAudioUri: Uri? = null
        try {
            // 1. Video pass -> temp file.
            videoPass(
                inputUri = inputUri,
                outputPath = tempVideo.absolutePath,
                info = info,
                settings = settings,
                encoderName = choice.name,
                encoderMime = choice.mime,
                outW = outW,
                outH = outH,
                targetBitrate = targetBitrate,
                targetFps = targetFps,
                rotation = rotation,
                trimStartUs = trimStartUs,
                trimEndUs = trimEndUs,
                control = control,
                onProgress = { p -> onProgress(p * 0.80f) }
            )

            val wantsAudio = settings.audioMode != VideoAudioMode.STRIP && info.hasAudio

            // 2. Audio: passthrough or transcode.
            var audioAlreadyTrimmed = false
            if (wantsAudio) {
                val passthroughAac = settings.audioMode == VideoAudioMode.KEEP && audioMimeIs(inputUri, MIME_AAC)
                if (passthroughAac) {
                    tempAudioUri = inputUri // copy samples directly during merge
                } else {
                    tempAudio = File(context.cacheDir, "tmp_${System.currentTimeMillis()}_audio.m4a")
                    val audioBitrate = when (settings.audioMode) {
                        VideoAudioMode.COMPRESS -> {
                            // Re-encode at ~55% of source, bounded to 96-192 kbps.
                            ((info.audioBitrate * 0.55).toInt()).coerceIn(96_000, 192_000)
                        }
                        else -> 128_000 // KEEP: passthrough failed, re-encode at a safe default.
                    }
                    audioTranscodePass(
                        inputUri = inputUri,
                        outputPath = tempAudio.absolutePath,
                        bitrate = audioBitrate,
                        trimStartUs = trimStartUs,
                        trimEndUs = trimEndUs,
                        control = control,
                        onProgress = { p -> onProgress(0.80f + p * 0.12f) }
                    )
                    tempAudioUri = Uri.fromFile(tempAudio)
                    audioAlreadyTrimmed = true
                }
            }

            // 3. Merge (or move straight to final when there is no audio).
            if (!wantsAudio) {
                val done = tempVideo.renameTo(File(outputPath))
                if (!done) {
                    tempVideo.copyTo(File(outputPath), overwrite = true)
                    Storage.deleteQuietly(tempVideo)
                }
            } else {
                mergePass(
                    videoPath = tempVideo.absolutePath,
                    audioUri = tempAudioUri,
                    outputPath = outputPath,
                    rotation = rotation,
                    trimStartUs = trimStartUs,
                    trimEndUs = trimEndUs,
                    audioAlreadyTrimmed = audioAlreadyTrimmed,
                    control = control,
                    onProgress = { p -> onProgress(0.92f + p * 0.08f) }
                )
            }
            control.checkActive()
            onProgress(1f)
            Stats(File(outputPath).length(), trimmedDurationMs(info.durationMs, trimStartUs, trimEndUs))
        } catch (t: Throwable) {
            Storage.deleteQuietly(tempVideo, tempAudio)
            throw t
        } finally {
            Storage.deleteQuietly(tempVideo, tempAudio)
        }
    }

    // ------------------------------------------------------------------
    // Video pass (surface pipeline)
    // ------------------------------------------------------------------

    private suspend fun videoPass(
        inputUri: Uri,
        outputPath: String,
        info: MediaInfo,
        settings: VideoSettings,
        encoderName: String,
        encoderMime: String,
        outW: Int,
        outH: Int,
        targetBitrate: Int,
        targetFps: Int,
        rotation: Int,
        trimStartUs: Long,
        trimEndUs: Long,
        control: JobControl,
        onProgress: (Float) -> Unit
    ) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, inputUri, null)
            val videoIndex = findTrack(extractor, "video/")
                ?: throw VideoCompressionException(ERR_NO_VIDEO)
            extractor.selectTrack(videoIndex)
            val inputFormat = extractor.getTrackFormat(videoIndex)
            val inputMime = inputFormat.getString(MediaFormat.KEY_MIME)
                ?: throw VideoCompressionException(ERR_DECODE)

            val encoder = MediaCodec.createByCodecName(encoderName)
            val encFormat = MediaFormat.createVideoFormat(encoderMime, outW, outH).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, targetBitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, targetFps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                )
                if (encoderSupportsBitrateMode(encoderMime, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)) {
                    setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                }
                // Speed: realtime priority + operating-rate hint keep hardware
                // encoders from over-allocating and speed up the transcode.
                if (android.os.Build.VERSION.SDK_INT >= 23) {
                    setInteger(MediaFormat.KEY_PRIORITY, 0)
                    setInteger(MediaFormat.KEY_OPERATING_RATE, targetFps)
                }
            }
            encoder.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val inputSurface = encoder.createInputSurface()
            encoder.start()

            // CRITICAL: with a Surface output the decoder applies the source
            // rotation metadata itself. If we kept it, the encoder would receive
            // already-rotated frames while we configure it with STORED-orientation
            // dimensions AND set the muxer rotation hint -> double rotation /
            // distorted portrait videos. Forcing rotation 0 makes decoder output,
            // encoder input size and muxer hint all consistent.
            val decoderFormat = MediaFormat(inputFormat).apply {
                setInteger(MediaFormat.KEY_ROTATION, 0)
            }
            val decoder = MediaCodec.createDecoderByType(inputMime)
            decoder.configure(decoderFormat, inputSurface, null, 0)
            decoder.start()

            var muxer: MediaMuxer? = null
            try {
                muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                muxer.setOrientationHint(rotation)
                var videoMuxerTrack = -1
                var muxerStarted = false
                var encEos = false
                var inputDone = false
                var decoderEosSignalled = false

                if (trimStartUs > 0) {
                extractor.seekTo(trimStartUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                }

                val decoderInfo = MediaCodec.BufferInfo()
                val encoderInfo = MediaCodec.BufferInfo()
                val lastKeptPts = longArrayOf(Long.MIN_VALUE)
                val keepAllFrames = settings.frameRate == null
                val frameIntervalUs = if (keepAllFrames) 0L else (1_000_000.0 / targetFps).toLong()
                var lastReported = -1f

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
                            // Report progress: use trim window when trimming,
                            // otherwise use the full source duration.
                            val progressTotal = if (trimEndUs > trimStartUs) {
                                trimEndUs - trimStartUs
                            } else {
                                runCatching { inputFormat.getLong(MediaFormat.KEY_DURATION) }.getOrDefault(0L).coerceAtLeast(0L)
                            }
                            if (progressTotal > 0) {
                                val effectivePts = if (trimStartUs > 0) pts - trimStartUs else pts
                                val p = (effectivePts.toFloat() / progressTotal).coerceIn(0f, 1f)
                                if (p - lastReported >= 0.005f) {
                                    onProgress(p)
                                    lastReported = p
                                }
                            }
                        }
                    }
                }

                // Drain decoder (renders into the encoder's input surface).
                var decoderOut = decoder.dequeueOutputBuffer(decoderInfo, 0)
                while (decoderOut >= 0) {
                    val render = if (keepAllFrames) {
                        true
                    } else {
                        val pts = decoderInfo.presentationTimeUs
                        if (lastKeptPts[0] == Long.MIN_VALUE || pts - lastKeptPts[0] >= frameIntervalUs) {
                            lastKeptPts[0] = pts
                            true
                        } else {
                            false
                        }
                    }
                    decoder.releaseOutputBuffer(decoderOut, render)
                    if (decoderInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 && !decoderEosSignalled) {
                        encoder.signalEndOfInputStream()
                        decoderEosSignalled = true
                    }
                    decoderOut = decoder.dequeueOutputBuffer(decoderInfo, 0)
                }

                // Drain encoder -> muxer.
                var encoderOut = encoder.dequeueOutputBuffer(encoderInfo, 0)
                while (encoderOut >= 0) {
                    if (encoderInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        encoderInfo.size = 0
                    }
                    if (encoderInfo.size > 0) {
                        val encoded = encoder.getOutputBuffer(encoderOut)!!
                        if (!muxerStarted) {
                            videoMuxerTrack = muxer.addTrack(encoder.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                        if (encoderInfo.presentationTimeUs >= trimStartUs) {
                            // Frames decoded from the sync sample before the trim
                            // point are dropped; the rest are written with a
                            // zero-based PTS.
                            encoded.position(encoderInfo.offset)
                            encoded.limit(encoderInfo.offset + encoderInfo.size)
                            val adjusted = MediaCodec.BufferInfo()
                            adjusted.set(
                                0,
                                encoderInfo.size,
                                (encoderInfo.presentationTimeUs - trimStartUs).coerceAtLeast(0),
                                encoderInfo.flags
                            )
                            muxer.writeSampleData(videoMuxerTrack, encoded, adjusted)
                        }
                    }
                    if (encoderInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        encEos = true
                    }
                    encoder.releaseOutputBuffer(encoderOut, false)
                    encoderOut = encoder.dequeueOutputBuffer(encoderInfo, 0)
                }
                }
            } finally {
                runCatching { inputSurface.release() }
                runCatching { decoder.stop() }
                runCatching { decoder.release() }
                runCatching { encoder.stop() }
                runCatching { encoder.release() }
                runCatching { muxer?.stop() }
                runCatching { muxer?.release() }
            }
        } finally {
            extractor.release()
        }
    }

    // ------------------------------------------------------------------
    // Audio pass (decode -> AAC encode -> m4a)
    // ------------------------------------------------------------------

    private suspend fun audioTranscodePass(
        inputUri: Uri,
        outputPath: String,
        bitrate: Int,
        trimStartUs: Long,
        trimEndUs: Long,
        control: JobControl,
        onProgress: (Float) -> Unit
    ) {
        // Shared, battle-tested decode -> AAC -> M4A pipeline (see AacTranscoder).
        com.compressly.core.engine.audio.AacTranscoder.transcode(
            context = context,
            inputUri = inputUri,
            outputPath = outputPath,
            bitrate = bitrate,
            trimStartUs = trimStartUs,
            trimEndUs = trimEndUs,
            control = control,
            onProgress = onProgress
        )
    }

    // ------------------------------------------------------------------
    // Merge pass (video temp + audio temp/original -> final)
    // ------------------------------------------------------------------

private suspend fun mergePass(
        videoPath: String,
        audioUri: Uri?,
        outputPath: String,
        rotation: Int,
        trimStartUs: Long,
        trimEndUs: Long,
        audioAlreadyTrimmed: Boolean,
        control: JobControl,
        onProgress: (Float) -> Unit
    ) {
        val muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        muxer.setOrientationHint(rotation)

        val videoExtractor = MediaExtractor()
        videoExtractor.setDataSource(videoPath)
        val videoIndex = findTrack(videoExtractor, "video/")
            ?: throw VideoCompressionException(ERR_NO_VIDEO)
        videoExtractor.selectTrack(videoIndex)
        val videoMuxerTrack = muxer.addTrack(videoExtractor.getTrackFormat(videoIndex))

        var audioExtractor: MediaExtractor? = null
        var audioMuxerTrack = -1
        if (audioUri != null) {
            val ae = MediaExtractor()
            ae.setDataSource(context, audioUri, null)
            val audioIndex = findTrack(ae, "audio/")
            if (audioIndex != null) {
                ae.selectTrack(audioIndex)
                audioMuxerTrack = muxer.addTrack(ae.getTrackFormat(audioIndex))
                audioExtractor = ae
            } else {
                ae.release()
            }
        }

        muxer.start()

        val videoBuf = ByteBuffer.allocateDirect(1 shl 20)
        val audioBuf = ByteBuffer.allocateDirect(1 shl 20)
        val videoInfo = MediaCodec.BufferInfo()
        val audioInfo = MediaCodec.BufferInfo()
        var videoHasSample = false
        var audioHasSample = false
        var videoDone = false
        var audioDone = audioExtractor == null
        var lastPts = 0L
        // Passthrough audio comes from the ORIGINAL file (needs trimming to the
        // trim window); transcoded audio is already trimmed (PTS start at 0).
        val audioOffset = if (audioAlreadyTrimmed) 0L else trimStartUs
        val audioEnd = if (audioAlreadyTrimmed) 0L else trimEndUs

        fun readVideo(): Boolean {
            if (videoDone) return false
            val sz = videoExtractor.readSampleData(videoBuf, 0)
            if (sz < 0) { videoDone = true; return false }
            videoInfo.set(0, sz, videoExtractor.sampleTime, videoExtractor.sampleFlags)
            videoExtractor.advance()
            return true
        }

        fun readAudio(): Boolean {
            val ae = audioExtractor ?: return false
            if (audioDone) return false
            while (true) {
                val sz = ae.readSampleData(audioBuf, 0)
                if (sz < 0) { audioDone = true; return false }
                val pts = ae.sampleTime
                ae.advance()
                if (pts < audioOffset) continue
                if (audioEnd > 0 && pts > audioEnd) { audioDone = true; return false }
                audioInfo.set(0, sz, (pts - audioOffset).coerceAtLeast(0), ae.sampleFlags)
                return true
            }
        }

        try {
            // The video temp file is already trimmed (PTS start at 0); the
            // passthrough audio is trimmed inside readAudio().
            videoHasSample = readVideo()
            if (!audioDone) audioHasSample = readAudio()

            while (!videoDone || !audioDone) {
                control.checkActive()

                // Only write when a valid sample is available; this prevents
                // writing stale/repeated data when one stream has finished.
                val writeVideo: Boolean = when {
                    videoDone -> false
                    audioDone -> videoHasSample
                    !videoHasSample && !audioHasSample -> false
                    !videoHasSample -> false
                    !audioHasSample -> true
                    else -> videoInfo.presentationTimeUs <= audioInfo.presentationTimeUs
                }

                if (writeVideo && videoHasSample) {
                    muxer.writeSampleData(videoMuxerTrack, videoBuf, videoInfo)
                    videoHasSample = readVideo()
                } else if (!writeVideo && audioHasSample) {
                    muxer.writeSampleData(audioMuxerTrack, audioBuf, audioInfo)
                    audioHasSample = readAudio()
                } else {
                    // Neither stream has a ready sample — read the next one.
                    if (!videoDone) videoHasSample = readVideo()
                    if (!audioDone) audioHasSample = readAudio()
                }

                // Report progress. When trim is not enabled, use the video
                // duration from the extractor as the denominator.
                if (videoInfo.presentationTimeUs > lastPts) {
                    val totalUs = if (trimEndUs > trimStartUs) trimEndUs - trimStartUs
                    else runCatching { videoExtractor.getTrackFormat(videoIndex).getLong(MediaFormat.KEY_DURATION) }.getOrDefault(0L)
                    if (totalUs > 0) {
                        val p = (videoInfo.presentationTimeUs.toFloat() / totalUs).coerceIn(0f, 1f)
                        onProgress(p)
                    }
                    lastPts = videoInfo.presentationTimeUs
                }
            }
        } finally {
            runCatching { videoExtractor.release() }
            runCatching { audioExtractor?.release() }
            runCatching { muxer.stop() }
            runCatching { muxer.release() }
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun findTrack(extractor: MediaExtractor, prefix: String): Int? =
        com.compressly.core.engine.MediaUtil.findTrack(extractor, prefix)

    private fun audioMimeIs(uri: Uri, mime: String): Boolean {
        return runCatching {
            val e = MediaExtractor()
            try {
                e.setDataSource(context, uri, null)
                for (i in 0 until e.trackCount) {
                    if (e.getTrackFormat(i).getString(MediaFormat.KEY_MIME) == mime) return true
                }
                false
            } finally {
                e.release()
            }
        }.getOrDefault(false)
    }

    data class EncoderChoice(val name: String, val mime: String)

    /** Picks the best encoder for [requestedMime], hardware first, software fallback. */
    private fun resolveEncoder(requestedMime: String): EncoderChoice {
        val hw = findEncoder(requestedMime, hardwareOnly = true)
        if (hw != null) return EncoderChoice(hw, requestedMime)
        val sw = findEncoder(requestedMime, hardwareOnly = false)
        if (sw != null) return EncoderChoice(sw, requestedMime)
        if (requestedMime == MIME_H265) {
            val hw264 = findEncoder(MIME_H264, hardwareOnly = true)
            if (hw264 != null) return EncoderChoice(hw264, MIME_H264)
            val sw264 = findEncoder(MIME_H264, hardwareOnly = false)
            if (sw264 != null) return EncoderChoice(sw264, MIME_H264)
        }
        throw VideoCompressionException(ERR_NO_ENCODER)
    }

    private fun findEncoder(mime: String, hardwareOnly: Boolean): String? {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        for (ci in list.codecInfos) {
            if (!ci.isEncoder) continue
            if (!ci.supportedTypes.contains(mime)) continue
            val name = ci.name.lowercase()
            // Google software codecs; everything else is a vendor/hardware codec.
            val isSoftware = name.startsWith("omx.google.") ||
                name.startsWith("c2.android.") ||
                name.startsWith("omx.ittiam.")
            if (hardwareOnly && !isSoftware) return ci.name
            if (!hardwareOnly && isSoftware) return ci.name
        }
        return null
    }

    private fun encoderSupportsBitrateMode(mime: String, mode: Int): Boolean {
        return runCatching {
            val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            for (ci in list.codecInfos) {
                if (!ci.isEncoder || !ci.supportedTypes.contains(mime)) continue
                val caps = ci.getCapabilitiesForType(mime).encoderCapabilities ?: continue
                if (caps.isBitrateModeSupported(mode)) return true
            }
            false
        }.getOrDefault(false)
    }

    /**
     * Computes output dimensions in the STORED orientation (rotation applied
     * via muxer hint). Smart mode caps very large footage at 1920px wide:
     * perceptually that keeps >70% quality while saving a lot of space.
     */
    private fun computeOutputDims(
        info: MediaInfo,
        settings: VideoSettings,
        preset: com.compressly.core.engine.model.CompressionPreset
    ): Pair<Int, Int> {
        val storedW = info.width.takeIf { it > 0 } ?: return 1280 to 720
        val storedH = info.height.takeIf { it > 0 } ?: return 1280 to 720
        val rotated = info.rotation == 90 || info.rotation == 270
        val displayW = if (rotated) storedH else storedW
        val displayH = if (rotated) storedW else storedH

        var (capW, capH) = when (settings.resolution) {
            VideoResolution.ORIGINAL -> displayW to displayH
            VideoResolution.R1080 -> 1920 to 1080
            VideoResolution.R720 -> 1280 to 720
            VideoResolution.R480 -> 854 to 480
            VideoResolution.CUSTOM ->
                settings.customWidth.coerceAtLeast(64) to settings.customHeight.coerceAtLeast(64)
        }
        if (preset == com.compressly.core.engine.model.CompressionPreset.SMART && displayW > 1920) {
            val ratio = 1920.0 / displayW
            capW = 1920
            capH = (displayH * ratio).toInt().coerceAtLeast(2)
        }
        val scale = minOf(1.0, capW.toDouble() / displayW, capH.toDouble() / displayH)
        var w = (storedW * scale).toInt()
        var h = (storedH * scale).toInt()
        // Encoders want even dimensions.
        if (w % 2 != 0) w -= 1
        if (h % 2 != 0) h -= 1
        return w.coerceAtLeast(2) to h.coerceAtLeast(2)
    }

    private fun trimmedDurationMs(originalMs: Long, startUs: Long, endUs: Long): Long {
        if (originalMs <= 0) return 0L
        val start = startUs / 1000
        val end = if (endUs > 0) endUs / 1000 else originalMs
        return (end - start).coerceAtLeast(0)
    }

    /** Copies at most the remaining capacity of [dst] from [src] (no overflow). */
    private fun putLimited(dst: ByteBuffer, src: ByteBuffer) =
        com.compressly.core.engine.MediaUtil.putLimited(dst, src)

    /**
     * Copies PCM from a decoder output buffer into [target] (16-bit signed,
     * little-endian), down-mixing to at most [targetChannels] channels.
     */
    /** Delegates to the shared implementation in MediaUtil (DRY). */
    private fun convertPcmToEncoder(
        source: ByteBuffer,
        sourceSize: Int,
        target: ByteBuffer,
        sourceChannels: Int,
        targetChannels: Int
    ) {
        com.compressly.core.engine.MediaUtil.convertPcmToEncoder(source, sourceSize, target, sourceChannels, targetChannels)
    }
}

/** Expected video-engine failure carrying a stable message key. */
class VideoCompressionException(val key: String) : Exception(key)
