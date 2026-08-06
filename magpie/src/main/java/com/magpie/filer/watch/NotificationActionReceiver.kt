package com.magpie.filer.watch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Handles the buttons on Magpie's own notifications. */
class NotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val store = FileStore.get(context)
        when (intent.action) {
            Notifications.ACTION_IGNORE -> {
                val path = intent.getStringExtra(Notifications.EXTRA_PATH) ?: return
                val file = store.waiting.value.firstOrNull { it.path == path }
                store.ignore(path)
                if (file != null) Notifications.cancel(context, file)
            }

            WatchService.ACTION_STOP -> {
                store.setWatching(false)
                WatchService.stop(context)
            }
        }
    }
}
