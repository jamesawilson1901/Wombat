package com.wombat.split.split

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.wombat.split.R
import com.wombat.split.jobs.JobRepository
import com.wombat.split.jobs.SplitJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that runs split jobs one at a time, so they survive the
 * app going to the background. Shows a progress notification with a working
 * Cancel action; queued and running jobs can also be cancelled from the UI.
 */
class SplitService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = ArrayDeque<Long>()
    private var worker: Job? = null
    private var currentJob: Job? = null
    private var currentJobId: Long? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        notificationManager.createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_RUN -> {
                val id = intent.getLongExtra(EXTRA_JOB_ID, -1L)
                if (id >= 0) enqueue(id)
            }
            ACTION_CANCEL -> {
                val id = intent.getLongExtra(EXTRA_JOB_ID, -1L)
                if (id >= 0) cancelJob(id)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun enqueue(id: Long) {
        val job = JobRepository.get(id) ?: return
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            progressNotification(job.name, indeterminate = true, jobId = id),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
        synchronized(queue) { queue += id }
        if (worker?.isActive != true) {
            worker = scope.launch { drainQueue() }
        }
    }

    private fun cancelJob(id: Long) {
        val removedFromQueue = synchronized(queue) { queue.remove(id) }
        if (removedFromQueue) {
            JobRepository.update(id) { it.copy(status = SplitJob.Status.Cancelled) }
        } else if (currentJobId == id) {
            currentJob?.cancel()
        }
    }

    private suspend fun drainQueue() {
        while (true) {
            val id = synchronized(queue) { queue.removeFirstOrNull() } ?: break
            currentJobId = id
            val inner = scope.launch { runJob(id) }
            currentJob = inner
            inner.join()
            currentJob = null
            currentJobId = null
        }
        ServiceCompat.stopForeground(this@SplitService, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private suspend fun runJob(id: Long) {
        val job = JobRepository.get(id) ?: return
        if (job.status !is SplitJob.Status.Queued) return

        JobRepository.update(id) { it.copy(status = SplitJob.Status.Scanning) }
        notify(NOTIFICATION_ID, progressNotification(job.name, indeterminate = true, jobId = id))

        try {
            val source = DocumentFile.fromTreeUri(this, job.sourceUri.toUri())
                ?: error(getString(R.string.error_source_inaccessible))
            val destination = DocumentFile.fromTreeUri(this, job.destinationUri.toUri())
                ?: error(getString(R.string.error_destination_inaccessible))

            val splitter = FolderSplitter(this)
            val scanned = splitter.scan(source)
            val plan = SplitPlanner.plan(scanned.map { it.entry }, job.limitBytes)

            JobRepository.update(id) {
                it.copy(status = SplitJob.Status.Running(0, plan.totalBytes))
            }
            var lastNotified = 0L
            val result = splitter.execute(plan, scanned, destination, job.name) { copied, total ->
                JobRepository.update(id) {
                    it.copy(status = SplitJob.Status.Running(copied, total))
                }
                val now = SystemClock.elapsedRealtime()
                if (now - lastNotified > 500) {
                    lastNotified = now
                    notify(
                        NOTIFICATION_ID,
                        progressNotification(job.name, copied, total, jobId = id),
                    )
                }
            }

            JobRepository.update(id) {
                it.copy(
                    status = SplitJob.Status.Done(
                        partCount = result.partCount,
                        fileCount = result.fileCount,
                        totalBytes = result.totalBytes,
                        oversizedCount = result.oversizedCount,
                    )
                )
            }
            notify(
                completionId(id),
                doneNotification(
                    getString(R.string.notif_done_title, job.name),
                    getString(R.string.notif_done_text, result.partCount),
                ),
            )
        } catch (e: CancellationException) {
            JobRepository.update(id) { it.copy(status = SplitJob.Status.Cancelled) }
            notify(
                completionId(id),
                doneNotification(
                    getString(R.string.notif_cancelled_title, job.name),
                    "",
                ),
            )
            throw e
        } catch (e: Exception) {
            val message = e.message ?: getString(R.string.error_generic)
            JobRepository.update(id) { it.copy(status = SplitJob.Status.Failed(message)) }
            notify(
                completionId(id),
                doneNotification(getString(R.string.notif_failed_title, job.name), message),
            )
        }
    }

    private fun progressNotification(
        jobName: String,
        bytesCopied: Long = 0,
        bytesTotal: Long = 0,
        indeterminate: Boolean = false,
        jobId: Long,
    ): android.app.Notification {
        val cancelIntent = Intent(this, SplitService::class.java)
            .setAction(ACTION_CANCEL)
            .putExtra(EXTRA_JOB_ID, jobId)
        val cancelPending = PendingIntent.getService(
            this,
            jobId.toInt(),
            cancelIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val percent =
            if (bytesTotal > 0) ((bytesCopied * 100) / bytesTotal).toInt() else 0
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(getString(R.string.notif_running_title, jobName))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent, indeterminate)
            .addAction(0, getString(R.string.cancel), cancelPending)
            .build()
    }

    private fun doneNotification(title: String, text: String): android.app.Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .build()

    private fun notify(id: Int, notification: android.app.Notification) {
        try {
            notificationManager.notify(id, notification)
        } catch (_: SecurityException) {
            // Notifications denied — the job keeps running regardless.
        }
    }

    private val notificationManager: NotificationManager
        get() = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

    private fun completionId(jobId: Long): Int = (jobId + 1000).toInt()

    companion object {
        private const val ACTION_RUN = "com.wombat.split.action.RUN"
        private const val ACTION_CANCEL = "com.wombat.split.action.CANCEL"
        private const val EXTRA_JOB_ID = "job_id"
        private const val CHANNEL_ID = "split_jobs"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context, jobId: Long) {
            val intent = Intent(context, SplitService::class.java)
                .setAction(ACTION_RUN)
                .putExtra(EXTRA_JOB_ID, jobId)
            ContextCompat.startForegroundService(context, intent)
        }

        fun cancel(context: Context, jobId: Long) {
            val intent = Intent(context, SplitService::class.java)
                .setAction(ACTION_CANCEL)
                .putExtra(EXTRA_JOB_ID, jobId)
            context.startService(intent)
        }
    }
}
