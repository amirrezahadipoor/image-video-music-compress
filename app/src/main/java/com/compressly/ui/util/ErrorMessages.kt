package com.compressly.ui.util

import androidx.annotation.StringRes
import ir.siliksama.hajmino.R

/** Maps stable engine error keys to plain-language UI strings. */
object ErrorMessages {

    @StringRes
    fun forKey(key: String?): Int = when (key) {
        "heic_unsupported" -> R.string.error_heic_unsupported
        // PhotoCompressor emits this exact key on API 26-27 (minSdk) when a
        // HEIC file is picked — without the mapping it fell through to the
        // generic error and the helpful "needs Android 9+" message was lost.
        "heic_old_device" -> R.string.error_heic_unsupported
        "corrupt" -> R.string.error_corrupt
        "decode_failed" -> R.string.error_decode_failed
        "no_video" -> R.string.error_unsupported_format
        "no_encoder" -> R.string.error_unsupported_codec
        "encode_failed" -> R.string.error_encode_failed
        "unsupported_format" -> R.string.error_unsupported_format
        "file_not_found" -> R.string.error_file_not_found
        "file_empty" -> R.string.error_file_empty
        "output" -> R.string.error_output
        "metadata_write" -> R.string.error_metadata_write
        "interrupted" -> R.string.error_interrupted
        "cancelled" -> R.string.error_cancelled
        "no_space" -> R.string.error_no_space
        else -> R.string.error_generic
    }
}
