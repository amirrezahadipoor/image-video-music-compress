package com.compressly.core.engine.video

import android.media.MediaCodecList

/** Checks which encoders exist on this device (fully offline). */
object CodecSupport {

    fun hasEncoder(mime: String): Boolean {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        for (ci in list.codecInfos) {
            if (ci.isEncoder && ci.supportedTypes.contains(mime)) return true
        }
        return false
    }
}
