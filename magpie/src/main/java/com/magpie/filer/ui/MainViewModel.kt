package com.magpie.filer.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.AndroidViewModel
import com.magpie.filer.core.Naming
import com.magpie.filer.move.Destinations
import com.magpie.filer.move.MoveOutcome
import com.magpie.filer.move.Mover
import com.magpie.filer.watch.FileStore
import com.magpie.filer.watch.Notifications
import com.magpie.filer.watch.SpottedFile
import com.magpie.filer.watch.WatchRoots
import com.magpie.filer.watch.WatchService
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What Android is currently letting Magpie do. */
data class Readiness(
    val allFilesAccess: Boolean,
    val notificationsAllowed: Boolean,
    val serviceRunning: Boolean,
)

/** Where the user is in the file-this flow. */
sealed interface FilingStep {

    data object Idle : FilingStep

    /** [token] makes each request distinct, so the picker opens exactly once. */
    data class ChooseFolder(val token: Long, val files: List<SpottedFile>) : FilingStep

    data class Rename(
        val file: SpottedFile,
        val tree: Uri,
        val destination: String,
        val suggestions: List<String>,
    ) : FilingStep

    data class Working(val message: String) : FilingStep

    data class Report(val outcomes: List<MoveOutcome>) : FilingStep
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private companion object {
        /** Enough to find something; not so many that the screen becomes a file manager. */
        const val IN_FOLDERS_LIMIT = 60
    }

    private val app = application
    private val store = FileStore.get(application)
    private val mover = Mover(application)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val waiting: StateFlow<List<SpottedFile>> = store.waiting
    val ignored: StateFlow<List<SpottedFile>> = store.ignored
    val problems = store.problems
    val watching: StateFlow<Boolean> = store.watching

    private val _readiness = MutableStateFlow(readReadiness())
    val readiness: StateFlow<Readiness> = _readiness.asStateFlow()

    private val _selection = MutableStateFlow<Set<String>>(emptySet())
    val selection: StateFlow<Set<String>> = _selection.asStateFlow()

    private val _selecting = MutableStateFlow(false)
    val selecting: StateFlow<Boolean> = _selecting.asStateFlow()

    private val _step = MutableStateFlow<FilingStep>(FilingStep.Idle)
    val step: StateFlow<FilingStep> = _step.asStateFlow()

    /** The folder the picker should open at, if one has been used before. */
    val lastDestination: Uri? get() = store.destination

    /**
     * What is in the watched folders right now, minus anything already waiting
     * or ignored. Files that were already there when watching started are never
     * offered by notification, but the spec is clear that they must still be
     * reachable from the app — this is how.
     */
    private val _inFolders = MutableStateFlow<List<SpottedFile>>(emptyList())
    val inFolders: StateFlow<List<SpottedFile>> = _inFolders.asStateFlow()

    /**
     * Set once Android has refused POST_NOTIFICATIONS. After that the system
     * dialog never appears again, so the button has to go to settings instead
     * of becoming a control that does nothing.
     */
    var notificationRequestRefused: Boolean = false
        private set

    fun onNotificationRequestRefused() {
        notificationRequestRefused = true
        refresh()
    }

    private var nextToken = 1L

    override fun onCleared() {
        scope.cancel()
        super.onCleared()
    }

    // ---- permissions and service state -------------------------------------

    fun refresh() {
        _readiness.value = readReadiness()
    }

    private fun readReadiness() = Readiness(
        allFilesAccess = Environment.isExternalStorageManager(),
        notificationsAllowed = NotificationManagerCompat.from(app).areNotificationsEnabled(),
        serviceRunning = WatchService.isRunning,
    )

    fun setWatching(on: Boolean) {
        store.setWatching(on)
        if (on) {
            // Watching starts from now: turning it off for a fortnight and back
            // on should not offer a fortnight of downloads in one go.
            store.resetBaselines()
            val failure = WatchService.start(app)
            if (failure != null) {
                store.report(failure)
                store.setWatching(false)
            }
        } else {
            WatchService.stop(app)
        }
        refresh()
    }

