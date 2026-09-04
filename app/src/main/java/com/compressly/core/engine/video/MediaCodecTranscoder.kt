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
        private const val MIME_AV1 = "video/av01"
        private const val MIME_AAC = "audio/mp4a-latm"
        private const val TIMEOUT_US = 10_000L

        const val ERR_NO_VIDEO = "no_video"
        const val ERR_NO_ENCODER = "no_encoder"
        const val ERR_ENCODE = "encode_failed"
        const val ERR_DECODE = "decode_failed"
    }

    data class Stats(
        val outputSize: Long,
        val durationMs: Long,
        /** The codec actually written: "h264" or "h265" (an H.265 request may have fallen back). */
        val codec: String
    )

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

        // Resolve encoders (H.264/H.265, hardware-first, software fallback).
        // VIDEO-FIX-1: a LIST of candidates, tried in order. The old code picked
        // one encoder by name and never retried, so a vendor encoder that rejects
        // the configured format (missing COLOR_FormatSurface, unknown keys,
        // resolution limits) killed the whole transcode with no fallback at all.
        val requestedMime = requestedMimeFor(settings.codec)
        val encoderChoices = resolveEncoderCandidates(requestedMime)
        if (encoderChoices.isEmpty()) throw VideoCompressionException(ERR_NO_ENCODER)

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

        // ENGINE-NOOP-FIX: the job asks for no change at all (same codec,
        // resolution, fps, audio — isNoOpTranscode) and the planner's target
        // sits at/above the source rate (isNoGainTarget): re-encoding can
        // only add a generation of loss, and on files thinner than the
        // encoder floor it can even make the file BIGGER — the MIN_BITRATE
        // clamp in targetVideoBitrate() defeats the 97 % no-gain cap, and the
        // corrective pass never engages below MIN_CORRECTABLE_BYTES. Copy the
        // original instead of encoding. (Compressor's keep-original check
        // covers the app path; this is the same rule at engine level so the
        // transcoder is honest for ANY caller, not just Compressor.)
        if (VideoPlanner.isNoOpTranscode(plannedInfo, settings, preset) &&
            VideoPlanner.isNoGainTarget(plannedInfo, settings, preset)
        ) {
            val input = context.contentResolver.openInputStream(inputUri)
                ?: throw VideoCompressionException(ERR_NO_VIDEO)
            File(outputPath).outputStream().use { out -> input.use { it.copyTo(out, 256 * 1024) } }
            onProgress(1f)
            // Labeled (local) return: a plain `return` is prohibited inside a
            // suspend lambda, but the value flows out as withContext's result.
            return@withContext Stats(
                File(outputPath).length(),
                plannedInfo.durationMs,
                codecLabel(plannedInfo.mimeType)
            )
        }
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
            // VIDEO-FIX-1: the pass returns the encoder it actually used, so the
            // result summary can report the real codec (H.265 -> H.264 fallback
            // must not be labelled H.265).
            var firstPass = videoPass(
                inputUri = inputUri,
                outputPath = tempVideo.absolutePath,
                info = plannedInfo,
                settings = settings,
                choices = encoderChoices,
                plan = plan,
                rotation = rotation,
                trimStartUs = trimStartUs,
                trimEndUs = trimEndUs,
                control = control,
                onProgress = { p -> onProgress(p * 0.50f) }
            )
            // Where the final video stream really starts (original timeline).
            // Audio must be re-based from THIS point, not from trimStartUs, or
            // the soundtrack leads the picture by the key-frame gap.
            var videoStartUs = if (trimStartUs > 0) firstPass.firstKeptPtsUs else 0L

            // 1b. Corrective pass. Hardware encoders treat KEY_BIT_RATE as a
            //     hint in VBR mode and routinely overshoot it, so the planner's
            //     target is not the rate the file was actually written at.
            //     Measure what we got and, if it is meaningfully over, encode
            //     once more at a proportionally corrected rate. This is what
            //     makes "compress harder" actually produce a smaller file.
            val videoDurationMs = trimmedDurationMs(plannedInfo.durationMs, trimStartUs, trimEndUs)
            val correction = VideoPlanner.correctedBitrate(
                plan.bitrate,
                tempVideo.length(),
                videoDurationMs,
                aggressive = plan.aggressiveCorrection
            )
            if (correction != null) {
                control.checkActive()
                Storage.deleteQuietly(tempVideo)
                // The corrective pass re-encodes from scratch, so the first
                // kept frame can land at a different PTS — re-measure.
                firstPass = videoPass(
                    inputUri = inputUri,
                    outputPath = tempVideo.absolutePath,
                    info = plannedInfo,
                    settings = settings,
                    // Re-use the encoder the first pass proved working; never
                    // re-negotiate between passes (a different choice could
                    // change the bitstream behaviour halfway through).
                    choices = listOf(firstPass.choice),
                    // The key-frame interval has to follow the corrected rate:
                    // the GOP that fitted the first pass is too short for the
                    // lower one, and short GOPs are exactly what eats the budget.
                    plan = plan.copy(
                        bitrate = correction,
                        iFrameInterval = VideoPlanner.iFrameIntervalSeconds(correction, plan.width, plan.height, plan.fps)
                    ),
                    rotation = rotation,
                    trimStartUs = trimStartUs,
                    trimEndUs = trimEndUs,
                    control = control,
                    onProgress = { p -> onProgress(0.50f + p * 0.30f) }
                )
                videoStartUs = if (trimStartUs > 0) firstPass.firstKeptPtsUs else 0L
            } else {
                onProgress(0.80f)
            }

            val wantsAudio = settings.audioMode != VideoAudioMode.STRIP && plannedInfo.hasAudio

            // 2. Audio: passthrough or transcode.
            var audioAlreadyTrimmed = false
            if (wantsAudio) {
                // AUDIO-PASS: KEEP copies the file's own AAC track straight into
                // the output — no decode/encode, no generation of loss. A
                // COMPRESS request whose planned rate lands at/above the source
                // (the planner caps it there) is the same honesty rule applied
                // to video: re-encoding for nothing only adds loss, so the track
                // is copied instead. Only a genuinely lower target re-encodes.
                val plannedAudioRate = VideoPlanner.audioBitrateBps(plannedInfo, settings, preset)
                val noGainCompress = settings.audioMode == VideoAudioMode.COMPRESS &&
                    plannedInfo.audioBitrate > 0 &&
                    plannedAudioRate >= (plannedInfo.audioBitrate * VideoPlanner.NO_GAIN_RATIO.toDouble()).toInt()
                val passthroughAac = audioMimeIs(inputUri, MIME_AAC) &&
                    (settings.audioMode == VideoAudioMode.KEEP || noGainCompress)
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
                        // TRIM-KEY-FIX: trim the audio from where the VIDEO
                        // actually starts (first kept frame), not the
                        // requested point — otherwise the soundtrack runs
                        // ahead of the picture by the key-frame gap.
                        trimStartUs = videoStartUs,
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
                    audioStartUs = videoStartUs,
                    audioAlreadyTrimmed = audioAlreadyTrimmed,
                    control = control,
                    onProgress = { p -> onProgress(0.92f + p * 0.08f) }
                )
            }
            // FASTSTART-FIX: MediaMuxer writes moov AFTER mdat, so the output
            // cannot be streamed until the whole file is read. Relocate moov
            // before mdat (best-effort) so players can start playback early.
            bestEffortFastStart(outputPath)
            control.checkActive()
            onProgress(1f)
            // VIDEO-FIX-2: never report a zero-byte output as success. When the
            // encoder only emitted CODEC_CONFIG + EOS (no real frame), or the
            // muxer never started, the file at outputPath is empty — publishing
            // it as "done" drops a corrupt 0-byte clip into the gallery.
            if (File(outputPath).length() <= 0) {
                throw VideoCompressionException(ERR_ENCODE)
            }
            val codecTag = codecLabel(firstPass.choice.mime)
            // STATS-FIX: measure from the RECOVERED info (the original `info`
            // has a zero duration when MediaInspector failed, which made the
            // stats bar show 0 s for a perfectly good file). And base the
            // window on the ACTUAL video start, not the requested trim
            // point — the output stream begins where the encoder began.
            val measuredVideoStart = if (trimStartUs > 0) videoStartUs else 0L
            Stats(
                File(outputPath).length(),
                trimmedDurationMs(plannedInfo.durationMs, measuredVideoStart, trimEndUs),
                codecTag
            )
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

    /**
     * Result of one decode->encode pass: the encoder that was actually used
     * ([VideoPassResult.choice]) and, when trimming, the original-timeline PTS
     * of the first frame rendered into the encoder ([VideoPassResult.firstKeptPtsUs]).
     * The audio pass and the stats are re-based from that point, because the
     * output stream begins there — not at the requested trim point.
     *
     * [choices] are tried in order; each candidate gets two configure
     * attempts (full format, then a minimal format without optional keys).
     *
     * VIDEO-FIX-1: the old code configured exactly one encoder by name. Vendor
     * encoders routinely reject optional keys (KEY_BITRATE_MODE, KEY_PRIORITY,
     * KEY_I_FRAME_INTERVAL) or the COLOR_FormatSurface input — on those devices
     * a single error aborted the whole job with no fallback, which read as
     * "video compression never works". Now every candidate is attempted, and
     * the failure of one cannot take the job down.
     */
    private suspend fun videoPass(
        inputUri: Uri,
        outputPath: String,
        info: MediaInfo,
        settings: VideoSettings,
        choices: List<EncoderChoice>,
        plan: VideoPlanner.Plan,
        rotation: Int,
        trimStartUs: Long,
        trimEndUs: Long,
        control: JobControl,
        onProgress: (Float) -> Unit
    ): VideoPassResult {
        val outW = plan.width
        val outH = plan.height
        val targetBitrate = plan.bitrate
        val targetFps = plan.fps
        // Hoisted outside the try so the final return (after the
        // `finally` block) can see it.
        var chosen: EncoderChoice? = null
        // TRIM-KEY-FIX: original-timeline PTS of the first frame rendered
        // into the encoder (= the point the output stream starts), and the
        // encoder-output PTS of the first emitted sample (the muxed track
        // is re-based to 0 from it).
        var firstRenderedPtsUs = Long.MIN_VALUE
        var firstOutputPtsUs = Long.MIN_VALUE
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, inputUri, null)
            val videoIndex = findTrack(extractor, "video/")
                ?: throw VideoCompressionException(ERR_NO_VIDEO)
            extractor.selectTrack(videoIndex)
            val inputFormat = extractor.getTrackFormat(videoIndex)
            val inputMime = inputFormat.getString(MediaFormat.KEY_MIME)
                ?: throw VideoCompressionException(ERR_DECODE)

            // HDR-passthrough: read the source colour/HDR metadata once here
            // (it belongs to the source track) and hand it to the encoder.
            val hdr = if (settings.preserveHdr) readHdrInfo(inputFormat) else null

            // ── Pick a working encoder across the candidate list ──────────
            var encoder: MediaCodec? = null
            var inputSurface: android.view.Surface? = null
            for (candidate in choices) {
                var enc: MediaCodec? = null
                try {
                    enc = createConfiguredEncoder(candidate, outW, outH, targetBitrate, targetFps, plan, hdr)
                    val surface = enc.createInputSurface()
                    enc.start()
                    // Success on this candidate — commit and move on.
                    inputSurface = surface
                    encoder = enc
                    chosen = candidate
                    break
                } catch (t: Throwable) {
                    // ENGINE-ONLY failures; user cancellation must propagate.
                    // Release BOTH: the one configured here (if start() failed)
                    // and any previously committed instance (if this candidate
                    // reused it) — never leak a codec while probing.
                    if (t is com.compressly.core.engine.CompressionCancelledException) throw t
                    runCatching { enc?.release() }
                    runCatching { encoder?.release() }
                    encoder = null
                    inputSurface = null
                }
            }
            val enc = encoder ?: throw VideoCompressionException(ERR_NO_ENCODER)
            val inputSurfaceFinal = inputSurface ?: throw VideoCompressionException(ERR_NO_ENCODER)

            // ── Configure the decoder onto the encoder's input surface ─────
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
            decoder.configure(inputFormat, inputSurfaceFinal, null, 0)
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
                    val pts = decoderInfo.presentationTimeUs
                    // TRIM-KEY-FIX: decode EVERY frame — the P-frame reference
                    // chain must stay intact — but render only frames at/after
                    // the trim start. The encoder's first input frame is then
                    // the first in-window frame, emitted as an IDR, so the
                    // output bitstream really begins where the user asked.
                    // (Old code rendered pre-trim frames too and dropped the
                    // encoded samples afterwards: the output then started
                    // mid-GOP, on frames the decoder never saw as a keyframe
                    // — a corrupt opening for up to a full GOP.)
                    val inWindow = trimStartUs <= 0 || pts >= trimStartUs
                    val render = inWindow && (keepAllFrames || frameGate.shouldKeep(pts))
                    if (render && firstRenderedPtsUs == Long.MIN_VALUE) {
                        firstRenderedPtsUs = pts
                    }
                    decoder.releaseOutputBuffer(decoderOut, render)
                    if (decoderInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 && !decoderEosSignalled) {
                        enc.signalEndOfInputStream()
                        decoderEosSignalled = true
                    }
                    decoderOut = decoder.dequeueOutputBuffer(decoderInfo, 0)
                }

                // Drain encoder -> muxer.
                var encoderOut = enc.dequeueOutputBuffer(encoderInfo, 0)
                while (encoderOut >= 0) {
                    didWork = true
                    if (encoderInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        encoderInfo.size = 0
                    }
                    if (encoderInfo.size > 0) {
                        val encoded = enc.getOutputBuffer(encoderOut)!!
                        if (!muxerStarted) {
                            videoMuxerTrack = muxer.addTrack(enc.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                        // TRIM-KEY-FIX: write EVERYTHING the encoder emits —
                        // pre-trim frames never reach the encoder (render
                        // gate above) — rebasing PTS to the first output
                        // sample so the muxed track is zero-based.
                        if (firstOutputPtsUs == Long.MIN_VALUE) {
                            firstOutputPtsUs = encoderInfo.presentationTimeUs
                        }
                        encoded.position(encoderInfo.offset)
                        encoded.limit(encoderInfo.offset + encoderInfo.size)
                        val adjusted = MediaCodec.BufferInfo()
                        adjusted.set(
                            0,
                            encoderInfo.size,
                            (encoderInfo.presentationTimeUs - firstOutputPtsUs).coerceAtLeast(0),
                            encoderInfo.flags
                        )
                        muxer.writeSampleData(videoMuxerTrack, encoded, adjusted)
                    }
                    if (encoderInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        encEos = true
                    }
                    enc.releaseOutputBuffer(encoderOut, false)
                    encoderOut = enc.dequeueOutputBuffer(encoderInfo, 0)
                }

                // VID-1 FIX: if all queues were empty, yield for 1 ms to prevent
                // 100 % CPU spin while waiting for the hardware encoder to finish.
                if (!didWork && !encEos) {
                    kotlinx.coroutines.delay(1)
                }
                }
                // VIDEO-FIX-2: no frame ever reached the muxer (the encoder only
                // emitted CODEC_CONFIG then EOS). This is a failed encode, not a
                // success — never fall through to publishing an empty file.
                if (!muxerStarted) throw VideoCompressionException(ERR_ENCODE)
            } finally {
                runCatching { inputSurfaceFinal.release() }
                runCatching { decoder.stop() }
                runCatching { decoder.release() }
                runCatching { enc.stop() }
                runCatching { enc.release() }
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
        // If nothing was ever rendered the pass fails earlier (muxerStarted
        // guard), so firstRenderedPtsUs is set on every successful return.
        val startUs = if (firstRenderedPtsUs != Long.MIN_VALUE) firstRenderedPtsUs else 0L
        return VideoPassResult(chosen!!, startUs)
    }

    /**
     * Creates, configures and (on entry) starts the encoder for [choice].
     *
     * Two attempts per encoder: first the full format (bitrate mode + priority
     * + GOP interval), then a minimal format with only the mandatory keys.
     * Vendor encoders differ wildly in which optional keys they accept; the
     * second attempt is what keeps a picky encoder from killing the job.
     */
    private fun createConfiguredEncoder(
        choice: EncoderChoice,
        outW: Int,
        outH: Int,
        targetBitrate: Int,
        targetFps: Int,
        plan: VideoPlanner.Plan,
        hdr: HdrInfo?
    ): MediaCodec {
        fun buildFormat(full: Boolean): MediaFormat =
            MediaFormat.createVideoFormat(choice.mime, outW, outH).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, targetBitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, targetFps)
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                )
                if (full) {
                    // HDR-passthrough: carry the source colour transfer,
                    // standard and range so an HDR10/HDR10+ clip is not
                    // flattened to SDR. These keys sit behind the same
                    // full-format/minimal-format retry as every other optional
                    // key: an encoder that rejects them falls back to the
                    // minimal format (SDR) rather than aborting the job.
                    if (hdr != null) {
                        setInteger(MediaFormat.KEY_COLOR_TRANSFER, hdr.colorTransfer)
                        setInteger(MediaFormat.KEY_COLOR_STANDARD, hdr.colorStandard)
                        setInteger(MediaFormat.KEY_COLOR_RANGE, hdr.colorRange)
                    }
                    // A fixed 2 s GOP at a very low bitrate lets single key
                    // frames eat the whole budget; the planner widens it.
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, plan.iFrameInterval)
                    // VIDEO-FIX-3: the bitrate mode was picked by asking whether
                    // ANY encoder supports it, then applied to a specific one.
                    // If THE CHOSEN encoder does not support CBR/VBR the
                    // configure() call throws and the whole video fails. The
                    // capability check is now per-encoder.
                    val requestedMode = if (plan.preferCbr)
                        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
                    else
                        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
                    val mode = when {
                        encoderSupportsBitrateMode(choice.name, choice.mime, requestedMode) -> requestedMode
                        requestedMode == MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR &&
                            encoderSupportsBitrateMode(
                                choice.name, choice.mime,
                                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
                            ) -> MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
                        else -> null
                    }
                    if (mode != null) setInteger(MediaFormat.KEY_BITRATE_MODE, mode)
                    // This is an offline transcode, not a live capture. The old
                    // code asked for realtime priority (0) and pinned
                    // KEY_OPERATING_RATE to the output frame rate, which tells
                    // the codec to budget for real-time delivery: it spends less
                    // effort per frame (worse bits-per-pixel) and on several
                    // SoCs throttles the pipeline to 1x, so a ten-minute clip
                    // took ten minutes. Non-realtime priority with no
                    // operating-rate cap gives the encoder freedom to run flat
                    // out and to trade time for size.
                    // KEY_PRIORITY exists since API 23; minSdk is 26, so no guard needed.
                    setInteger(MediaFormat.KEY_PRIORITY, 1)
                }
            }

        var enc = MediaCodec.createByCodecName(choice.name)
        try {
            enc.configure(
                buildFormat(full = true), null, null,
                MediaCodec.CONFIGURE_FLAG_ENCODE
            )
            return enc
        } catch (t: Throwable) {
            // Optional keys rejected — drop them and retry once with the
            // minimal format before giving up on this candidate.
            runCatching { enc.release() }
        }
        enc = MediaCodec.createByCodecName(choice.name)
        enc.configure(
            buildFormat(full = false), null, null,
            MediaCodec.CONFIGURE_FLAG_ENCODE
        )
        return enc
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
        val ok = com.compressly.core.engine.audio.AacTranscoder.transcode(
            context = context,
            inputUri = inputUri,
            outputPath = outputPath,
            bitrate = bitrate,
            trimStartUs = trimStartUs,
            trimEndUs = trimEndUs,
            control = control,
            onProgress = onProgress
        )
        // false = no audio track found or nothing was written; a video that
        // was analysed as having audio must not silently come out muted.
        if (!ok) throw VideoCompressionException(ERR_ENCODE)
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
        /** Original-timeline point the (passthrough) audio is re-based from.
     *  After TRIM-KEY-FIX this is where the VIDEO starts, which can be later
     *  than the requested trimStartUs by the key-frame gap. */
        audioStartUs: Long,
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
        // MUX-3 FIX: 1 MB was a wasteful default for MOST content, but it was
        // never the real invariant — SIZED-BUF-FIX below grows the buffer to
        // the actual sample size. A compressed video sample is NOT bounded by
        // any fixed size: 4K60 @ 60 Mbps averages 1 MB per frame and key
        // frames are several times larger. readSampleData() copies only what
        // fits, so a fixed 512 KB buffer silently truncated big samples
        // (the rest was skipped by advance()) — corrupt frames in the output.
        // Start small for common phone clips; grow on demand.
        var videoBuf = ByteBuffer.allocateDirect(512 * 1024)
        val audioBuf = ByteBuffer.allocateDirect(256 * 1024)
        val videoInfo = MediaCodec.BufferInfo()
        val audioInfo = MediaCodec.BufferInfo()
        var videoHasSample = false
        var audioHasSample = false
        var videoDone = false
        var audioDone = audioExtractor == null
        var lastPts = 0L
        // VIDEO-FIX-2: if neither stream ever produced a sample the muxer wrote
        // nothing; that is a failed encode and must not be published as success.
        var wroteAnySample = false
        // Passthrough audio comes from the ORIGINAL file (needs trimming to the
        // trim window); transcoded audio is already trimmed (PTS start at 0).
        // TRIM-KEY-FIX: the video track now starts at audioStartUs (first key
        // frame at/after the requested trim point), so the passthrough audio
        // must be re-based from the same point, not the requested one.
        val audioOffset = if (audioAlreadyTrimmed) 0L else audioStartUs
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
            // SIZED-BUF-FIX: size the buffer to the real sample BEFORE reading.
            // NEWAPI-FIX: MediaExtractor#getSampleSize is API 28+ (this is what our
            // minSdk-26 lint gate flagged) and would NoSuchMethodError on
            // Android 8.0/8.1. Only pre-size when available; on API 26-27 we can't
            // know the size ahead, so fall back to readSampleData() reporting the
            // bytes it wrote and grow the buffer if the frame is exceptionally large.
            val sampleSize = if (android.os.Build.VERSION.SDK_INT >= 28) {
                videoExtractor.sampleSize.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            } else {
                // API < 28: readSampleData() itself reports the size it wrote.
                0
            }
            if (sampleSize > videoBuf.capacity()) {
                videoBuf = ByteBuffer.allocateDirect(sampleSize)
            }
            var sz = videoExtractor.readSampleData(videoBuf, 0)
            // Grow-and-retry: on pre-28 a frame can exceed the (unpre-sized) buffer.
            var retries = 0
            while (sz < 0 && retries < 3 && videoBuf.capacity() < 64 * 1024 * 1024) {
                videoBuf = ByteBuffer.allocateDirect(videoBuf.capacity() * 4)
                sz = videoExtractor.readSampleData(videoBuf, 0)
                retries++
            }
            if (sz < 0) { videoDone = true; return false }
            videoInfo.set(0, sz, videoExtractor.sampleTime, sampleFlagsToCodecFlags(videoExtractor.sampleFlags))
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
                val flags = ae.sampleFlags
                ae.advance()
                if (pts < audioOffset) continue
                if (audioEnd > 0 && pts > audioEnd) { audioDone = true; return false }
                audioInfo.set(0, sz, (pts - audioOffset).coerceAtLeast(0), sampleFlagsToCodecFlags(flags))
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
                    wroteAnySample = true
                    videoHasSample = readVideo()
                    stallCount = 0
                } else if (!writeVideo && audioHasSample) {
                    muxer.writeSampleData(audioMuxerTrack, audioBuf, audioInfo)
                    wroteAnySample = true
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
            // VIDEO-FIX-2: zero samples written == failed encode.
            if (!wroteAnySample) throw VideoCompressionException(ERR_ENCODE)
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

    /**
     * MediaExtractor sample flags and MediaCodec buffer flags are different
     * constants; MediaMuxer.writeSampleData() expects the latter (BUFFER_FLAG_*).
     * Both use bit 1 for sync/key frames, so the conversion is exact.
     */
    private fun sampleFlagsToCodecFlags(flags: Int): Int =
        if (flags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0

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

    /** One decode->encode pass: the encoder actually used, and (when
     *  trimming) where the output stream really starts on the original
     *  timeline — see the TRIM-KEY-FIX notes in [videoPass]. */
    private data class VideoPassResult(val choice: EncoderChoice, val firstKeptPtsUs: Long)

    /** Human label for a codec, used in the result summary. */
    private fun codecLabel(mime: String?): String {
        val m = mime?.lowercase() ?: return "h264"
        return when {
            m.contains("av01") || m.contains("av1") -> "av1"
            m.contains("hevc") -> "h265"
            else -> "h264"
        }
    }

    /** Colour/HDR metadata to carry from the source track into the encoder. */
    private data class HdrInfo(
        val colorTransfer: Int,
        val colorStandard: Int,
        val colorRange: Int
    )

    /** The container MIME family for a codec request. */
    private fun requestedMimeFor(codec: VideoCodec): String = when (codec) {
        VideoCodec.H265 -> MIME_H265
        VideoCodec.AV1 -> MIME_AV1
        VideoCodec.H264 -> MIME_H264
    }

    /**
     * All usable encoders for [requestedMime], best first: the requested codec
     * (hardware, then software), then its compatibility chain — AV1 falls back
     * to HEVC then H.264, HEVC falls back to H.264 — so a device that cannot
     * honour the request still produces a file instead of failing. Each
     * candidate is configured independently by [videoPass] (with its own
     * minimal-format retry), so one broken vendor entry can no longer abort
     * the whole transcode.
     */
    private fun resolveEncoderCandidates(requestedMime: String): List<EncoderChoice> {
        val result = mutableListOf<EncoderChoice>()
        val chain = when (requestedMime) {
            MIME_AV1 -> listOf(MIME_AV1, MIME_H265, MIME_H264)
            MIME_H265 -> listOf(MIME_H265, MIME_H264)
            else -> listOf(MIME_H264)
        }
        for (mime in chain) {
            findEncoder(mime, hardwareOnly = true)?.let { result += EncoderChoice(it, mime) }
            findEncoder(mime, hardwareOnly = false)?.let { result += EncoderChoice(it, mime) }
        }
        return result
    }

    /**
     * Reads the colour/HDR metadata from a source track format, or null when
     * the track is not HDR (transfer/standard/range all present) — in which
     * case the encoder uses its own defaults. Only the three integer keys are
     * read; static HDR metadata (KEY_HDR_STATIC_INFO) is a buffer of variable
     * length and is deliberately not relayed, because relaying it risks
     * encoders rejecting the format.
     */
    private fun readHdrInfo(format: MediaFormat): HdrInfo? {
        fun key(name: String): Int? =
            if (format.containsKey(name)) runCatching { format.getInteger(name) }.getOrNull() else null
        val transfer = key(MediaFormat.KEY_COLOR_TRANSFER)
        val standard = key(MediaFormat.KEY_COLOR_STANDARD)
        val range = key(MediaFormat.KEY_COLOR_RANGE)
        if (transfer == null || standard == null || range == null) return null
        return HdrInfo(transfer, standard, range)
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

    // VID-3 FIX: cache results so we scan MediaCodecList only once per
    // (encoder, mime, mode) triple per process lifetime. This avoids rescanning
    // 30-50 codecs on every videoPass call, which adds ~2-5 ms on mid-range
    // devices.
    private val bitrateModeSupportCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /**
     * True when THE CHOSEN ENCODER (by name) supports [mode]. Asking "does any
     * encoder support this mode" and then setting it on a different one is what
     * made configure() throw IllegalArgumentException on devices whose top
     * encoder lacks CBR/VBR — killing video compression outright.
     */
    private fun encoderSupportsBitrateMode(encoderName: String, mime: String, mode: Int): Boolean {
        val key = "$encoderName:$mime:$mode"
        return bitrateModeSupportCache.getOrPut(key) {
            runCatching {
                val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
                for (ci in list.codecInfos) {
                    if (ci.name != encoderName || !ci.isEncoder || !ci.supportedTypes.contains(mime)) continue
                    val caps = ci.getCapabilitiesForType(mime).encoderCapabilities ?: return@getOrPut false
                    return@getOrPut caps.isBitrateModeSupported(mode)
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

/**
 * FASTSTART-FIX: best-effort relocate `moov` before `mdat` on the transcoded
 * output so players can stream it immediately. MediaMuxer (and therefore the
 * merge pass) writes moov last; this rewrites the container in place with the
 * same bytes, only re-ordered. It never re-encodes and never corrupts: on any
 * parse failure it leaves the original file untouched.
 */
private fun bestEffortFastStart(outputPath: String) {
    val src = File(outputPath)
    if (!src.exists() || src.length() <= 0) return
    val dst = File(outputPath + ".faststart")
    try {
        if (Mp4FastStart.remux(src, dst) && dst.length() > 0 && dst.length() == src.length()) {
            if (!dst.renameTo(src)) {
                dst.copyTo(src, overwrite = true)
                Storage.deleteQuietly(dst)
            }
        } else {
            Storage.deleteQuietly(dst)
        }
    } catch (_: Exception) {
        Storage.deleteQuietly(dst)
    }
}
