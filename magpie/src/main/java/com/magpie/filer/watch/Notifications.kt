package com.magpie.filer.watch

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.magpie.filer.MainActivity
import com.magpie.filer.R
import com.magpie.filer.core.Formatting

/**
 * One notification per file, and one quiet permanent notice for the service.
 *
 * A file notification dismisses itself after a few minutes. That only clears
 * the notification — the file stays on the waiting list in the app, because
 * ignoring a notification is not the same as deciding what to do with a file.
 */
object Notifications {

    const val CHANNEL_FILES = "files"
    const val CHANNEL_SERVICE = "service"

    /** The service's own notification. File ids are derived from their paths. */
    const val ONGOING_ID = 1

    const val ACTION_FILE = "com.magpie.filer.action.FILE"
    const val ACTION_IGNORE = "com.magpie.filer.action.IGNORE"
    const val EXTRA_PATH = "com.magpie.filer.extra.PATH"

    /**
     * Identifies one tap. Android hands an activity its launch intent back when
     * the process is recreated, so without something to compare against, a tap
     * from days ago reopens the folder picker on a plain relaunch.
     */
    const val EXTRA_TOKEN = "com.magpie.filer.extra.TOKEN"

    private const val SELF_DISMISS_MILLIS = 4L * 60L * 1000L

    fun createChannels(context: Context) {
        val manager = NotificationManagerCompat.from(context)
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_FILES, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName(context.getString(R.string.channel_files_name))
                .setDescription(context.getString(R.string.channel_files_description))
                .setShowBadge(true)
                .build()
        )
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_SERVICE, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(context.getString(R.string.channel_service_name))
                .setDescription(context.getString(R.string.channel_service_description))
                .setShowBadge(false)
                .build()
        )
    }

    /**
     * The foreground service notice. Worded as a statement of what is running,
     * not as a warning — nothing is wrong when this is showing.
     */
    fun ongoing(context: Context, folders: Int): Notification {
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getBroadcast(
            context,
            1,
            Intent(context, NotificationActionReceiver::class.java)
                .setAction(WatchService.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = when (folders) {
            0 -> "Looking for your download folders."
            1 -> "Watching 1 folder for new files."
            else -> "Watching $folders folders for new files."
        }
        return NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_magpie)
            .setContentTitle("Magpie is on")
            .setContentText(text)
            .setContentIntent(open)
            .addAction(0, "Stop watching", stop)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    /**
     * Offer a file. Returns null when the notification was posted, or a sentence
     * explaining why it was not — the file is on the waiting list either way, so
     * a missing notification is never a lost file.
     */
    fun offer(context: Context, file: SpottedFile): String? {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) {
            return "Magpie has spotted files but cannot show notifications — they are " +
                "switched off for this app. The waiting list below still has everything."
        }

        val open = PendingIntent.getActivity(
            context,
            file.notificationId,
            Intent(context, MainActivity::class.java)
                .setAction(ACTION_FILE)
                .putExtra(EXTRA_PATH, file.path)
                .putExtra(EXTRA_TOKEN, "${file.path}@${file.spottedAt}")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val leave = PendingIntent.getBroadcast(
            context,
            file.notificationId,
            Intent(context, NotificationActionReceiver::class.java)
                .setAction(ACTION_IGNORE)
                .putExtra(EXTRA_PATH, file.path),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val detail = "${Formatting.fileSize(file.size)} · ${Formatting.typeLabel(file.name)} · ${file.source}"
        val notification = NotificationCompat.Builder(context, CHANNEL_FILES)
            .setSmallIcon(R.drawable.ic_stat_magpie)
            .setContentTitle(file.name)
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText("${file.name}\n$detail"))
            .setContentIntent(open)
            .addAction(0, "Leave it", leave)
            .setAutoCancel(true)
            // Clears itself if it is not dealt with. The file stays waiting.
            .setTimeoutAfter(SELF_DISMISS_MILLIS)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        return try {
            manager.notify(file.notificationId, notification)
            null
        } catch (e: SecurityException) {
            "Android refused to show a notification for ${file.name} " +
                "(${e.message ?: "permission denied"}). It is on the waiting list below."
        }
    }

    fun cancel(context: Context, file: SpottedFile) {
        NotificationManagerCompat.from(context).cancel(file.notificationId)
    }
}
