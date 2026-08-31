package com.compressly.core.engine.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.compressly.core.engine.JobControl
import com.compressly.core.engine.MediaInspector
import com.compressly.core.engine.model.AudioBitrateMode
import com.compressly.core.engine.model.AudioFormat
import com.compressly.core.engine.model.AudioSettings
import com.compressly.core.util.Storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Audio engine: decodes MP3/WAV/FLAC/OGG/M4A and re-encodes to AAC (M4A)
 * via hardware MediaCodec, or to MP3 via the embedded pure-Java LAME port.
 * ID3/MP4 metadata is preserved by default (jaudiotagger), or stripped on
 * user request.
 */
class AudioCompressor(private val context: Context) {

    suspend fun compress(
        uri: Uri,
        info: com.compressly.core.engine.model.MediaInfo,
        settings: AudioSettings,
        control: JobControl,
        onProgress: (Float) -> Unit
    ): File = withContext(Dispatchers.Default) {
        val tempDir = context.cacheDir.resolve("compress").apply { mkdirs() }
        val ext = if (settings.format == AudioFormat.AAC) "m4a" else "mp3"
        // MP3-L2 FIX: use nanoTime for uniqueness. currentTimeMillis() has
        // millisecond resolution; two concurrent audio jobs starting within the
        // same millisecond would produce identical filenames and overwrite each
        // other's output. nanoTime() is monotonic and nanosecond-unique.
        val tempOut = File(tempDir, "out_${System.nanoTime()}.$ext")
        try {
            when (settings.format) {
                AudioFormat.AAC -> {
                    val ok = AacTranscoder.transcode(
                        context = context,
                        inputUri = uri,
                        outputPath = tempOut.absolutePath,
                        // Capped at the source rate: encoding above it only
                        // makes the file bigger, it cannot recover quality.
                        bitrate = AudioPlanner.targetBitrateKbps(settings.bitrate, info.audioBitrate) * 1000,
                        trimStartUs = 0,
                        trimEndUs = 0,
                        control = control,
                        onProgress = { p -> onProgress(p * 0.9f) }
                    )
                    if (!ok) throw AudioCompressionException(KEY_UNSUPPORTED)
                }
                AudioFormat.MP3 -> {
                    val kbps = AudioPlanner.targetBitrateKbps(settings.bitrate, info.audioBitrate)
                    encodeMp3(uri, tempOut, settings, kbps, info.durationMs, control) { p -> onProgress(p * 0.9f) }
                }
            }

            // AUDIO-FIX: never hand a 0-byte file onward — the publish step
            // compares against the source size and would "succeed" with it.
            if (tempOut.length() <= 0) {
                throw AudioCompressionException(KEY_ENCODE)
            }

            if (settings.preserveMetadata) {
                onProgress(0.95f)
                runCatching {
                    val tags = MediaInspector.readTags(context, uri)
                    AudioMetadataWriter.write(tempOut, tags)
                }
            }
            control.checkActive()
            onProgress(1f)
            tempOut
        } catch (t: Throwable) {
            Storage.deleteQuietly(tempOut)
            throw t
        }
    }

    companion object {
        const val KEY_UNSUPPORTED = "unsupported_format"
        const val KEY_DECODE = "decode_failed"
        const val KEY_ENCODE = "encode_failed"
    }

    // ------------------------------------------------------------------
    // MP3 path: decode to PCM, feed LAME.
    // ------------------------------------------------------------------

