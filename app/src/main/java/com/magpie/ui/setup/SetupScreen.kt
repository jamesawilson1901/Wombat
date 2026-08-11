package com.magpie.ui.setup

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.magpie.Graph
import kotlinx.coroutines.launch

/**
 * §3 first-run wizard: walks the permissions one at a time, re-checks each
 * with the real API call, and won't advance until granted.
 */
@Composable
fun SetupScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var refresh by remember { mutableIntStateOf(0) }

    // Re-check every permission when the user comes back from Settings.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val settings by Graph.settings.snapshots.collectAsState(initial = null)
    val scope = rememberCoroutineScope()

    val hasStorage = remember(refresh) { Environment.isExternalStorageManager() }
    val hasOverlay = remember(refresh) { Settings.canDrawOverlays(context) }
    val hasNotifications = remember(refresh) {
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }
    val hasBattery = remember(refresh) {
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .isIgnoringBatteryOptimizations(context.packageName)
    }
    val colorOsConfirmed = settings?.colorOsConfirmed == true

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refresh++ }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {
        Text("Set up Magpie", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(4.dp))
        Text(
            "Magpie needs a few permissions before it can file your downloads. One at a time:",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(16.dp))

        StepCard(
            index = 1,
            title = "All files access",
            body = "Magpie moves files between folders, so it needs full file access.",
            done = hasStorage,
            enabled = true,
            actionLabel = "Grant file access",
        ) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    .setData(Uri.parse("package:${context.packageName}"))
            )
        }

        StepCard(
            index = 2,
            title = "Display over other apps",
            body = "The filing popup appears over whatever app you're using.",
            done = hasOverlay,
            enabled = hasStorage,
            actionLabel = "Allow overlay",
        ) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
            )
        }

        StepCard(
            index = 3,
            title = "Notifications",
            body = "Used for the quiet background-watcher notice and as a fallback when the popup can't be shown.",
            done = hasNotifications,
            enabled = hasStorage && hasOverlay,
            actionLabel = "Allow notifications",
        ) {
            if (Build.VERSION.SDK_INT >= 33) {
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        StepCard(
            index = 4,
            title = "Ignore battery optimisation",
            body = "Without this, the watcher gets killed and downloads go unnoticed.",
            done = hasBattery,
            enabled = hasStorage && hasOverlay && hasNotifications,
            actionLabel = "Exempt from battery optimisation",
        ) {
            context.startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:${context.packageName}"))
            )
        }

        // ColorOS-specific: nothing to detect (§3) — plain language, user confirms.
        Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text("5. ColorOS: keep Magpie alive", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    "ColorOS kills background apps aggressively. In the Phone Manager / Security app, please: \n\n" +
                        "• add Magpie to Startup Manager (allow auto-start)\n" +
                        "• add Magpie to the floating window allowlist\n\n" +
                        "Without this the app silently stops working. Magpie can't detect these settings — " +
                        "tap the button, make the two changes, then confirm.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                        )
                    },
                    enabled = hasStorage && hasOverlay && hasNotifications && hasBattery,
                ) { Text("Open app settings") }
                Spacer(Modifier.height(6.dp))
                Button(
                    onClick = { scope.launch { Graph.settings.setColorOsConfirmed(true) } },
                    enabled = hasStorage && hasOverlay && hasNotifications && hasBattery && !colorOsConfirmed,
                ) { Text(if (colorOsConfirmed) "Confirmed" else "I've done both") }
            }
        }

        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onDone,
            enabled = hasStorage && hasOverlay && hasNotifications && hasBattery && colorOsConfirmed,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text("Finish setup") }
    }
}

@Composable
private fun StepCard(
    index: Int,
    title: String,
    body: String,
    done: Boolean,
    enabled: Boolean,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "$index. $title ${if (done) "✓" else ""}",
                style = MaterialTheme.typography.titleMedium,
                color = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(6.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium)
            if (!done) {
                Spacer(Modifier.height(10.dp))
                Button(onClick = onAction, enabled = enabled) { Text(actionLabel) }
            }
        }
    }
}
