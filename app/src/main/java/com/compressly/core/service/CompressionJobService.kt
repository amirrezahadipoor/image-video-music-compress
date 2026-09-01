package com.compressly.core.service

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
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
 * backgrounded. Holds a WakeLock so aggressive OEM battery managers
 * (Xiaomi MIUI, Huawei EMUI, OnePlus OxygenOS, Samsung One UI) cannot kill
 * the CPU mid-compression and leave a corrupt output file.
 */
class CompressionJobService : Service() {

    companion object {
        const val ACTION_START   = "com.compressly.action.START"
        const val ACTION_PAUSE   = "com.compressly.action.PAUSE"
        const val ACTION_RESUME  = "com.compressly.action.RESUME"
        const val ACTION_CANCEL  = "com.compressly.action.CANCEL"
        const val ACTION_OPEN_JOB = "com.compressly.action.OPEN_JOB"
        const val EXTRA_JOB_ID  = "com.compressly.extra.JOB_ID"
    }

    private var collector: Job? = null
    private var isForeground = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val notifiedResults = mutableSetOf<Long>()

    /**
     * WakeLock: PARTIAL_WAKE_LOCK keeps the CPU running while the screen is
     * off. MediaCodec hardware encoders need the CPU to orchestrate buffers
     * even when GPU pipelines are involved. Without this, Xiaomi/Huawei OEM
     * layers kill the process within ~30 s of screen-off.
     *
     * Timeout of 2 h is a hard safety cap — no single compression job should
     * ever take longer; it auto-releases if we somehow forget to release it.
     */
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val coordinator = (application as CompresslyApp).container.jobCoordinator
        val jobId = intent?.getLongExtra(EXTRA_JOB_ID, -1L)

        when (intent?.action) {
            ACTION_PAUSE  -> if (jobId != null && jobId != -1L) coordinator.pause(jobId)
            ACTION_RESUME -> if (jobId != null && jobId != -1L) coordinator.resume(jobId)
            ACTION_CANCEL -> if (jobId != null && jobId != -1L) coordinator.cancel(jobId)
        }

        // Only promote on the way in. This used to run for every command, so
        // tapping pause, resume or cancel in the notification re-posted the
        // "Preparing…" placeholder with an indeterminate bar over the live
        // progress, and it stayed that way until the next progress tick.
        if (!isForeground) {
            val type = if (Build.VERSION.SDK_INT >= 34)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
            ServiceCompat.startForeground(
                this,
                NotificationHelper.NOTIF_ID,
                NotificationHelper.buildStartNotification(this),
                type
            )
            isForeground = true
        }

        // Acquire WakeLock on first START command (idempotent for subsequent calls).
        ensureWakeLock()

        if (collector == null) {
            collector = scope.launch {
                coordinator.jobs.collect { jobs -> handleJobs(jobs) }
            }
        }
        return START_NOT_STICKY
    }

    // NOTIF-DEBOUNCE: one notification per progress tick (every ~0.5 %) is a
    // binder call for nothing — the user cannot read a 0.5 % change. Refresh
    // at most every 400 ms, and immediately when the job or its paused state
    // changes (the pause/resume button swap must not lag a full interval).
    private var lastNotifAtMs = 0L
    private var lastNotifJobId = -1L
    private var lastNotifPaused = false

    private fun handleJobs(jobs: Map<Long, com.compressly.core.engine.model.JobState>) {
        val active = jobs.values.filter {
            it.status == JobStatus.RUNNING ||
                it.status == JobStatus.PAUSED ||
                it.status == JobStatus.CANCELLING
        }
        if (active.isEmpty()) {
            for (job in jobs.values) {
                if (job.status in terminalStatuses && job.jobId !in notifiedResults) {
                    notifiedResults += job.jobId
                    safeNotify(
                        NotificationHelper.NOTIF_RESULT_ID,
                        NotificationHelper.buildResultNotification(this, job)
                    )
                }
            }
            notifiedResults.retainAll(jobs.keys)
            releaseWakeLock()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            isForeground = false
            stopSelf()
            return
        }
        val job = active.first()
        // WAKE-PAUSE-FIX: a fully-paused job does no work. Holding the CPU
        // awake while the user walks away just burns battery — release the
        // lock while every active job is paused, re-acquire on resume.
        if (active.all { it.isPaused }) releaseWakeLock() else ensureWakeLock()

        val now = android.os.SystemClock.elapsedRealtime()
        val force = job.jobId != lastNotifJobId || job.isPaused != lastNotifPaused
        if (force || now - lastNotifAtMs >= 400L) {
            safeNotify(
                NotificationHelper.NOTIF_ID,
                NotificationHelper.buildJobNotification(this, job)
            )
            lastNotifAtMs = now
            lastNotifJobId = job.jobId
            lastNotifPaused = job.isPaused
        }
    }


    /**
     * NOTIFY-FIX: notify() with a rejected/never-asked POST_NOTIFICATIONS
     * permission is silently dropped on Android 13+ — and lint flags it as a
     * hard error. Guard explicitly (the permission only exists on API 33+;
     * below that it is granted at install time).
     */
    private fun safeNotify(id: Int, notification: android.app.Notification) {
        val nm = NotificationManagerCompat.from(this)
        if (Build.VERSION.SDK_INT >= 33 &&
            android.content.pm.PackageManager.PERMISSION_GRANTED !=
            androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.POST_NOTIFICATIONS
            )
        ) {
            return
        }
        runCatching { nm.notify(id, notification) }
    }

    private val terminalStatuses = setOf(
        JobStatus.COMPLETED, JobStatus.FAILED, JobStatus.CANCELLED
    )

    private fun ensureWakeLock() {
        if (wakeLock == null) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "compressly:compression"
            ).also { wl ->
                wl.acquire(2 * 60 * 60 * 1000L) // 2-hour safety cap
            }
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            wakeLock?.let { wl ->
                if (wl.isHeld) wl.release()
            }
        }
        wakeLock = null
    }

    override fun onDestroy() {
        releaseWakeLock()
        collector?.cancel()
        super.onDestroy()
    }
}
