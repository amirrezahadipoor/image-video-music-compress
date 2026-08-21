package com.compressly.core.service

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.compressly.CompresslyApp
import com.compressly.core.engine.model.JobStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps long compression jobs alive when the app is
 * backgrounded. The JobCoordinator does the actual work; this service hosts
 * the persistent notification with progress, pause/resume and cancel actions.
 */
class CompressionJobService : Service() {

    companion object {
        const val ACTION_START = "com.compressly.action.START"
        const val ACTION_PAUSE = "com.compressly.action.PAUSE"
        const val ACTION_RESUME = "com.compressly.action.RESUME"
        const val ACTION_CANCEL = "com.compressly.action.CANCEL"
        const val ACTION_OPEN_JOB = "com.compressly.action.OPEN_JOB"
        const val EXTRA_JOB_ID = "com.compressly.extra.JOB_ID"
    }

    private var collector: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val notifiedResults = mutableSetOf<Long>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val coordinator = (application as CompresslyApp).container.jobCoordinator
        val jobId = intent?.getLongExtra(EXTRA_JOB_ID, -1L)

        when (intent?.action) {
            ACTION_PAUSE -> if (jobId != null && jobId != -1L) coordinator.pause(jobId)
            ACTION_RESUME -> if (jobId != null && jobId != -1L) coordinator.resume(jobId)
            ACTION_CANCEL -> if (jobId != null && jobId != -1L) coordinator.cancel(jobId)
        }

        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(
            this,
            NotificationHelper.NOTIF_ID,
            NotificationHelper.buildStartNotification(this),
            type
        )

        if (collector == null) {
            collector = scope.launch {
                coordinator.jobs.collect { jobs -> handleJobs(jobs) }
            }
        }
        return START_NOT_STICKY
    }

    private fun handleJobs(jobs: Map<Long, com.compressly.core.engine.model.JobState>) {
        val active = jobs.values.filter {
            it.status == JobStatus.RUNNING ||
                it.status == JobStatus.PAUSED ||
                it.status == JobStatus.CANCELLING
        }
        if (active.isEmpty()) {
            // Post one-time result notifications for finished jobs.
            for (job in jobs.values) {
                if (job.status in terminalStatuses && job.jobId !in notifiedResults) {
                    notifiedResults += job.jobId
                    NotificationManagerCompat.from(this)
                        .notify((job.jobId % 100).toInt() + 1000, NotificationHelper.buildResultNotification(this, job))
                }
            }
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        NotificationManagerCompat.from(this)
            .notify(NotificationHelper.NOTIF_ID, NotificationHelper.buildJobNotification(this, active.first()))
    }

    private val terminalStatuses = setOf(
        JobStatus.COMPLETED, JobStatus.FAILED, JobStatus.CANCELLED
    )

    override fun onDestroy() {
        collector?.cancel()
        super.onDestroy()
    }
}