    // ---- the lists ---------------------------------------------------------

    fun ignore(file: SpottedFile) {
        store.ignore(file.path)
        Notifications.cancel(app, file)
        deselect(file.path)
    }

    fun restore(file: SpottedFile) {
        store.restore(file.path)
    }

    fun dismissProblem(id: Long) {
        store.dismissProblem(id)
    }

    /** Read the watched folders so older files can be filed by hand. */
    fun loadInFolders() {
        scope.launch {
            val found = withContext(Dispatchers.IO) { readInFolders() }
            _inFolders.value = found
        }
    }

    private fun readInFolders(): List<SpottedFile> {
        val alreadyListed = (waiting.value + ignored.value).map { it.path }.toSet()
        val found = ArrayList<SpottedFile>()
        for (root in WatchRoots.discover(app)) {
            val children = root.directory.listFiles()
            if (children == null) {
                store.report(
                    "Magpie cannot read ${root.label} (${root.path}), so what is in it " +
                        "cannot be listed. Check all-files access in Android settings."
                )
                continue
            }
            for (child in children) {
                if (!child.isFile) continue
                if (Naming.isTemporary(child.name)) continue
                if (child.absolutePath in alreadyListed) continue
                found += SpottedFile(
                    path = child.absolutePath,
                    name = child.name,
                    size = child.length(),
                    source = root.label,
                    spottedAt = child.lastModified(),
                )
            }
        }
        return found.sortedByDescending { it.spottedAt }.take(IN_FOLDERS_LIMIT)
    }

    // ---- selection ---------------------------------------------------------

    fun startSelecting() {
        _selecting.value = true
        _selection.value = emptySet()
    }

    fun beginSelecting(file: SpottedFile) {
        _selecting.value = true
        _selection.value = setOf(file.path)
    }

    fun toggleSelected(file: SpottedFile) {
        val current = _selection.value
        _selection.value = if (file.path in current) current - file.path else current + file.path
    }

    fun selectAll() {
        _selection.value = waiting.value.map { it.path }.toSet()
    }

    fun stopSelecting() {
        _selecting.value = false
        _selection.value = emptySet()
    }

    private fun deselect(path: String) {
        _selection.value = _selection.value - path
        if (_selection.value.isEmpty()) _selecting.value = false
    }

    // ---- the filing flow ---------------------------------------------------

    /** Start filing one file: the folder picker opens first, every time. */
    fun beginFiling(file: SpottedFile) {
        if (!startFiling(listOf(file))) {
            store.report(
                "Magpie is already filing something. Finish that one — or cancel it — " +
                    "and then tap ${file.name} again."
            )
        }
    }

    /** Start filing everything ticked. Renaming is skipped for a batch. */
    fun fileSelected() {
        val chosen = waiting.value.filter { it.path in _selection.value }
        if (chosen.isEmpty()) return
        startFiling(chosen)
    }

    /** False when a filing flow is already in progress, so nothing is stranded. */
    private fun startFiling(files: List<SpottedFile>): Boolean {
        if (_step.value !is FilingStep.Idle) return false
        _step.value = FilingStep.ChooseFolder(nextToken++, files)
        return true
    }

    fun onFolderChosen(uri: Uri?) {
        val pending = _step.value as? FilingStep.ChooseFolder
        if (pending == null) {
            // Android can kill the app while the folder picker is in front. The
            // picker still returns a folder, but which file it was for is gone.
            // Say so rather than appearing to do nothing.
            if (uri != null) {
                store.report(
                    "Magpie lost track of which file that folder was for — Android closed " +
                        "the app while the picker was open. Nothing was moved. Tap the file " +
                        "in the list below to try again."
                )
            }
            return
        }
        if (uri == null) {
            _step.value = FilingStep.Idle
            return
        }

        try {
            app.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            store.destination = uri
        } catch (e: SecurityException) {
            // The folder still works for this move; it just will not be
            // remembered for next time, and the user should know why.
            store.report(
                "Android would not let Magpie remember that folder for next time " +
                    "(${e.message ?: "permission refused"}). Filing it now still works."
            )
        }

        val files = pending.files
        if (files.size == 1) {
            val only = files.first()
            _step.value = FilingStep.Rename(
                file = only,
                tree = uri,
                destination = Destinations.label(app, uri),
                suggestions = Naming.suggestions(only.name),
            )
        } else {
            startMoves(files, uri, rename = null)
        }
    }

