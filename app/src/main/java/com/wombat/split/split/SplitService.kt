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

    /** Guards [queue], [draining], [batchTotal] and [batchDone]. */
    private val queueLock = Any()
    private val queue = ArrayDeque<Long>()

    /** True while a drain loop owns the queue. Handing this flag back happens
     *  atomically with observing an empty queue, so an enqueue can never slip
     *  past a finishing drain and strand its job. */
    private var draining = false
    private var batchTotal = 0
    private var batchDone = 0

    @Volatile
    private var currentJob: Job? = null

    @Volatile
    private var currentJobId: Long? = null

    @Volatile
    private var lastStartId = 0

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
        lastStartId = startId
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
        val startWorker = synchronized(queueLock) {
            queue += id
            batchTotal++
            if (draining) {
                // A drain loop is live and will pick this up on its next turn.
                false
            } else {
                draining = true
                true
            }
        }
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
        if (startWorker) scope.launch { drainQueue() }
    }

    private fun cancelJob(id: Long) {
        val removedFromQueue = synchronized(queueLock) {
            val removed = queue.remove(id)
            // Drop it from the batch count too, so "3 of 5" doesn't keep
            // counting jobs that will never run.
            if (removed) batchTotal--
            removed
        }
        if (removedFromQueue) {
            JobRepository.update(id) { it.copy(status = SplitJob.Status.Cancelled) }
        } else if (currentJobId == id) {
            currentJob?.cancel()
        }
    }

    private suspend fun drainQueue() {
        while (true) {
            val id = synchronized(queueLock) {
                val next = queue.removeFirstOrNull()
                // Releasing the flag happens under the same lock that observed
                // the empty queue, so a concurrent enqueue either lands before
                // this and gets drained, or sees draining == false and starts
                // a fresh worker. Neither can be lost.
                if (next == null) draining = false
                next
            } ?: break

            currentJobId = id
            val inner = scope.launch { runJob(id) }
            currentJob = inner
            inner.join()
            currentJob = null
            currentJobId = null
            synchronized(queueLock) { batchDone++ }
        }

        // Capture the startId together with the stop decision. If an
        // ACTION_RUN lands after this point it bumps lastStartId, making the
        // stopSelf below a no-op so the freshly started worker survives.
        val stopId = synchronized(queueLock) {
            if (draining || queue.isNotEmpty()) return@synchronized null
            batchTotal = 0
            batchDone = 0
            lastStartId
        }
        if (stopId != null) {
            ServiceCompat.stopForeground(this@SplitService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf(stopId)
        }
    }

    private suspend fun runJob(id: Long) {
        val job = JobRepository.get(id) ?: return
        if (job.status !is SplitJob.Status.Queued) return

        JobRepository.update(id) { it.copy(status = SplitJob.Status.Scanning) }
        notify(NOTIFICATION_ID, progressNotification(job.name, indeterminate = true, jobId = id))

        try {
            val destination = DocumentFile.fromTreeUri(this, job.destinationUri.toUri())
                ?: error(getString(R.string.error_destination_inaccessible))

            val splitter = FolderSplitter(this)
            val scanned = if (job.sourceIsFile) {
                val source = DocumentFile.fromSingleUri(this, job.sourceUri.toUri())
                    ?.takeIf { it.isFile }
                    ?: error(getString(R.string.error_source_inaccessible))
                splitter.scanSingle(source)
            } else {
                val source = DocumentFile.fromTreeUri(this, job.sourceUri.toUri())
                    ?: error(getString(R.string.error_source_inaccessible))
                splitter.scan(source)
            }
            // Zip containers add per-entry overhead on top of the payload, so
            // plan against a slightly smaller cap to keep finished zips under
            // the user's limit.
            val effectiveLimit =
                if (job.zipParts) (job.limitBytes - ZIP_HEADROOM_BYTES).coerceAtLeast(1)
                else job.limitBytes
            val plan = SplitPlanner.plan(scanned.map { it.entry }, effectiveLimit)

            JobRepository.update(id) {
                it.copy(status = SplitJob.Status.Running(0, plan.totalBytes))
            }
            var lastNotified = 0L
            val result = splitter.execute(
                plan, scanned, destination, job.name, job.zipParts,
            ) { copied, total ->
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
                        chunkedFileCount = result.chunkedFileCount,
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

        // Only worth showing the position when there's actually a queue.
        val (position, total) = synchronized(queueLock) { (batchDone + 1) to batchTotal }
        val subtitle =
            if (total > 1) getString(R.string.notif_queue_position, position, total) else null

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(getString(R.string.notif_running_title, jobName))
            .setContentText(subtitle)
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
        private const val ZIP_HEADROOM_BYTES = 64L * 1024L

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
