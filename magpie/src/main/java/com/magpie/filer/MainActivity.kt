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
        // Clear it so a rotation does not reopen the picker.
        intent.removeExtra(Notifications.EXTRA_PATH)
        viewModel.fileByPath(path)
    }
}
