package com.magpie.filer.watch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Handles the buttons on Magpie's own notifications. */
class NotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val path = intent.getStringExtra(Notifications.EXTRA_PATH)
        val app = context.applicationContext

        // Reaching the store reads and parses a saved list, and this receiver
        // can be the thing that cold-starts the process. That is not work for
        // the main thread.
        val pending = goAsync()
        Thread {
            try {
                val store = FileStore.get(app)
                when (action) {
                    Notifications.ACTION_IGNORE -> {
                        if (path != null) {
                            val file = store.waiting.value.firstOrNull { it.path == path }
                            store.ignore(path)
                            if (file != null) Notifications.cancel(app, file)
                        }
                    }

                    WatchService.ACTION_STOP -> {
                        store.setWatching(false)
                        WatchService.stop(app)
                    }
                }
            } finally {
                pending.finish()
            }
        }.start()
    }
}
