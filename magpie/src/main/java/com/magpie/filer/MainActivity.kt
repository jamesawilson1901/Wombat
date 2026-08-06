package com.magpie.filer

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.magpie.filer.ui.MagpieScreen
import com.magpie.filer.ui.MainViewModel
import com.magpie.filer.ui.theme.MagpieTheme
import com.magpie.filer.watch.Notifications

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MagpieTheme {
                MagpieScreen(viewModel)
            }
        }
        openFileFrom(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openFileFrom(intent)
    }

    override fun onResume() {
        super.onResume()
        // All-files access and notification permission can both be changed
        // outside the app, and the service can be killed while it is away.
        viewModel.refresh()
    }

    /** A tapped notification means "file this one". */
    private fun openFileFrom(intent: Intent?) {
        if (intent?.action != Notifications.ACTION_FILE) return
        val path = intent.getStringExtra(Notifications.EXTRA_PATH) ?: return
        val token = intent.getStringExtra(Notifications.EXTRA_TOKEN) ?: path

        // Clearing the extra covers a rotation, but Android keeps its own copy
        // of the launch intent and hands it back after process death — so the
        // tap is also recorded, and a repeat of the same one is ignored.
        intent.removeExtra(Notifications.EXTRA_PATH)
        if (!viewModel.claimNotification(token)) return

        viewModel.fileByPath(path)
    }
}
