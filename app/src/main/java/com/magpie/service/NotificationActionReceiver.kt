package com.magpie.service

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.magpie.Graph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class NotificationActionReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_MOVE = "com.magpie.action.MOVE"
        const val EXTRA_SOURCE = "source"
        const val EXTRA_DEST = "dest"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_MOVE) return
        val source = intent.getStringExtra(EXTRA_SOURCE) ?: return
        val dest = intent.getStringExtra(EXTRA_DEST) ?: return
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)
        val pending = goAsync()
        scope.launch {
            try {
                Graph.filer.performMove(source, dest, suggestedBy = "local", accepted = true)
                if (notificationId >= 0) {
                    context.getSystemService(NotificationManager::class.java).cancel(notificationId)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
