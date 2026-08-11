package com.magpie.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Notification
import android.content.Context
import android.content.Intent
import com.magpie.MainActivity
import com.magpie.R

object Notifications {
    const val CHANNEL_SERVICE = "magpie_service"
    const val CHANNEL_POPUP = "magpie_popup"
    const val SERVICE_NOTIFICATION_ID = 1
    private var nextId = 100

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SERVICE, "Background watcher", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Persistent notification while Magpie watches for downloads"
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_POPUP, "New file decisions", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Heads-up fallback when the popup can't be shown"
            }
        )
    }

    fun serviceNotification(context: Context): Notification {
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("Magpie is watching for downloads")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    /**
     * §6.4 fallback: heads-up notification with the filename as title and the
     * suggested folders as action buttons. Tapping the body opens the app.
     */
    fun postFallback(context: Context, filePath: String, fileName: String, suggestions: List<String>) {
        val id = nextId++
        val open = PendingIntent.getActivity(
            context, id, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = Notification.Builder(context, CHANNEL_POPUP)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(fileName)
            .setContentText("Where should this go?")
            .setContentIntent(open)
            .setAutoCancel(true)
        suggestions.take(3).forEachIndexed { index, folder ->
            val intent = Intent(context, NotificationActionReceiver::class.java).apply {
                action = NotificationActionReceiver.ACTION_MOVE
                putExtra(NotificationActionReceiver.EXTRA_SOURCE, filePath)
                putExtra(NotificationActionReceiver.EXTRA_DEST, folder)
                putExtra(NotificationActionReceiver.EXTRA_NOTIFICATION_ID, id)
            }
            val pending = PendingIntent.getBroadcast(
                context, id * 10 + index, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(
                Notification.Action.Builder(null, folder.substringAfterLast('/'), pending).build()
            )
        }
        context.getSystemService(NotificationManager::class.java).notify(id, builder.build())
    }
}
