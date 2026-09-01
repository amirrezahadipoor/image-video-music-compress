package com.compressly

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.compressly.core.engine.JobControl
import com.compressly.core.engine.MediaInspector
import com.compressly.core.engine.model.CompressionPreset
import com.compressly.core.engine.model.VideoSettings
import com.compressly.core.engine.video.MediaCodecTranscoder
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real end-to-end smoke test of the video pipeline, on a real device /
 * emulator (no mocks): a tiny H.264 clip is GENERATED on-device with the
 * hardware encoder, then fed through the production MediaCodecTranscoder and
 * the output is sanity-checked. This is the only test that exercises the
 * actual MediaCodec surface pipeline, encoder negotiation and the muxer —
 * things the JVM unit tests cannot reach.
 *
 * Run: ./gradlew :app:connectedBazaarDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class VideoTranscodeSmokeTest {

    private val W = 320
    private val H = 240

    private companion object {
        const val FPS = 15
        const val FRAME_US = 1_000_000L / FPS
        const val FRAME_COUNT = 24
    }

    @Test
    fun smartTranscodeProducesAValidSmallerOutput() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val clip = makeTestClip(context)
        val out = File(context.cacheDir, "smoke_out_${System.nanoTime()}.mp4")
        try {
            val info = MediaInspector.inspect(context, Uri.fromFile(clip))
            assertTrue("probe must see the generated video track", info.hasVideo)

            val stats = runBlocking {
                MediaCodecTranscoder(context).transcode(
                    inputUri = Uri.fromFile(clip),
                    outputPath = out.absolutePath,
                    info = info,
                    settings = VideoSettings(),          // defaults: H.264, ORIGINAL, KEEP audio
                    preset = CompressionPreset.SMART,    // the default grade
                    control = JobControl(),
                    onProgress = {}
                )
            }

            assertTrue("output must not be empty", out.length() > 0)
            assertTrue(
                "codec written must be a known one (was ${stats.codec})",
                stats.codec == "h264" || stats.codec == "h265"
            )
            assertTrue("duration must be sane (was ${stats.durationMs} ms)", stats.durationMs > 0)
            // SMART on a 24-frame clip: target is capped by the no-gain rule,
            // so it must never come back LARGER than the input.
            assertTrue(
                "output ${out.length()} must not exceed input ${clip.length()}",
                out.length() <= clip.length()
            )
        } finally {
            clip.delete()
            out.delete()
        }
    }

    /**
     * Generates a short H.264 MP4 with the on-device encoder: 24 frames of a
     * moving vertical grey gradient (a fake screen recording). Returns the
     * finished file or throws — the test fails loudly if the device cannot
     * encode at all.
     */
    private fun makeTestClip(context: Context): File {
        val file = File(context.cacheDir, "smoke_src_${System.nanoTime()}.mp4")
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, W, H).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, 900_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        var muxerStarted = false
        var inputDone = false
        var outputDone = false
        var ptsUs = 0L
        var frameIndex = 0
        val info = MediaCodec.BufferInfo()
        val yuv = ByteArray(W * H * 3 / 2)
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!
                        buf.clear()
                        if (frameIndex < FRAME_COUNT) {
                            // Y: moving gradient; U/V: neutral grey.
                            val shift = frameIndex % H
                            for (row in 0 until H) {
                                val value = ((row + shift) * 255 / H).toByte()
                                java.util.Arrays.fill(yuv, row * W, (row + 1) * W, value)
                            }
                            java.util.Arrays.fill(yuv, W * H, yuv.size, 0x80.toByte())
                            buf.put(yuv)
                            codec.queueInputBuffer(inIdx, 0, yuv.size, ptsUs, 0)
                            frameIndex++
                            ptsUs += FRAME_US
                        } else {
                            // Empty EOS buffer on a freshly dequeued index.
                            codec.queueInputBuffer(
                                inIdx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                if (outIdx >= 0) {
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0) {
                        if (!muxerStarted) {
                            track = muxer.addTrack(codec.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                        val buf = codec.getOutputBuffer(outIdx)!!
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        muxer.writeSampleData(track, buf, info)
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    codec.releaseOutputBuffer(outIdx, false)
                }
            }
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            if (muxerStarted) runCatching { muxer.stop() }
            runCatching { muxer.release() }
        }
        assertTrue("test clip must be produced", file.length() > 0)
        return file
    }
}
