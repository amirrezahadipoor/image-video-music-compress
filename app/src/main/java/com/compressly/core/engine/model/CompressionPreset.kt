package com.compressly.core.engine.model

/**
 * The compression grading system shared by photos, videos and audio.
 *
 * SMART is the default: an adaptive mode that automatically balances size and
 * quality, targeting the strongest compression that still keeps perceptual
 * quality above ~70%. The four manual tiers render as a shared 4-step gauge.
 */
enum class CompressionPreset(val order: Int) {
    MAXIMUM_QUALITY(0),
    BALANCED(1),
    HIGH_COMPRESSION(2),
    MAXIMUM_COMPRESSION(3),
    SMART(-1);

    companion object {
        val DEFAULT = SMART

        /** The four manual tiers, for the 4-step gauge. */
        val ordered: List<CompressionPreset> =
            listOf(MAXIMUM_QUALITY, BALANCED, HIGH_COMPRESSION, MAXIMUM_COMPRESSION)

        /** SMART first, then the manual tiers (used by pickers). */
        val all: List<CompressionPreset> = listOf(SMART) + ordered

        fun fromName(name: String): CompressionPreset =
            entries.firstOrNull { it.name == name } ?: DEFAULT

        fun atOrder(order: Int): CompressionPreset =
            ordered.firstOrNull { it.order == order } ?: BALANCED
    }
}
