package com.magpie

import android.annotation.SuppressLint
import android.content.Context
import android.os.Environment
import com.magpie.api.ClaudeClient
import com.magpie.data.MagpieDb
import com.magpie.data.SettingsRepository
import com.magpie.domain.PathGuard
import com.magpie.overlay.PopupCoordinator
import com.magpie.service.Filer
import java.io.File

/** Tiny service locator — one process, one user, no DI framework needed. */
@SuppressLint("StaticFieldLeak")
object Graph {
    lateinit var context: Context
        private set

    val db: MagpieDb by lazy { MagpieDb.build(context) }
    val settings: SettingsRepository by lazy { SettingsRepository(context) }
    val claude: ClaudeClient by lazy { ClaudeClient(context, settings) }
    val filer: Filer by lazy { Filer(context) }
    val popups: PopupCoordinator by lazy { PopupCoordinator(context) }

    fun init(appContext: Context) {
        context = appContext.applicationContext
    }

    /** Primary shared storage plus the SD card root, when one is mounted. */
    fun storageRoots(): List<String> {
        val roots = mutableListOf(Environment.getExternalStorageDirectory().absolutePath)
        try {
            context.getExternalFilesDirs(null).filterNotNull().forEach { dir ->
                val marker = "/Android/data/"
                val path = dir.absolutePath
                val idx = path.indexOf(marker)
                if (idx > 0) {
                    val root = path.substring(0, idx)
                    if (root !in roots && File(root).exists()) roots.add(root)
                }
            }
        } catch (_: Exception) {
        }
        return roots
    }

    fun pathGuard(): PathGuard = PathGuard(storageRoots())
}
