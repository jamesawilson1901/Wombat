package com.magpie

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.magpie.data.PresetFolder
import com.magpie.service.WatcherService
import com.magpie.ui.home.HomeScreen
import com.magpie.ui.log.LogScreen
import com.magpie.ui.presets.PresetWizardScreen
import com.magpie.ui.settings.SettingsScreen
import com.magpie.ui.setup.SetupScreen
import com.magpie.ui.theme.MagpieTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    companion object {
        const val ACTION_PICK_DESTINATION = "com.magpie.action.PICK_DESTINATION"
        const val EXTRA_FILES = "files"

        /** "primary:Download" → /storage/emulated/0/Download; "1234-ABCD:x" → /storage/1234-ABCD/x */
        fun treeUriToPath(uri: Uri): String? = try {
            val docId = DocumentsContract.getTreeDocumentId(uri)
            val parts = docId.split(":", limit = 2)
            val root = if (parts[0] == "primary") {
                Environment.getExternalStorageDirectory().absolutePath
            } else {
                "/storage/${parts[0]}"
            }
            if (parts.size > 1 && parts[1].isNotEmpty()) "$root/${parts[1]}" else root
        } catch (_: Exception) {
            null
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var pickPendingFiles: List<String> = emptyList()

    // "Somewhere else…" — the system folder picker (SAF). A folder picked here
    // that isn't a preset yet is added automatically with an empty description
    // (§7.2), then the ticked files move there.
    private val pickFolder = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        val files = pickPendingFiles
        pickPendingFiles = emptyList()
        if (uri == null || files.isEmpty()) return@registerForActivityResult
        val path = treeUriToPath(uri) ?: return@registerForActivityResult
        scope.launch {
            if (Graph.db.presets().byPath(path) == null) {
                Graph.db.presets().upsert(
                    PresetFolder(path = path, name = path.substringAfterLast('/'))
                )
            }
            files.forEach { source ->
                Graph.filer.performMove(source, path, suggestedBy = "manual", accepted = false)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handlePickIntent(intent)

        setContent {
            MagpieTheme {
                val settings by Graph.settings.snapshots.collectAsState(initial = null)
                val nav = rememberNavController()
                val s = settings ?: return@MagpieTheme

                NavHost(
                    navController = nav,
                    startDestination = if (!s.setupComplete) "setup" else if (!s.presetWizardDone) "presets" else "home",
                ) {
                    composable("setup") {
                        SetupScreen(onDone = {
                            scope.launch {
                                Graph.settings.setSetupComplete(true)
                                WatcherService.startIfConfigured(this@MainActivity)
                                nav.navigate(if (s.presetWizardDone) "home" else "presets") {
                                    popUpTo("setup") { inclusive = true }
                                }
                            }
                        })
                    }
                    composable("presets") {
                        PresetWizardScreen(onDone = {
                            scope.launch {
                                Graph.settings.setPresetWizardDone(true)
                                nav.navigate("home") { popUpTo("presets") { inclusive = true } }
                            }
                        })
                    }
                    composable("home") {
                        HomeScreen(
                            onOpenLog = { nav.navigate("log") },
                            onOpenSettings = { nav.navigate("settings") },
                            onRerunWizard = { nav.navigate("presets") },
                        )
                    }
                    composable("log") { LogScreen(onBack = { nav.popBackStack() }) }
                    composable("settings") { SettingsScreen(onBack = { nav.popBackStack() }) }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handlePickIntent(intent)
    }

    private fun handlePickIntent(intent: Intent?) {
        if (intent?.action == ACTION_PICK_DESTINATION) {
            pickPendingFiles = intent.getStringArrayExtra(EXTRA_FILES)?.toList() ?: emptyList()
            if (pickPendingFiles.isNotEmpty()) pickFolder.launch(null)
        }
    }
}
