package com.magpie.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Environment
import android.os.FileObserver
import android.os.IBinder
import com.magpie.Graph
import com.magpie.data.PendingFile
import com.magpie.domain.GroupDebouncer
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * §4: foreground service watching Download, the screenshot folders, and any
 * SD-card equivalents with FileObserver. Fires on CLOSE_WRITE and MOVED_TO
 * (never CREATE), skips in-progress markers, and re-checks size stability
 * before treating a file as complete.
 */
class WatcherService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val observers = mutableListOf<FileObserver>()
    private lateinit var debouncer: GroupDebouncer
    private val inFlight = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    companion object {
        private val IGNORED_SUFFIXES = listOf(".crdownload", ".part", ".tmp", ".download", SafeMoverTmp)
        private const val EVENT_MASK = FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO

        fun startIfConfigured(context: Context) {
            val configured = runBlocking { Graph.settings.snapshot().setupComplete }
            if (!configured) return
            try {
                context.startForegroundService(Intent(context, WatcherService::class.java))
            } catch (_: Exception) {
                // Background-start restrictions; the next app launch retries.
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        debouncer = GroupDebouncer(
            scope = scope,
            debounceMs = { runBlocking { Graph.settings.snapshot().debounceSeconds } * 1000L },
        ) { group ->
            Graph.popups.showGroup(group)
        }
        // Re-register observers whenever the watched-folder set changes.
        scope.launch {
            Graph.settings.snapshots
                .map { it.watchedFolders }
                .distinctUntilChanged()
                .collect { folders -> registerObservers(folders) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = Notifications.serviceNotification(this)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                Notifications.SERVICE_NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(Notifications.SERVICE_NOTIFICATION_ID, notification)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        observers.forEach { it.stopWatching() }
        observers.clear()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun registerObservers(watched: Set<String>) {
        observers.forEach { it.stopWatching() }
        observers.clear()
        val guard = Graph.pathGuard()
        val dirs = mutableSetOf<String>()
        for (folder in watched) {
            if (guard.isDenied(folder)) continue
            val dir = File(folder)
            if (!dir.isDirectory) continue
            dirs.add(dir.absolutePath)
            // "Recursive where needed": one level of subfolders covers the
            // apps that write into Download/<app>/.
            dir.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }
                ?.forEach { dirs.add(it.absolutePath) }
        }
        for (dir in dirs) {
            @Suppress("DEPRECATION") // File-based constructor requires API 29
            val observer = object : FileObserver(dir, EVENT_MASK) {
                override fun onEvent(event: Int, path: String?) {
                    if (path == null) return
                    if (event and EVENT_MASK == 0) return
                    onFileEvent(File(dir, path))
                }
            }
            observer.startWatching()
            observers.add(observer)
        }
    }

    private fun onFileEvent(file: File) {
        val name = file.name
        if (name.startsWith(".")) return
        if (IGNORED_SUFFIXES.any { name.endsWith(it, ignoreCase = true) }) return
        if (!inFlight.add(file.absolutePath)) return
        scope.launch {
            try {
                if (!awaitStable(file)) return@launch
                dispatchCompleted(file)
            } finally {
                inFlight.remove(file.absolutePath)
            }
        }
    }

    /** Two size reads 500 ms apart must match and be non-zero (§4 MUST). */
    private suspend fun awaitStable(file: File): Boolean {
        repeat(6) {
            val first = file.length()
            delay(500)
            val second = file.length()
            if (!file.isFile) return false
            if (first > 0 && first == second) return true
        }
        return false
    }

    private suspend fun dispatchCompleted(file: File) {
        val settings = Graph.settings.snapshot()
        val path = file.absolutePath

        if (isScreenshot(file)) {
            // §12: silent sweep — no popup, no API, no rename.
            if (settings.sweepEnabled && !path.startsWith(settings.sweepDestination + "/")) {
                Graph.filer.performMove(
                    sourcePath = path,
                    destFolder = settings.sweepDestination,
                    suggestedBy = "sweep",
                    accepted = true,
                )
            }
            return
        }

        Graph.db.pending().upsert(PendingFile(path, file.length(), System.currentTimeMillis()))
        debouncer.add(path)
    }

    private fun isScreenshot(file: File): Boolean {
        val parent = file.parentFile?.name ?: return false
        return parent.equals("Screenshots", ignoreCase = true) ||
            file.name.startsWith("Screenshot", ignoreCase = true)
    }
}

private const val SafeMoverTmp = ".magpie-tmp"
