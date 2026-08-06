package com.magpie.filer.watch

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.FileObserver
import android.os.IBinder
import java.util.concurrent.ConcurrentHashMap
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.magpie.filer.core.Naming
import com.magpie.filer.core.StabilityTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * Watches the download folders and offers each file once it has finished
 * arriving.
 *
 * Two mechanisms, because neither is reliable alone. A [FileObserver] on each
 * folder is fast but misses events under scoped storage, so it only means "look
 * again soon"; a periodic sweep is the backstop that actually finds things.
 *
 * Nothing here touches a user's file. The worst this service can do is fail to
 * notice something, in which case the download is sitting in Downloads exactly
 * where it would have been if Magpie were not installed.
 */
class WatchService : Service() {

    companion object {
        const val ACTION_STOP = "com.magpie.filer.action.STOP_WATCHING"

        /** How often to look while something is mid-download. */
        private const val BUSY_TICK_MILLIS = 2_500L

        /** How often to look otherwise. */
        private const val IDLE_TICK_MILLIS = 15_000L

        /** How long a FileObserver event keeps the sweep in a hurry. */
        private const val HURRY_MILLIS = 20_000L

        /** Re-reading mounted volumes is a binder call; it does not need doing often. */
        private const val ROOT_REFRESH_MILLIS = 15_000L

        /** Most notifications one sweep will post. The rest still go on the list. */
        private const val NOTIFICATION_BURST_LIMIT = 5

        private const val OBSERVER_MASK =
            FileObserver.CREATE or FileObserver.CLOSE_WRITE or
                FileObserver.MOVED_TO or FileObserver.MOVED_FROM or FileObserver.DELETE

        @Volatile
        var isRunning: Boolean = false
            private set

        /**
         * Ask the service to start. Returns null on success, or a sentence
         * explaining why Android refused — the caller shows it to the user
         * rather than leaving a toggle that looks on but is not.
         */
        fun start(context: Context): String? {
            val intent = Intent(context, WatchService::class.java)
            return try {
                ContextCompat.startForegroundService(context, intent)
                null
            } catch (e: IllegalStateException) {
                // Includes ForegroundServiceStartNotAllowedException on API 31+.
                "Android would not let Magpie start watching in the background " +
                    "(${e.message ?: e.javaClass.simpleName}). Open Magpie and turn watching on again."
            } catch (e: SecurityException) {
                "Android refused Magpie the permission it needs to watch in the background " +
                    "(${e.message ?: "security exception"})."
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WatchService::class.java))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tracker = StabilityTracker()
    // Written from the watch coroutine, cleared from the main thread in onDestroy.
    private val observers = ConcurrentHashMap<String, FileObserver>()

    private lateinit var store: FileStore

    @Volatile
    private var hurryUntil = 0L

    // Written by the watch coroutine, read by onStartCommand on the main thread.
    @Volatile
    private var roots: List<WatchRoot> = emptyList()

    @Volatile
    private var postedFolderCount = -1

    private var rootsCheckedAt = 0L
    private var lastPruneAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        store = FileStore.get(this)
        isRunning = true
        goForeground()
        scope.launch { watchLoop() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            store.setWatching(false)
            stopSelf()
            return START_NOT_STICKY
        }
        goForeground()
        return START_STICKY
    }

    override fun onDestroy() {
        for (observer in observers.values) observer.stopWatching()
        observers.clear()
        scope.cancel()
        isRunning = false
        super.onDestroy()
    }

    private fun goForeground() {
        val count = roots.size
        if (count == postedFolderCount) return
        postedFolderCount = count
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // dataSync is capped at six hours a day from Android 15; specialUse
            // is not, and being stopped mid-day would break the whole point.
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }
        ServiceCompat.startForeground(
            this,
            Notifications.ONGOING_ID,
            Notifications.ongoing(this, count),
            type,
        )
    }

    private suspend fun watchLoop() {
        while (scope.isActive) {
            val now = System.currentTimeMillis()
            refreshRoots(now)
            sweep(now)
            if (now - lastPruneAt > IDLE_TICK_MILLIS * 4) {
                lastPruneAt = now
                store.pruneMissing()
            }
            val busy = now < hurryUntil || tracker.pending > 0
            delay(if (busy) BUSY_TICK_MILLIS else IDLE_TICK_MILLIS)
        }
    }

    private fun refreshRoots(now: Long) {
        if (roots.isNotEmpty() && now - rootsCheckedAt < ROOT_REFRESH_MILLIS) return
        rootsCheckedAt = now

        val discovered = WatchRoots.discover(this)
        if (discovered.isEmpty()) {
            store.report(
                "Magpie cannot see any of the folders it watches. If all-files access " +
                    "was only just granted, turn watching off and on again."
            )
        }
        if (discovered.map { it.path } == roots.map { it.path }) return
        roots = discovered
        goForeground()

        val wanted = discovered.associateBy { it.path }
        for (gone in observers.keys - wanted.keys) {
            observers.remove(gone)?.stopWatching()
        }
        for ((path, root) in wanted) {
            if (path in observers) continue
            val observer = object : FileObserver(root.directory, OBSERVER_MASK) {
                override fun onEvent(event: Int, path: String?) {
                    // Deliberately does no work: events arrive while a file is
                    // still being written, and under scoped storage they arrive
                    // unreliably. All they mean is "look again soon".
                    hurryUntil = System.currentTimeMillis() + HURRY_MILLIS
                }
            }
            observer.startWatching()
            observers[path] = observer
        }
    }

    private fun sweep(now: Long) {
        val live = HashSet<String>()
        var notified = 0

        for (root in roots) {
            val children = root.directory.listFiles()
            if (children == null) {
                store.report(
                    "Magpie cannot read ${root.label} (${root.path}). Check that " +
                        "all-files access is still granted to Magpie in Android settings."
                )
                continue
            }

            // Everything already in a folder when Magpie starts watching it is
            // older than that folder's baseline, so switching Magpie on never
            // produces a hundred notifications. The baseline is a timestamp
            // rather than a list of filenames, so it holds for a folder of any
            // size and survives a card being taken out and put back.
            val since = store.baselineFor(root.path, now)

            for (child in children) {
                if (!child.isFile) continue
                if (Naming.isTemporary(child.name)) continue

                val path = child.absolutePath
                live += path

                if (store.isOffered(path) || child.lastModified() < since) {
                    tracker.forget(path)
                    continue
                }
                if (!tracker.observe(path, child.length(), now)) continue

                val spotted = SpottedFile(
                    path = path,
                    name = child.name,
                    size = child.length(),
                    source = root.label,
                    spottedAt = now,
                )
                tracker.forget(path)
                if (!store.offer(spotted)) continue

                // Everything found goes on the waiting list; only the run of
                // notifications is capped, so coming back from a long spell of
                // being killed does not bury the notification shade.
                if (notified < NOTIFICATION_BURST_LIMIT) {
                    notified++
                    Notifications.offer(this, spotted)?.let(store::report)
                }
            }
        }

        tracker.retainOnly(live)
    }
}
