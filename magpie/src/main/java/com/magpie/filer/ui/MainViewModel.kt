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
import com.magpie.filer.watch.WatchService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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

    fun forgetIgnored(file: SpottedFile) {
        store.forgetIgnored(file.path)
    }

    fun dismissProblem(id: Long) {
        store.dismissProblem(id)
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
        _step.value = FilingStep.ChooseFolder(nextToken++, listOf(file))
    }

    /** Start filing everything ticked. Renaming is skipped for a batch. */
    fun fileSelected() {
        val chosen = waiting.value.filter { it.path in _selection.value }
        if (chosen.isEmpty()) return
        _step.value = FilingStep.ChooseFolder(nextToken++, chosen)
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
        val cleaned = Naming.withExtensionOf(
            Naming.sanitise(newName),
            renaming.file.name,
        )
        startMoves(listOf(renaming.file), renaming.tree, rename = cleaned)
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

            val outcomes = ArrayList<MoveOutcome>(files.size)
            for (file in files) {
                val outcome = mover.move(file, tree, rename ?: file.name)
                // Only a verified copy takes a file off the list. Anything else
                // stays where it is, in both senses.
                if (outcome is MoveOutcome.Moved || outcome is MoveOutcome.OriginalRemains) {
                    store.drop(file.path)
                    Notifications.cancel(app, file)
                }
                outcomes += outcome
            }

            _selection.value = emptySet()
            _selecting.value = false
            _step.value = FilingStep.Report(outcomes)
        }
    }

    // ---- opened from a notification ----------------------------------------

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
