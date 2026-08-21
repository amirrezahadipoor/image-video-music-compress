package com.compressly.core.engine.audio

import com.compressly.core.engine.JobControl
import de.sciss.jump3r.mp3.BitStream
import de.sciss.jump3r.mp3.GainAnalysis
import de.sciss.jump3r.mp3.ID3Tag
import de.sciss.jump3r.mp3.Lame
import de.sciss.jump3r.mp3.LameGlobalFlags
import de.sciss.jump3r.mp3.MPEGMode
import de.sciss.jump3r.mp3.MPGLib
import de.sciss.jump3r.mp3.Presets
import de.sciss.jump3r.mp3.Quantize
import de.sciss.jump3r.mp3.QuantizePVT
import de.sciss.jump3r.mp3.Reservoir
import de.sciss.jump3r.mp3.Takehiro
import de.sciss.jump3r.mp3.VBRTag
import de.sciss.jump3r.mp3.VbrMode
import de.sciss.jump3r.mp3.Version
import java.io.Closeable
import java.io.IOException
import java.io.OutputStream

/**
 * Android-safe MP3 encoder built directly on jump3r's pure-Java LAME port.
 *
 * We use the low-level `de.sciss.jump3r.mp3` package instead of
 * `lowlevel.LameEncoder` because the latter depends on
 * `javax.sound.sampled.AudioFormat`, which does not exist on Android.
 * This class wires the LAME modules the same way LameEncoder does, fully
 * offline, with CBR and VBR support.
 */
class Mp3Writer(
    sampleRate: Int,
    channels: Int,
    bitrateKbps: Int,
    vbr: Boolean,
    private val out: OutputStream,
    private val control: JobControl
) : Closeable {

    private val lame: Lame
    private val gfp: LameGlobalFlags
    private val mp3Buf = ByteArray(32 * 1024)

    init {
        val ga = GainAnalysis()
        val bs = BitStream()
        val p = Presets()
        val qupvt = QuantizePVT()
        val qu = Quantize()
        val vbrTag = VBRTag()
        val ver = Version()
        val id3 = ID3Tag()
        val rv = Reservoir()
        val tak = Takehiro()
        val mpg = MPGLib()

        lame = Lame()
        lame.setModules(ga, bs, p, qupvt, qu, vbrTag, ver, id3, mpg)
        bs.setModules(ga, mpg, ver, vbrTag)
        id3.setModules(bs, ver)
        p.setModules(lame)
        qu.setModules(bs, rv, qupvt, tak)
        qupvt.setModules(tak, rv, lame.enc.psy)
        rv.setModules(bs)
        tak.setModules(qupvt)
        vbrTag.setModules(lame, bs, ver)

        gfp = lame.lame_init()
        gfp.num_channels = channels.coerceIn(1, 2)
        gfp.in_samplerate = sampleRate
        gfp.mode = if (gfp.num_channels == 1) MPEGMode.MONO else MPEGMode.JOINT_STEREO
        if (vbr) {
            gfp.VBR = VbrMode.vbr_default
            gfp.VBR_q = vbrQualityFor(bitrateKbps)
            gfp.quality = 2
        } else {
            gfp.brate = bitrateKbps.coerceIn(32, 320)
            gfp.quality = 2
        }
        id3.id3tag_init(gfp)
        // We write ID3 tags ourselves (via jaudiotagger) after encoding.
        gfp.write_id3tag_automatic = false
        gfp.findReplayGain = true

        val rc = lame.lame_init_params(gfp)
        if (rc < 0) throw IOException("MP3 encoder init failed ($rc)")
    }

    /** Feeds 16-bit signed little-endian PCM (any length) into the encoder. */
    suspend fun writePcm(pcm: ByteArray, length: Int) {
        var off = 0
        while (off < length) {
            control.checkActive()
            val chunk = minOf(32 * 1024, length - off)
            encodeChunk(pcm, off, chunk)
            off += chunk
        }
    }

    /** Flushes remaining MP3 frames and writes the LAME tag. */
    fun finish() {
        val n = lame.lame_encode_flush(gfp, mp3Buf, 0, mp3Buf.size)
        if (n > 0) out.write(mp3Buf, 0, n)
        out.flush()
    }

    override fun close() {
        runCatching { lame.lame_close(gfp) }
    }

    private fun encodeChunk(pcm: ByteArray, offset: Int, length: Int) {
        val channels = gfp.num_channels
        val samples = length / 2
        if (samples == 0) return

        // Convert little-endian 16-bit PCM to LAME's 32-bit sample format.
        val sampleBuffer = IntArray(samples)
        var si = samples
        var i = samples * 2
        while ((i -= 2) >= 0) {
            sampleBuffer[--si] =
                ((pcm[offset + i].toInt() and 0xff) shl 16) or
                    ((pcm[offset + i + 1].toInt() and 0xff) shl 24)
        }

        var p = samples
        val n = samples / channels
        val l = IntArray(n)
        val r = IntArray(n)
        if (channels == 2) {
            for (j in n - 1 downTo 0) {
                r[j] = sampleBuffer[--p]
                l[j] = sampleBuffer[--p]
            }
        } else {
            for (j in n - 1 downTo 0) {
                l[j] = sampleBuffer[--p]
            }
        }

        val res = lame.lame_encode_buffer_int(gfp, l, r, n, mp3Buf, 0, mp3Buf.size)
        if (res > 0) {
            out.write(mp3Buf, 0, res)
        } else if (res < 0) {
            throw IOException("MP3 encode error ($res)")
        }
    }

    private fun vbrQualityFor(bitrateKbps: Int): Int = when {
        bitrateKbps >= 320 -> 1
        bitrateKbps >= 256 -> 2
        bitrateKbps >= 192 -> 3
        bitrateKbps >= 128 -> 4
        else -> 6
    }
}
