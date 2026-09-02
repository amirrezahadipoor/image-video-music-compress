package com.compressly

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaCodec
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.compressly.core.engine.JobControl
import com.compressly.core.engine.MediaInspector
import com.compressly.core.engine.audio.AacTranscoder
import com.compressly.core.engine.audio.AudioCompressor
import com.compressly.core.engine.model.AudioFormat
import com.compressly.core.engine.model.PhotoSettings
import com.compressly.core.engine.photo.PhotoCompressor
import java.io.File
import java.io.FileOutputStream
import java.util.Random
import kotlin.math.sin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real end-to-end smoke tests of the PHOTO and AUDIO engines on a real
 * device/emulator (no mocks). Together with [VideoTranscodeSmokeTest] this
 * means every one of the three engines is exercised by CI on a real runtime —
 * previously only the video pipeline had device coverage.
 *
 * Sources are GENERATED on-device (JPEG with noise, WAV sine sweep) so the
 * tests are hermetic and deterministic enough to assert on real size wins.
 *
 * Run: ./gradlew :app:connectedBazaarDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class PhotoAudioSmokeTest {

    // ─────────────────────────── Photo ───────────────────────────

    @Test
    fun photoEngineCompressesJpegSmallerAndKeepsDimensions() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = makeNoisyJpeg(context)
        val out = File(context.cacheDir, "smoke_photo_${System.nanoTime()}.jpg")
        try {
            val info = MediaInspector.inspect(context, Uri.fromFile(source))
            assertTrue("probe must see image dimensions", info.effectiveWidth > 0 && info.effectiveHeight > 0)

            val file = runBlocking {
                PhotoCompressor(context).compress(
                    uri = Uri.fromFile(source),
                    sourceMime = "image/jpeg",
                    // Defaults: quality 82, format SOURCE (JPEG), no resize.
                    // The source was written at quality 95 WITH noise, so a
                    // re-encode at 82 must really shrink it.
                    settings = PhotoSettings(),
                    control = JobControl(),
                    onProgress = {}
                )
            }
            try {
                assertTrue("photo output must not be empty", file.length() > 0)
                assertTrue(
                    "photo output ${file.length()} must be smaller than source ${source.length()}",
                    file.length() < source.length()
                )
                // Output must still be a decodable JPEG of the same size.
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                file.inputStream().use { BitmapFactory.decodeStream(it, null, opts) }
                assertEquals("width must be preserved", source.width, opts.outWidth)
                assertEquals("height must be preserved", source.height, opts.outHeight)
            } finally {
                file.delete()
            }
        } finally {
            source.delete()
        }
    }

    /** 640x480 JPEG at quality 95 filled with a gradient + mild noise. */
    private fun makeNoisyJpeg(context: Context): File {
        val w = 640
        val h = 480
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val rnd = Random(42) // deterministic content, reproducible test
        for (y in 0 until h) {
            for (x in 0 until w) {
                val base = (x * 255 / w + y * 128 / h).coerceIn(0, 255)
                val n = rnd.nextInt(24) - 12
                val v = (base + n).coerceIn(0, 255)
                bmp.setPixel(x, y, (0xFF shl 24) or (v shl 16) or (v shl 8) or v)
            }
        }
        val file = File(context.cacheDir, "smoke_src_${System.nanoTime()}.jpg")
        FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        bmp.recycle()
        return file
    }

    // ─────────────────────────── Audio ───────────────────────────

    @Test
    fun audioEngineMp3PathProducesSmallerValidOutput() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val wav = makeSineWav(context)
        val out = File(context.cacheDir, "smoke_audio_${System.nanoTime()}.mp3")
        try {
            val info = MediaInspector.inspect(context, Uri.fromFile(wav))

            val file = runBlocking {
                AudioCompressor(context).compress(
                    uri = Uri.fromFile(wav),
                    info = info,
                    // MP3 via the embedded LAME port, 192 kbps CBR:
                    // 2 s at 192 kbps is ~48 KB vs ~176 KB of WAV.
                    settings = com.compressly.core.engine.model.AudioSettings(
                        format = AudioFormat.MP3,
                        bitrate = 192,
                        bitrateMode = com.compressly.core.engine.model.AudioBitrateMode.CBR
                    ),
                    control = JobControl(),
                    onProgress = {}
                )
            }
            try {
                assertTrue("mp3 output must not be empty", file.length() > 0)
                assertTrue(
                    "mp3 output ${file.length()} must be smaller than wav source ${wav.length()}",
                    file.length() < wav.length()
                )
                // Must start with an ID3 tag or an MPEG frame sync.
                val head = ByteArray(64).also { buf -> file.inputStream().use { it.read(buf) } }
                val hasId3 = head.size >= 3 && head[0] == 'I'.code.toByte() &&
                    head[1] == 'D'.code.toByte() && head[2] == '3'.code.toByte()
                val hasSync = head.indices.any { i ->
                    i + 1 < head.size &&
                        head[i].toInt() and 0xFF == 0xFF &&
                        head[i + 1].toInt() and 0xE0 == 0xE0
                }
                assertTrue("mp3 must start with ID3 or an MPEG sync", hasId3 || hasSync)
            } finally {
                file.delete()
            }
        } finally {
            wav.delete()
        }
    }

    @Test
    fun audioEngineAacPathProducesValidOutput() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // The emulator/device must offer a hardware AAC encoder; otherwise the
        // production engine would skip this path too, so skip the test.
        val encoderAvailable = runCatching {
            MediaCodec.createEncoderByType("audio/mp4a-latm").also { it.release() }
        }.isSuccess
        assumeTrue("no AAC encoder on this device — skipping", encoderAvailable)

        val wav = makeSineWav(context)
        val out = File(context.cacheDir, "smoke_audio_${System.nanoTime()}.m4a")
        try {
            val file = runBlocking {
                AudioCompressor(context).compress(
                    uri = Uri.fromFile(wav),
                    info = MediaInspector.inspect(context, Uri.fromFile(wav)),
                    settings = com.compressly.core.engine.model.AudioSettings(
                        format = AudioFormat.AAC,
                        bitrate = 192,
                        bitrateMode = com.compressly.core.engine.model.AudioBitrateMode.CBR
                    ),
                    control = JobControl(),
                    onProgress = {}
                )
            }
            try {
                assertTrue("m4a output must not be empty", file.length() > 0)
                assertTrue(
                    "m4a output ${file.length()} must be smaller than wav source ${wav.length()}",
                    file.length() < wav.length()
                )
                // ISO-BMFF containers start with the ftyp box.
                val head = ByteArray(12).also { buf -> file.inputStream().use { it.read(buf) } }
                val ftyp = String(head.copyOfRange(4, 8), Charsets.US_ASCII)
                assertEquals("m4a must be an ISO-BMFF container", "ftyp", ftyp)
            } finally {
                file.delete()
            }
        } finally {
            wav.delete()
        }
    }

    /** 2 s, 44.1 kHz, mono, 16-bit PCM WAV: a 440 Hz sine with a slow swell. */
    private fun makeSineWav(context: Context): File {
        val sampleRate = 44100
        val seconds = 2
        val n = sampleRate * seconds
        val data = ByteArray(n * 2)
        for (i in 0 until n) {
            val swell = 0.6 + 0.4 * sin(Math.PI * i / n)
            val sample = (12000 * swell * sin(2.0 * Math.PI * 440.0 * i / sampleRate)).toInt()
            data[i * 2] = sample.toByte()
            data[i * 2 + 1] = (sample shr 8).toByte()
        }
        val file = File(context.cacheDir, "smoke_src_${System.nanoTime()}.wav")
        FileOutputStream(file).use { out ->
            fun wstr(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))
            fun wint(v: Int) {
                out.write(v and 0xFF); out.write((v shr 8) and 0xFF)
                out.write((v shr 16) and 0xFF); out.write((v shr 24) and 0xFF)
            }
            fun wshort(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }
            wstr("RIFF"); wint(36 + data.size); wstr("WAVE")
            wstr("fmt "); wint(16); wshort(1); wshort(1)
            wint(sampleRate); wint(sampleRate * 2); wshort(2); wshort(16)
            wstr("data"); wint(data.size)
            out.write(data)
        }
        return file
    }
}
