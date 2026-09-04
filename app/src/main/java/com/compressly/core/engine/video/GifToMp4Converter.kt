package com.compressly.core.engine.video

import android.content.Context
import android.graphics.Movie
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.view.Surface
import com.compressly.core.engine.JobControl
import kotlin.math.max
import kotlin.math.min

/**
 * Animated GIF -> MP4 converter.
 *
 * Android has no GIF decoder that exposes frames directly, but
 * [android.graphics.Movie] decodes an animated GIF. We draw each frame onto a
 * MediaCodec encoder's input surface (H.264) and mux to an MP4, so an animated
 * GIF becomes a small, streamable video instead of being dropped or flattened
 * to a single static image. The source GIF is never modified.
 *
 * Per-frame GIF delays are not exposed by `Movie` (only total duration), so the
 * timeline is approximated at a fixed frame rate. Best-effort: on any failure it
 * throws [GifConversionException] (a stable key) and never writes a corrupt file.
 */
class GifToMp4Converter(private val context: Context) {

    private val BPP = 0.16

    suspend fun convert(
        inputUri: Uri,
        outputPath: String,
        control: JobControl,
        onProgress: (Float) -> Unit
    ) {
        val bytes = context.contentResolver.openInputStream(inputUri)?.use { it.readBytes() }
            ?: throw GifConversionException("gif_read")
        val movie = Movie.decodeByteArray(bytes, 0, bytes.size)
            ?: throw GifConversionException("gif_decode")
        if (movie.width() <= 0 || movie.height() <= 0) throw GifConversionException("gif_decode")

        val width = alignEven(movie.width())
        val height = alignEven(movie.height())
        val durationMs = movie.duration().takeIf { it > 0 }?.toLong() ?: 1000L

        val fps = 15
        val frameCount = max(1, (durationMs * fps / 1000L).toInt())
        val stepMs = max(1L, durationMs / frameCount)
        val bitrate = (width * height * fps * BPP).toInt().coerceIn(800_000, 20_000_000)

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        }

        val codec = try {
            MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        } catch (_: Exception) {
            throw GifConversionException("gif_no_encoder")
        }

        var surface: Surface? = null
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            surface = codec.createInputSurface()
            codec.start()
        } catch (_: Exception) {
            try { codec.release() } catch (_: Exception) {}
            throw GifConversionException("gif_encode")
        }

        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var trackIndex = -1
        val startMuxer = {
            if (!muxerStarted) {
                trackIndex = muxer!!.addTrack(codec.outputFormat)
                muxer!!.start()
                muxerStarted = true
            }
        }
        try {
            muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val pps = 1_000_000L / fps
            var frame = 0L
            var t = 0L
            while (t < durationMs || frame == 0L) {
                control.checkActive()
                movie.setTime(t.coerceAtMost(durationMs).toInt())
                val canvas = surface!!.lockCanvas(null)
                try {
                    canvas.drawColor(android.graphics.Color.BLACK)
                    movie.draw(canvas, 0f, 0f)
                } finally {
                    surface!!.unlockCanvasAndPost(canvas)
                }
                drain(codec, muxer!!, startMuxer, trackIndexStart = { trackIndex }, timeoutUs = 0L)
                frame++
                t += stepMs
                onProgress(min(1f, frame.toFloat() / frameCount))
            }
            codec.signalEndOfInputStream()
            drain(codec, muxer!!, startMuxer, trackIndexStart = { trackIndex }, timeoutUs = 10_000L, endOfStream = true)
            if (!muxerStarted || trackIndex < 0) throw GifConversionException("gif_encode")
            muxer!!.stop()
        } catch (e: GifConversionException) {
            throw e
        } catch (_: Exception) {
            throw GifConversionException("gif_encode")
        } finally {
            try { codec.stop() } catch (_: Exception) {}
            try { codec.release() } catch (_: Exception) {}
            surface?.release()
            try { muxer?.release() } catch (_: Exception) {}
        }
    }

    /** Dequeue encoded frames until the current queue is drained (or EOS). */
    private fun drain(
        codec: MediaCodec,
        muxer: MediaMuxer,
        startMuxer: () -> Unit,
        trackIndexStart: () -> Int,
        timeoutUs: Long,
        endOfStream: Boolean = false
    ) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val idx = codec.dequeueOutputBuffer(info, timeoutUs)
            when {
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> startMuxer()
                idx >= 0 -> {
                    val buf = codec.getOutputBuffer(idx)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && buf != null) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        muxer.writeSampleData(trackIndexStart(), buf, info)
                    }
                    codec.releaseOutputBuffer(idx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    private fun alignEven(v: Int) = if (v % 2 == 0) v else v + 1
}

/** Error key surfaces as a user-facing message. */
class GifConversionException(val key: String) : Exception(key)