    fun confirmRename(newName: String) {
        val renaming = _step.value as? FilingStep.Rename ?: return
        val stripped = Naming.sanitise(newName)
        if (stripped.isBlank()) {
            // Everything typed was a character no folder accepts. Appending the
            // extension anyway would file the only copy as a hidden dotfile.
            store.report(
                "\"$newName\" is made only of characters a folder will not accept, so " +
                    "there would be no name left. Nothing was moved — try another name."
            )
            _step.value = FilingStep.Idle
            return
        }
        startMoves(
            listOf(renaming.file),
            renaming.tree,
            rename = Naming.withExtensionOf(stripped, renaming.file.name),
        )
    }

    fun cancelFiling() {
        _step.value = FilingStep.Idle
    }

    fun dismissReport() {
        _step.value = FilingStep.Idle
        stopSelecting()
    }

    private fun startMoves(files: List<SpottedFile>, tree: Uri, rename: String?) {
        scope.launch {
            _step.value = FilingStep.Working(
                if (files.size == 1) "Moving ${files.first().name}…"
                else "Moving ${files.size} files…"
            )

            val folder = Destinations.folderPath(tree)
            val outcomes = ArrayList<MoveOutcome>(files.size)
            for (file in files) {
                val outcome = mover.move(file, tree, rename ?: file.name)

                // Filing into a folder Magpie watches would otherwise have it
                // spot its own copy a moment later and offer it straight back.
                val savedAs = when (outcome) {
                    is MoveOutcome.Moved -> outcome.savedAs
                    is MoveOutcome.OriginalRemains -> outcome.savedAs
                    else -> null
                }
                if (folder != null && savedAs != null) {
                    store.markOffered(File(folder, savedAs).absolutePath)
                }

                // Only a completed move takes a file off the list. If the copy
                // worked but the original could not be deleted, the original is
                // still sitting there and the user may still want to deal with it.
                if (outcome is MoveOutcome.Moved) store.drop(file.path)
                if (outcome !is MoveOutcome.Failed) Notifications.cancel(app, file)

                outcomes += outcome
            }

            _selection.value = emptySet()
            _selecting.value = false
            _step.value = FilingStep.Report(outcomes)
        }
    }

    // ---- opened from a notification ----------------------------------------

    /**
     * True the first time a given notification tap is seen. Android replays an
     * activity's launch intent when it recreates the process, and acting on
     * that replay would reopen the folder picker out of nowhere.
     */
    fun claimNotification(token: String): Boolean {
        if (store.lastHandledNotification == token) return false
        store.lastHandledNotification = token
        return true
    }

    fun fileByPath(path: String) {
        val file = waiting.value.firstOrNull { it.path == path }
        if (file == null) {
            store.report(
                "That file is no longer waiting — it has either been filed already " +
                    "or it is no longer on the phone."
            )
            return
        }
        beginFiling(file)
    }

    // ---- settings shortcuts ------------------------------------------------

    fun allFilesAccessIntent(): Intent =
        Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
            .setData(Uri.parse("package:${app.packageName}"))

    fun allFilesAccessFallbackIntent(): Intent =
        Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)

    fun notificationSettingsIntent(): Intent =
        Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, app.packageName)

    fun report(message: String) {
        store.report(message)
    }

    val needsNotificationPermissionRequest: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !_readiness.value.notificationsAllowed
}
