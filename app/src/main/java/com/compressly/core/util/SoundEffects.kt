package com.compressly.core.util

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Tiny synthesized sound effects (zero asset weight, fully offline).
 * Generates PCM in memory and plays it on a background thread so the UI
 * never blocks. All failures are swallowed: sound is a bonus, never a crash.
 */
object SoundEffects {

    @Volatile
    var enabled: Boolean = true

    enum class Type { CLICK, SUCCESS, ERROR }

    private const val SAMPLE_RATE = 22050

    fun play(type: Type) {
        if (!enabled) return
        try {
            val pcm = buildPcm(type)
            val durationMs = pcm.second
            val data = pcm.first
            Thread {
                runCatching {
                    val attrs = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                    val fmt = AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                    val track = AudioTrack.Builder()
                        .setAudioAttributes(attrs)
                        .setAudioFormat(fmt)
                        .setBufferSizeInBytes(data.size)
                        .setTransferMode(AudioTrack.MODE_STATIC)
                        .build()
                    track.write(data, 0, data.size)
                    track.setVolume(0.85f)
                    track.play()
                    Thread.sleep(durationMs.toLong() + 60)
                    track.stop()
                    track.release()
                }
            }.start()
        } catch (_: Throwable) {
            // Never let sound break the app.
        }
    }

    private fun buildPcm(type: Type): Pair<ByteArray, Int> {
        return when (type) {
            Type.CLICK -> concat(
                tone(1568.0, 45, 0.30), // bright short blip
                silence(20)
            )
            Type.SUCCESS -> concat(
                tone(880.0, 110, 0.40),
                tone(1174.66, 170, 0.40) // pleasant rising chime
            )
            Type.ERROR -> concat(tone(196.0, 300, 0.35)) // soft low buzz
        }
    }

    private fun tone(freq: Double, durationMs: Int, volume: Double): ByteArray {
        val n = SAMPLE_RATE * durationMs / 1000
        val data = ByteArray(n * 2)
        val w = 2.0 * PI * freq
        for (i in 0 until n) {
            val t = i.toDouble() / SAMPLE_RATE
            val env = exp(-4.0 * t / (durationMs / 1000.0)) // natural decay
            val v = sin(w * t) * volume * env * 32767.0
            val s = v.toInt().coerceIn(-32768, 32767)
            data[i * 2] = (s and 0xff).toByte()
            data[i * 2 + 1] = ((s shr 8) and 0xff).toByte()
        }
        return data
    }

    private fun silence(ms: Int): ByteArray = ByteArray(SAMPLE_RATE * ms / 1000 * 2)

    private fun concat(vararg parts: ByteArray): Pair<ByteArray, Int> {
        var total = 0
        for (p in parts) total += p.size
        val out = ByteArray(total)
        var pos = 0
        for (p in parts) {
            System.arraycopy(p, 0, out, pos, p.size)
            pos += p.size
        }
        return out to (total * 1000 / (SAMPLE_RATE * 2))
    }
}
