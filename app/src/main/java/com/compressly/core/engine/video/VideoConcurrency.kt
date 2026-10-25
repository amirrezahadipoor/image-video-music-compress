package com.compressly.core.engine.video

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import com.compressly.core.engine.model.VideoCodec

/**
 * How many videos may be transcoded at the same time, for this device.
 *
 * Video batches used to be strictly sequential, because every device has a
 * finite number of codec instances and asking for one too many makes
 * `createEncoderByType` fail outright — the whole item dies. But "sequential"
 * is wrong for the other half of the market: phones whose encoders report
 * eight or more instances (and every device that falls back to a *software*
 * encoder, where each instance is an independent CPU job) can easily run five.
 *
 * The answer therefore comes from the platform instead of a guess:
 * [MediaCodecInfo.getCapabilitiesForType].`maxSupportedInstances` is the count
 * of decoder+encoder instances of that codec the device advertises. Two are
 * kept aside — one for the decoder feeding each pass and one for whatever else
 * on the phone is using the camera/codec block — and the rest is capped at
 * [com.compressly.core.service.EngineGate.MAX_CONCURRENT_ITEMS].
 */
object VideoConcurrency {

    /** Instances of the codec family to leave alone (decoder + the rest of the OS). */
    const val RESERVED_INSTANCES = 2

    /** `5 / slots` slots of the global budget per video; kept pure for tests. */
    fun weightFor(slots: Int): Int = com.compressly.core.service.EngineGate.weightForSlots(slots)

    /**
     * Pure rule: how many parallel encodes a device advertising [maxInstances]
     * can take. Devices that report nothing (or refuse the query) get 1.
     */
    fun slotsForReportedInstances(maxInstances: Int): Int {
        if (maxInstances <= 0) return 1
        return (maxInstances - RESERVED_INSTANCES)
            .coerceIn(1, com.compressly.core.service.EngineGate.MAX_CONCURRENT_ITEMS)
    }

    fun mimeFor(codec: VideoCodec): String = when (codec) {
        VideoCodec.H264 -> "video/avc"
        VideoCodec.H265 -> "video/hevc"
        VideoCodec.AV1 -> "video/av01"
    }

    /** Slots this device can run in parallel for [codec]. Never throws. */
    fun slotsFor(codec: VideoCodec): Int = runCatching {
        val mime = mimeFor(codec)
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val reported = list.codecInfos
            .filter { it.isEncoder && it.supportedTypes.contains(mime) }
            .mapNotNull { info ->
                runCatching { info.getCapabilitiesForType(mime).maxSupportedInstances }.getOrNull()
            }
            .maxOrNull() ?: 0
        slotsForReportedInstances(reported)
    }.getOrDefault(1)
}
