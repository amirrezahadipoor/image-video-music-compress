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

        // MediaInspector normally supplies everything, but it can fail on exotic
        // or partly corrupt containers, and Compressor then falls back to an
        // empty MediaInfo. Planning from that would silently crush a 4K clip to
        // 1280x720, drop its audio track and tell the encoder 30 fps - so fill in
        // whatever is missing from the track itself before deciding anything.
        val plannedInfo = recoverInfo(inputUri, info)
        // One planner decides the dimensions, the rate and the frame rate, so
        // the encoder is configured with exactly the numbers the live estimate
        // in the UI was computed from.
        val plan = VideoPlanner.plan(plannedInfo, settings, preset)
        val outW = plan.width
        val outH = plan.height
        val targetBitrate = plan.bitrate
        val targetFps = plan.fps

        // VID-TEMP-1 FIX: use nanoTime for uniqueness. currentTimeMillis() has
        // millisecond resolution; two concurrent jobs that both start within the
        // same millisecond would produce identical filenames and corrupt each other's
        // temp files. nanoTime is monotonic and provides nanosecond uniqueness.
        val tempVideo = File(context.cacheDir, "tmp_${System.nanoTime()}_video.mp4")
        var tempAudio: File? = null
        var tempAudioUri: Uri? = null
        try {
            // 1. Video pass -> temp file.
            //    The span is 0..0.50 rather than 0..0.80 because a corrective
            //    second pass may follow; when it does not, progress jumps to
            //    0.80 below. The bar never moves backwards either way.
            videoPass(
                inputUri = inputUri,
                outputPath = tempVideo.absolutePath,
                info = plannedInfo,
                settings = settings,
                encoderName = choice.name,
                encoderMime = choice.mime,
                plan = plan,
                rotation = rotation,
                trimStartUs = trimStartUs,
                trimEndUs = trimEndUs,
                control = control,
                onProgress = { p -> onProgress(p * 0.50f) }
            )

            // 1b. Corrective pass. Hardware encoders treat KEY_BIT_RATE as a
            //     hint in VBR mode and routinely overshoot it, so the planner's
            //     target is not the rate the file was actually written at.
            //     Measure what we got and, if it is meaningfully over, encode
            //     once more at a proportionally corrected rate. This is what
            //     makes "compress harder" actually produce a smaller file.
            val videoDurationMs = trimmedDurationMs(plannedInfo.durationMs, trimStartUs, trimEndUs)
            val correction = VideoPlanner.correctedBitrate(plan.bitrate, tempVideo.length(), videoDurationMs)
            if (correction != null) {
                control.checkActive()
                Storage.deleteQuietly(tempVideo)
                videoPass(
                    inputUri = inputUri,
                    outputPath = tempVideo.absolutePath,
                    info = plannedInfo,
                    settings = settings,
                    encoderName = choice.name,
                    encoderMime = choice.mime,
                    // The key-frame interval has to follow the corrected rate:
                    // the GOP that fitted the first pass is too short for the
                    // lower one, and short GOPs are exactly what eats the budget.
                    plan = plan.copy(
                        bitrate = correction,
                        iFrameInterval = VideoPlanner.iFrameIntervalSeconds(correction, plan.width, plan.height)
                    ),
                    rotation = rotation,
                    trimStartUs = trimStartUs,
                    trimEndUs = trimEndUs,
                    control = control,
                    onProgress = { p -> onProgress(0.50f + p * 0.30f) }
                )
            } else {
                onProgress(0.80f)
            }

            val wantsAudio = settings.audioMode != VideoAudioMode.STRIP && info.hasAudio

            // 2. Audio: passthrough or transcode.
            var audioAlreadyTrimmed = false
            if (wantsAudio) {
                val passthroughAac = settings.audioMode == VideoAudioMode.KEEP && audioMimeIs(inputUri, MIME_AAC)
                if (passthroughAac) {
                    tempAudioUri = inputUri // copy samples directly during merge
                } else {
                    tempAudio = File(context.cacheDir, "tmp_${System.nanoTime()}_audio.m4a")
                    // Shared with the estimate, and capped at the source rate.
                    val audioBitrate = VideoPlanner.audioBitrateBps(plannedInfo, settings, preset)
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
        plan: VideoPlanner.Plan,
        rotation: Int,
        trimStartUs: Long,
        trimEndUs: Long,
        control: JobControl,
        onProgress: (Float) -> Unit
    ) {
        val outW = plan.width
        val outH = plan.height
        val targetBitrate = plan.bitrate
        val targetFps = plan.fps
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
                // A fixed 2 s GOP at a very low bitrate lets single key frames
                // eat the whole budget; the planner widens it instead.
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, plan.iFrameInterval)
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                )
                if (encoderSupportsBitrateMode(encoderMime, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)) {
                    setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                }
                // This is an offline transcode, not a live capture. The old
                // code asked for realtime priority (0) and pinned
                // KEY_OPERATING_RATE to the output frame rate, which tells the
                // codec to budget for real-time delivery: it spends less effort
                // per frame (worse bits-per-pixel) and on several SoCs throttles
                // the pipeline to 1x, so a ten-minute clip took ten minutes.
                // Non-realtime priority with no operating-rate cap gives the
                // encoder freedom to run flat out and to trade time for size.
                if (android.os.Build.VERSION.SDK_INT >= 23) {
                    setInteger(MediaFormat.KEY_PRIORITY, 1)
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
            //
            // BUG-2 FIX: MediaFormat(MediaFormat) copy constructor does NOT exist on
            // Android — calling it compiles but produces an empty MediaFormat object,
            // discarding all the source track parameters (codec, width, height…) and
            // causing an IllegalArgumentException in decoder.configure().
            // The correct approach is to keep the original inputFormat and override
            // only the rotation key directly on it before passing to configure().
            inputFormat.setInteger(MediaFormat.KEY_ROTATION, 0)
            val decoder = MediaCodec.createDecoderByType(inputMime)
            decoder.configure(inputFormat, inputSurface, null, 0)
            decoder.start()

            var muxer: MediaMuxer? = null
            // Hoisted outside the inner try so the finally block can safely read it.
            var muxerStarted = false
            try {
                muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                muxer.setOrientationHint(rotation)
                var videoMuxerTrack = -1
                var encEos = false
                var inputDone = false
                var decoderEosSignalled = false

                if (trimStartUs > 0) {
                extractor.seekTo(trimStartUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                }

                val decoderInfo = MediaCodec.BufferInfo()
                val encoderInfo = MediaCodec.BufferInfo()
                // Frames are dropped only when the requested rate is below the
                // source rate - the planner already decided this.
                val keepAllFrames = !plan.dropFrames
                val frameIntervalUs = if (keepAllFrames) 0L else (1_000_000.0 / targetFps).toLong()
                // Absolute frame schedule - see VideoPlanner.FrameGate for why
                // this must not be measured from the last kept frame.
                val frameGate = VideoPlanner.FrameGate(frameIntervalUs)
                var lastReported = -1f

                // VID-1 FIX: track whether any work was done in this iteration.
                // When inputDone+decoderEosSignalled but the hardware encoder is
                // still processing, all three dequeue calls return -1 and we spin
                // the CPU at 100% doing nothing. A 1 ms yield gives the encoder
                // time to finish a frame without measurably slowing the pipeline.
                while (!encEos) {
                control.checkActive()
                var didWork = false

                if (!inputDone) {
                    val inIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        didWork = true
                        val buf = decoder.getInputBuffer(inIndex)!!
                        val sampleSize = extractor.readSampleData(buf, 0)
                        val pts = if (sampleSize >= 0) extractor.sampleTime else -1L
                        // Stop at the end of the trim window. Without this the
                        // video track ran to the end of the source while the
                        // audio pass DID honour trimEndUs, so a trimmed export
                        // came out full-length with the tail playing silent.
                        val pastTrimEnd = trimEndUs > 0 && pts > trimEndUs
                        if (sampleSize < 0 || pastTrimEnd) {
                            decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
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
                    didWork = true
                    val render = keepAllFrames || frameGate.shouldKeep(decoderInfo.presentationTimeUs)
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
                    didWork = true
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

                // VID-1 FIX: if all queues were empty, yield for 1 ms to prevent
                // 100 % CPU spin while waiting for the hardware encoder to finish.
                if (!didWork && !encEos) {
                    kotlinx.coroutines.delay(1)
                }
                }
            } finally {
                runCatching { inputSurface.release() }
                runCatching { decoder.stop() }
                runCatching { decoder.release() }
                runCatching { encoder.stop() }
                runCatching { encoder.release() }
                // BUG-10 FIX: MediaMuxer.stop() throws IllegalStateException if
                // start() was never called (e.g. the encoder only emitted a
                // CODEC_CONFIG frame then EOS with no real video data).
                // The runCatching swallows the crash but leaves an empty file.
                // Guard with muxerStarted to skip stop() in that case.
                if (muxerStarted) runCatching { muxer?.stop() }
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
        // BUG-8 FIX: Cache the track format once here instead of calling
        // getTrackFormat(videoIndex) again inside the hot progress loop.
        // Calling getTrackFormat in a tight loop is wasteful; caching it
        // also guarantees consistent results if the extractor state changes.
        val videoTrackFormat = videoExtractor.getTrackFormat(videoIndex)
        val videoMuxerTrack = muxer.addTrack(videoTrackFormat)

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

        // MUX-3 FIX: 1 MB was vastly over-sized for most content. A single
        // compressed video sample is typically ≤ 512 KB even at 4K 60 Mbps.
        // Use 512 KB to halve the direct-buffer allocation cost (OS page pinning).
        val videoBuf = ByteBuffer.allocateDirect(512 * 1024)
        val audioBuf = ByteBuffer.allocateDirect(256 * 1024)
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

        // MUX-4 FIX: seek passthrough audio extractor to the trim start point
        // before reading. Without this, readAudio() spins through every packet
        // from the beginning of the file (potentially minutes of audio) silently
        // skipping them via the `if (pts < audioOffset) continue` guard.
        if (!audioAlreadyTrimmed && audioOffset > 0) {
            audioExtractor?.seekTo(audioOffset, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        }

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

        // MUX-1 FIX: stall counter guards against an infinite loop if
        // readSampleData() keeps returning 0-byte samples without advancing
        // (a pathological but theoretically possible edge case with some demuxers).
        var stallCount = 0
        val MAX_STALLS = 200

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
                    stallCount = 0
                } else if (!writeVideo && audioHasSample) {
                    muxer.writeSampleData(audioMuxerTrack, audioBuf, audioInfo)
                    audioHasSample = readAudio()
                    stallCount = 0
                } else {
                    // Neither stream has a ready sample — read the next one.
                    val prevVideo = videoHasSample
                    val prevAudio = audioHasSample
                    if (!videoDone) videoHasSample = readVideo()
                    if (!audioDone) audioHasSample = readAudio()
                    // MUX-1 FIX: if nothing was produced and nothing finished,
                    // increment the stall counter and break out if we exceed the
                    // limit to avoid a theoretically infinite loop.
                    if (!videoDone && !audioDone
                        && videoHasSample == prevVideo
                        && audioHasSample == prevAudio) {
                        if (++stallCount > MAX_STALLS) break
                    } else {
                        stallCount = 0
                    }
                }

                // Report progress. When trim is not enabled, use the video
                // duration from the cached track format as the denominator.
                if (videoInfo.presentationTimeUs > lastPts) {
                    val totalUs = if (trimEndUs > trimStartUs) trimEndUs - trimStartUs
                    else runCatching { videoTrackFormat.getLong(MediaFormat.KEY_DURATION) }.getOrDefault(0L)
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

    // VID-3 FIX: cache results so we scan MediaCodecList only once per (mime, mode)
    // pair per process lifetime. This avoids rescanning 30-50 codecs on every
    // videoPass call, which adds ~2-5 ms on mid-range devices.
    private val bitrateModeSupportCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    private fun encoderSupportsBitrateMode(mime: String, mode: Int): Boolean {
        val key = "$mime:$mode"
        return bitrateModeSupportCache.getOrPut(key) {
            runCatching {
                val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
                for (ci in list.codecInfos) {
                    if (!ci.isEncoder || !ci.supportedTypes.contains(mime)) continue
                    val caps = ci.getCapabilitiesForType(mime).encoderCapabilities ?: continue
                    if (caps.isBitrateModeSupported(mode)) return@getOrPut true
                }
                false
            }.getOrDefault(false)
        }
    }

    /**
     * Fills in whatever [info] is missing from the tracks themselves, in one
     * extractor pass.
     *
     * MediaInspector usually supplies all of it. When it cannot, the alternative
     * is planning from an empty MediaInfo, which means: output forced to
     * 1280x720 regardless of the source, no audio in the output, encoder told
     * 30 fps, and a guessed 4 Mbps source rate. Every one of those is a silent,
     * irreversible degradation of the user's file, so it is worth one header
     * read to avoid.
     */
    private fun recoverInfo(uri: Uri, info: MediaInfo): MediaInfo {
        val complete = info.width > 0 && info.height > 0 && info.frameRate > 0 &&
            info.videoBitrate > 0 && info.hasAudio
        if (complete) return info

        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)

            fun intAt(index: Int?, key: String): Int {
                if (index == null || index < 0) return 0
                val format = extractor.getTrackFormat(index)
                if (!format.containsKey(key)) return 0
                return runCatching { format.getInteger(key) }.getOrNull() ?: 0
            }

            fun longAt(index: Int?, key: String): Long {
                if (index == null || index < 0) return 0L
                val format = extractor.getTrackFormat(index)
                if (!format.containsKey(key)) return 0L
                return runCatching { format.getLong(key) }.getOrNull() ?: 0L
            }

            val video = findTrack(extractor, "video/")
            val audio = findTrack(extractor, "audio/")
            info.copy(
                // Without a duration the corrective pass cannot measure the
                // first encode at all, so it silently never runs.
                durationMs = info.durationMs.takeIf { it > 0 }
                    ?: longAt(video, MediaFormat.KEY_DURATION) / 1000L,
                width = info.width.takeIf { it > 0 } ?: intAt(video, MediaFormat.KEY_WIDTH),
                height = info.height.takeIf { it > 0 } ?: intAt(video, MediaFormat.KEY_HEIGHT),
                rotation = info.rotation.takeIf { it != 0 }
                    ?: intAt(video, MediaFormat.KEY_ROTATION),
                frameRate = info.frameRate.takeIf { it > 0 }
                    ?: intAt(video, MediaFormat.KEY_FRAME_RATE).coerceIn(0, 240),
                videoBitrate = info.videoBitrate.takeIf { it > 0 }
                    ?: intAt(video, MediaFormat.KEY_BIT_RATE),
                audioBitrate = info.audioBitrate.takeIf { it > 0 }
                    ?: intAt(audio, MediaFormat.KEY_BIT_RATE),
                hasVideo = info.hasVideo || video != null,
                // Only trust a positive answer here: a container the retriever
                // could not parse may still carry audio, and dropping it is not
                // recoverable after the encode.
                hasAudio = info.hasAudio || audio != null
            )
        } catch (t: Throwable) {
            info
        } finally {
            runCatching { extractor.release() }
        }
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
