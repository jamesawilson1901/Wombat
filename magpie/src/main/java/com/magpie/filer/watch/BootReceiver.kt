package com.magpie.filer.watch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Puts watching back on after a reboot, but only if it was on. */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_LOCKED_BOOT_COMPLETED) return

        val app = context.applicationContext
        val pending = goAsync()
        Thread {
            try {
                // Loading the saved lists touches the disk, and boot is exactly
                // when the main thread is busiest.
                val store = FileStore.get(app)
                if (!store.watching.value) return@Thread

                // If Android refuses the start, the reason is kept so the app
                // can say so next time it is opened, rather than showing a
                // toggle that lies.
                WatchService.start(app)?.let(store::report)
            } finally {
                pending.finish()
            }
        }.start()
    }
}
