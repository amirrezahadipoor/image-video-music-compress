package com.compressly.core.engine.model

import android.net.Uri

/** One file inside a compression job (a job can batch many files). */
data class InputItem(
    val itemId: Long,
    val uri: Uri,
    val displayName: String,
    val sizeBytes: Long,
    val mediaType: MediaType
)

/** Sealed wrapper so a job knows exactly which settings apply to its items. */
sealed class CompressionSettings {
    abstract val preset: CompressionPreset

    /**
     * REPLACE-ORIGINAL: when true, after a file is successfully compressed the
     * original source is deleted so the compressed copy takes its place. Only
     * ever applied when a genuinely NEW output was published (outputUri !=
     * inputUri) — the keep-original path reports the input URI itself as the
     * result and must NEVER delete the file it just handed back.
     */
    open val replaceOriginal: Boolean = false

    data class Photo(
        val settings: PhotoSettings,
        override val preset: CompressionPreset,
        override val replaceOriginal: Boolean = false
    ) : CompressionSettings()

    data class Video(
        val settings: VideoSettings,
        override val preset: CompressionPreset,
        override val replaceOriginal: Boolean = false
    ) : CompressionSettings()

    data class Audio(
        val settings: AudioSettings,
        override val preset: CompressionPreset,
        override val replaceOriginal: Boolean = false
    ) : CompressionSettings()
}

enum class JobStatus { RUNNING, PAUSED, CANCELLING, COMPLETED, FAILED, CANCELLED, PARTIAL }

enum class ItemPhase { QUEUED, PREPARING, COMPRESSING, FINALIZING, DONE, FAILED, CANCELLED }

/** Per-file progress snapshot, exposed to the UI through StateFlow. */
data class ItemState(
    val itemId: Long,
    val fileName: String,
    val phase: ItemPhase,
    /** 0..1 within the current phase. */
    val fraction: Float = 0f,
    val error: String? = null
) {
    /**
     * Phase-weighted overall fraction of this item, 0..1 — the same scale the
     * job bar uses, so the home banner and the progress screen can never
     * disagree about how far a single file has come.
     */
    val weightedFraction: Float
        get() = when (phase) {
            ItemPhase.QUEUED -> 0f
            ItemPhase.PREPARING -> 0.05f + fraction * 0.10f
            ItemPhase.COMPRESSING -> 0.15f + fraction * 0.80f
            ItemPhase.FINALIZING -> 0.95f + fraction * 0.05f
            ItemPhase.DONE -> 1f
            ItemPhase.FAILED -> 0f
            ItemPhase.CANCELLED -> 0f
        }
}

/** Whole-job progress snapshot. */
data class JobState(
    val jobId: Long,
    val mediaType: MediaType,
    val status: JobStatus,
    val isPaused: Boolean = false,
    val items: List<ItemState> = emptyList(),
    val startedAt: Long = System.currentTimeMillis(),
    val preset: CompressionPreset? = null
) {
    /**
     * Aggregated progress across all items, 0..1.
     *
     * PROGRESS-FIX: FAILED and CANCELLED items contribute 0 — they are not
     * work that was done. Previously each counted as a full 1.0, so cancelling
     * one item out of ten jumped the bar a tenth forward and distorted the
     * ETA (which extrapolates from this fraction).
     */
    val overallFraction: Float
        get() {
            if (items.isEmpty()) return 0f
            val total = items.size
            var sum = 0f
            for (item in items) {
                sum += item.weightedFraction
            }
            return (sum / total).coerceIn(0f, 1f)
        }
}

/** Outcome of compressing a single file. */
data class CompressionResult(
    val itemId: Long,
    val jobId: Long,
    val fileName: String,
    val inputUri: Uri,
    val inputSize: Long,
    val outputUri: Uri? = null,
    val outputSize: Long = 0L,
    val durationMs: Long = 0L,
    val success: Boolean,
    val error: String? = null,
    val settingsSummary: String = ""
)