    private suspend fun encodeMp3(
        uri: Uri,
        outFile: File,
        settings: AudioSettings,
        bitrateKbps: Int,
        durationMs: Long,
        control: JobControl,
        onProgress: (Float) -> Unit
    ) {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            val audioIndex = findTrack(extractor, "audio/")
                ?: throw AudioCompressionException(KEY_UNSUPPORTED)
            extractor.selectTrack(audioIndex)
            val inputFormat = extractor.getTrackFormat(audioIndex)
            val inputMime = inputFormat.getString(MediaFormat.KEY_MIME)
                ?: throw AudioCompressionException(KEY_DECODE)
            val sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            // AUDIO-1 FIX: read the actual source channel count separately from
            // the LAME output channel count. LAME only supports mono/stereo, but
            // the decoder may output 5.1 (6-channel) PCM for FLAC/AAC 5.1 sources.
            // We need srcChannels to correctly downmix the PCM before feeding LAME.
            val srcChannels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceIn(1, 8)
            val channels = srcChannels.coerceAtMost(2)  // LAME output: mono or stereo

            decoder = MediaCodec.createDecoderByType(inputMime)
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()

            // Use the format's duration if the caller-provided value is 0.
            val durationUs = if (durationMs > 0) {
                durationMs * 1000
            } else {
                runCatching { inputFormat.getLong(MediaFormat.KEY_DURATION) }.getOrDefault(0L).coerceAtLeast(0L)
            }
            var pcmFloat = false
            var lastPts = 0L
            var lastReported = -1f

            FileOutputStream(outFile).use { fos ->
                val writer = Mp3Writer(
                    sampleRate = sampleRate,
                    channels = channels,
                    bitrateKbps = bitrateKbps.coerceIn(AudioPlanner.MIN_BITRATE_KBPS, AudioPlanner.MAX_BITRATE_KBPS),
                    vbr = settings.bitrateMode == AudioBitrateMode.VBR,
                    out = fos,
                    control = control
                )
                val pcmOut = ByteArray(32 * 1024)
                val info = MediaCodec.BufferInfo()
                var inputDone = false
                var decoderEos = false

                while (!decoderEos) {
                    control.checkActive()

                    if (!inputDone) {
                        val inIndex = decoder.dequeueInputBuffer(10_000)
                        if (inIndex >= 0) {
                            val buf = decoder.getInputBuffer(inIndex)!!
                            val sz = extractor.readSampleData(buf, 0)
                            if (sz < 0) {
                                decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                decoder.queueInputBuffer(inIndex, 0, sz, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }

                    var outIndex = decoder.dequeueOutputBuffer(info, 0)
                    while (outIndex >= 0 || outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            pcmFloat = com.compressly.core.engine.MediaUtil.pcmEncodingOf(

                                decoder.outputFormat

                            ) == android.media.AudioFormat.ENCODING_PCM_FLOAT
                        } else {
                            if (info.size > 0) {
                                val buf = decoder.getOutputBuffer(outIndex)!!
                                buf.position(info.offset)
                                buf.limit(info.offset + info.size)
                                if (pcmFloat || srcChannels > 2) {
                                    val outBytes = com.compressly.core.engine.MediaUtil.encoderPcmBytes(
                                        info.size, srcChannels, channels, pcmFloat
                                    )
                                    val downmixed = ByteBuffer
                                        .allocateDirect(outBytes.coerceAtLeast(2))
                                        .order(ByteOrder.LITTLE_ENDIAN)
                                    com.compressly.core.engine.MediaUtil.convertDecoderPcm(
                                        buf, info.size, downmixed, srcChannels, channels, pcmFloat
                                    )
                                    downmixed.flip()
                                    var remaining = downmixed.remaining()
                                    while (remaining > 0 && downmixed.hasRemaining()) {
                                        val chunk = minOf(remaining, pcmOut.size, downmixed.remaining())
                                        downmixed.get(pcmOut, 0, chunk)
                                        writer.writePcm(pcmOut, chunk)
                                        remaining -= chunk
                                    }
                                } else {
                                    // Decoder output buffers can exceed our 32KB
                                    // scratch; feed the encoder in chunks so no
                                    // PCM is ever dropped.
                                    var remaining = info.size
                                    while (remaining > 0 && buf.remaining() > 0) {
                                        val chunk = minOf(remaining, pcmOut.size, buf.remaining())
                                        buf.get(pcmOut, 0, chunk)
                                        writer.writePcm(pcmOut, chunk)
                                        remaining -= chunk
                                    }
                                }
                                if (info.presentationTimeUs > lastPts) {
                                    lastPts = info.presentationTimeUs
                                    if (durationUs > 0) {
                                        val p = (lastPts.toFloat() / durationUs).coerceIn(0f, 1f)
                                        if (p - lastReported >= 0.01f) {
                                            onProgress(p)
                                            lastReported = p
                                        }
                                    }
                                }
                            }
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) decoderEos = true
                            decoder.releaseOutputBuffer(outIndex, false)
                        }
                        outIndex = decoder.dequeueOutputBuffer(info, 0)
                    }
                }
                writer.finish()
                writer.close()
            }
        } finally {
            runCatching { extractor.release() }
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
        }
    }

    private fun findTrack(extractor: MediaExtractor, prefix: String): Int? =
        com.compressly.core.engine.MediaUtil.findTrack(extractor, prefix)
}

/** Expected audio-engine failure carrying a stable message key. */
class AudioCompressionException(val key: String) : Exception(key)
