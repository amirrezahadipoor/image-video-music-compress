package com.compressly.core.engine.model

/**
 * The four-tier compression grading system shared by photos, videos and audio.
 * One component (the PresetGauge) renders these identically across all three
 * media types so the app feels like a single coherent tool.
 */
enum class CompressionPreset(val order: Int) {
    MAXIMUM_QUALITY(0),
    BALANCED(1),
    HIGH_COMPRESSION(2),
    MAXIMUM_COMPRESSION(3);

    companion object {
        val DEFAULT = BALANCED
        val ordered: List<CompressionPreset> = entries.sortedBy { it.order }

        fun fromName(name: String): CompressionPreset =
            entries.firstOrNull { it.name == name } ?: DEFAULT

        fun atOrder(order: Int): CompressionPreset =
            entries.firstOrNull { it.order == order } ?: BALANCED
    }
}
