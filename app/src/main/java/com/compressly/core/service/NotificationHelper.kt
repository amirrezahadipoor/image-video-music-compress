package com.compressly.core.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import ir.siliksama.hajmino.R
import com.compressly.core.engine.model.ItemPhase
import com.compressly.core.engine.model.JobState
import com.compressly.core.engine.model.JobStatus
import com.compressly.core.engine.model.MediaType

/** Builds all notifications for running compression jobs. */
object NotificationHelper {

    const val CHANNEL_JOBS = "compression_jobs"
    const val CHANNEL_RESULTS = "compression_results"
    const val NOTIF_ID = 1001
    // BUG-9 FIX: result notification must use a DIFFERENT ID from the ongoing
    // job notification (NOTIF_ID=1001). Using the same ID caused the result
    // notification to silently overwrite the active job notification while
    // another job was still running. 1002 is reserved for results.
    const val NOTIF_RESULT_ID = 1002

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_JOBS,
                context.getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = context.getString(R.string.notif_channel_desc) }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_RESULTS,
                context.getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = context.getString(R.string.notif_channel_desc) }
        )
    }

    /** Initial "preparing" notification shown as soon as the service starts. */
    fun buildStartNotification(context: Context): Notification {
        return NotificationCompat.Builder(context, CHANNEL_JOBS)
            .setSmallIcon(R.drawable.ic_stat_compress)
            .setContentTitle(context.getString(R.string.progress_preparing))
            .setContentText(context.getString(R.string.progress_compressing))
            .setProgress(0, 0, true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    /** Live progress + pause/resume/cancel controls for a running job. */
    fun buildJobNotification(context: Context, job: JobState): Notification {
        val mediaLabel = when (job.mediaType) {
            MediaType.PHOTO -> context.getString(R.string.media_photos)
            MediaType.VIDEO -> context.getString(R.string.media_videos)
            MediaType.AUDIO -> context.getString(R.string.media_audio_files)
        }
        val percent = (job.overallFraction * 100).toInt().coerceIn(0, 100)
        val activeIndex = job.items.indexOfFirst { it.phase != ItemPhase.DONE && it.phase != ItemPhase.FAILED && it.phase != ItemPhase.CANCELLED }
            .takeIf { it >= 0 } ?: 0
        val total = job.items.size
        val text = context.getString(R.string.notif_item, activeIndex + 1, total) +
            " - " + context.getString(R.string.progress_percent, percent)

        // LIVE-DETAIL: surface the currently-processing file name so the user
        // can see what is being worked on without opening the app (heavily
        // useful for a long batch that pauses on a single large file).
        val activeName = job.items.getOrNull(activeIndex)?.fileName
            ?.takeIf { it.isNotBlank() }
        val detail = if (activeName != null) {
            context.getString(R.string.notif_file, activeName) + "\n" + text
        } else text

        val builder = NotificationCompat.Builder(context, CHANNEL_JOBS)
            .setSmallIcon(R.drawable.ic_stat_compress)
            .setContentTitle(context.getString(R.string.notif_title, mediaLabel))
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setProgress(100, percent, job.overallFraction <= 0f)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openJobIntent(context, job.jobId))

        if (job.isPaused) {
            builder.addAction(0, context.getString(R.string.action_resume), serviceIntent(context, job.jobId, CompressionJobService.ACTION_RESUME))
        } else {
            builder.addAction(0, context.getString(R.string.action_pause), serviceIntent(context, job.jobId, CompressionJobService.ACTION_PAUSE))
        }
        // Open-jobs action so users can jump straight in from the shutter.
        builder.addAction(0, context.getString(R.string.action_open), openJobIntent(context, job.jobId))
        builder.addAction(0, context.getString(R.string.action_cancel), serviceIntent(context, job.jobId, CompressionJobService.ACTION_CANCEL))
        return builder.build()
    }

    /** Shown once when a whole job reaches a terminal state. */
    fun buildResultNotification(context: Context, job: JobState): Notification {
        val doneCount = job.items.count { it.phase == ItemPhase.DONE }
        val (title, text) = when (job.status) {
            JobStatus.COMPLETED -> context.getString(R.string.notif_done_title) to
                context.resources.getQuantityString(R.plurals.notif_done_text, doneCount, doneCount)
            JobStatus.CANCELLED -> context.getString(R.string.progress_stopped_by_user) to
                context.getString(R.string.progress_stopped_by_user)
            JobStatus.PARTIAL -> context.getString(R.string.notif_partial_title) to
                context.getString(R.string.notif_partial_text, doneCount, job.items.size)
            else -> context.getString(R.string.notif_failed_title) to
                context.getString(R.string.error_generic)
        }
        return NotificationCompat.Builder(context, CHANNEL_RESULTS)
            .setSmallIcon(R.drawable.ic_stat_compress)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openJobIntent(context, job.jobId))
            .build()
    }

    private fun openJobIntent(context: Context, jobId: Long): PendingIntent {
        val intent = Intent(context, com.compressly.MainActivity::class.java).apply {
            action = CompressionJobService.ACTION_OPEN_JOB
            putExtra(CompressionJobService.EXTRA_JOB_ID, jobId)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        // Use a stable hash to avoid Int overflow when jobId is large.
        val requestCode = (jobId.hashCode() * 31 and 0x7FFFFFFF)
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun serviceIntent(context: Context, jobId: Long, action: String): PendingIntent {
        val intent = Intent(context, CompressionJobService::class.java).apply {
            this.action = action
            putExtra(CompressionJobService.EXTRA_JOB_ID, jobId)
        }
        // Stable hash that won't overflow for large jobIds.
        val code = ((jobId.hashCode() * 31 + action.hashCode()) and 0x7FFFFFFF)
        return PendingIntent.getService(
            context,
            code,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
