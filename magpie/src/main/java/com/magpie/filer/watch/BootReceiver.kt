package com.magpie.filer.watch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Puts watching back on after a reboot, but only if it was on before. */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_LOCKED_BOOT_COMPLETED) return

        val store = FileStore.get(context)
        if (!store.watching.value) return

        // If Android refuses the start, the reason is kept so the app can say
        // so next time it is opened, rather than showing a toggle that lies.
        WatchService.start(context)?.let(store::report)
    }
}
