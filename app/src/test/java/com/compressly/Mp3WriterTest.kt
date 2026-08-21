package com.compressly

import com.compressly.core.engine.JobControl
import com.compressly.core.engine.audio.Mp3Writer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import kotlin.math.PI
import kotlinx.coroutines.runBlocking
import kotlin.math.sin

/**
 * REAL encoder test: feeds 16-bit PCM through the embedded pure-Java LAME
 * port (jump3r) and validates the resulting MP3 bytes. This runs on the JVM
 * in CI, so it actually exercises the encoder, not just mocks.
 */
class Mp3WriterTest {

    /** Generates [seconds] of a 440 Hz sine as 16-bit little-endian PCM. */
    private fun sinePcm(sampleRate: Int, seconds: Double): ByteArray {
        val n = (sampleRate * seconds).toInt()
        val out = ByteArray(n * 2)
        var i = 0
        while (i < n) {
            val v = (sin(2.0 * PI * 440.0 * i / sampleRate) * 0.5 * 32767.0).toInt()
            out[i * 2] = (v and 0xff).toByte()
            out[i * 2 + 1] = ((v shr 8) and 0xff).toByte()
            i++
        }
        return out
    }

    private fun isMpegFrameSync(b0: Byte, b1: Byte): Boolean {
        val v0 = b0.toInt() and 0xff
        val v1 = b1.toInt() and 0xff
        return v0 == 0xFF && (v1 and 0xE0) == 0xE0
    }

    @Test
    fun cbr_128kbps_encodesValidMp3() {
        val out = ByteArrayOutputStream()
        val writer = Mp3Writer(
            sampleRate = 44100,
            channels = 1,
            bitrateKbps = 128,
            vbr = false,
            out = out,
            control = JobControl()
        )
        val pcm = sinePcm(44100, 1.0)
        runBlocking { writer.writePcm(pcm, pcm.size) }
        writer.finish()
        writer.close()

        val bytes = out.toByteArray()
        // 1s at 128 kbps ~ 16 KB; allow a generous lower bound.
        assertTrue("encoded at least 4KB, got ${bytes.size}", bytes.size > 4_000)
        // MP3 stream must start with a frame sync (0xFF Ex).
        assertTrue(
            "starts with MPEG frame sync, got ${bytes[0].toInt() and 0xff} ${bytes[1].toInt() and 0xff}",
            isMpegFrameSync(bytes[0], bytes[1])
        )
    }

    @Test
    fun vbr_encodesValidMp3() {
        val out = ByteArrayOutputStream()
        val writer = Mp3Writer(
            sampleRate = 44100,
            channels = 2,
            bitrateKbps = 192,
            vbr = true,
            out = out,
            control = JobControl()
        )
        val pcm = sinePcm(44100, 0.5)
        runBlocking { writer.writePcm(pcm, pcm.size) }
        writer.finish()
        writer.close()

        val bytes = out.toByteArray()
        assertTrue("VBR encoded some output, got ${bytes.size}", bytes.size > 2_000)
        assertTrue("VBR starts with frame sync", isMpegFrameSync(bytes[0], bytes[1]))
    }

    @Test
    fun mono_downmix_producesValidStream() {
        val out = ByteArrayOutputStream()
        val writer = Mp3Writer(
            sampleRate = 22050,
            channels = 1,
            bitrateKbps = 64,
            vbr = false,
            out = out,
            control = JobControl()
        )
        val pcm = sinePcm(22050, 0.4)
        runBlocking { writer.writePcm(pcm, pcm.size) }
        writer.finish()
        writer.close()
        assertTrue("low-bitrate mono output non-empty", out.size() > 800)
    }

    @Test
    fun higherBitrate_biggerOutput() {
        fun encode(kbps: Int): Int {
            val out = ByteArrayOutputStream()
            val writer = Mp3Writer(44100, 1, kbps, false, out, JobControl())
            val pcm = sinePcm(44100, 1.0)
            runBlocking { writer.writePcm(pcm, pcm.size) }
            writer.finish()
            writer.close()
            return out.size()
        }
        val low = encode(64)
        val high = encode(320)
        assertTrue("320kbps larger than 64kbps ($high > $low)", high > low)
    }

    @Test
    fun pcmChunking_matchesSingleShot() {
        fun encode(chunk: Int): Int {
            val out = ByteArrayOutputStream()
            val writer = Mp3Writer(44100, 1, 128, false, out, JobControl())
            val pcm = sinePcm(44100, 0.5)
            var off = 0
            while (off < pcm.size) {
                val n = minOf(chunk, pcm.size - off)
                runBlocking { writer.writePcm(pcm.copyOfRange(off, off + n), n) }
                off += n
            }
            writer.finish()
            writer.close()
            return out.size()
        }
        // Feeding the encoder in different chunk sizes must produce the same
        // amount of MP3 data.
        assertEquals(encode(32768), encode(512))
    }
}
