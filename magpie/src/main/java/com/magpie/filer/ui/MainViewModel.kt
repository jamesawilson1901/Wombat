package com.magpie.filer.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.AndroidViewModel
import com.magpie.filer.ai.FilingSuggestion
import com.magpie.filer.ai.LibraryFolder
import com.magpie.filer.ai.LibraryFolders
import com.magpie.filer.ai.LibraryListing
import com.magpie.filer.ai.Rule
import com.magpie.filer.ai.Snapshots
import com.magpie.filer.ai.Rules
import com.magpie.filer.ai.FilingSuggestion as Suggestion
import com.magpie.filer.ai.SuggestionResult
import com.magpie.filer.ai.Suggester
import com.magpie.filer.core.Formatting
import com.magpie.filer.core.FolderPlan
import com.magpie.filer.core.Grouping
import com.magpie.filer.core.TimeGroup
import com.magpie.filer.core.Naming
import com.magpie.filer.core.PlanResult
import com.magpie.filer.core.Safety
import com.magpie.filer.move.Destinations
import com.magpie.filer.move.BuildReport
import com.magpie.filer.move.Filed
import com.magpie.filer.move.FolderBuilder
import com.magpie.filer.move.SafDocumentStore
import com.magpie.filer.move.MoveOutcome
import com.magpie.filer.move.Mover
import com.magpie.filer.watch.FileStore
import com.magpie.filer.watch.Notifications
import com.magpie.filer.watch.SpottedFile
import com.magpie.filer.watch.Taken
import com.magpie.filer.watch.WatchRoots
import com.magpie.filer.watch.WatchService
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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

    /** Waiting on Claude's opinion about one file. */
    data class Consulting(val file: SpottedFile) : FilingStep

    /** Claude has answered; the user decides whether to take it. */
    data class Suggested(
        val file: SpottedFile,
        val suggestion: FilingSuggestion,
        /** Where to open the picker, when the suggested folder was recognised. */
        val folderUri: Uri?,
        /** The folder name actually offered, or null when none was matched. */
        val folderName: String?,
    ) : FilingStep

    /**
     * [token] makes each request distinct, so the picker opens exactly once.
     * [openAt] positions the picker; [prefill] pre-fills the rename step.
     */
    data class ChooseFolder(
        val token: Long,
        val files: List<SpottedFile>,
        val openAt: Uri? = null,
        val prefill: String? = null,
    ) : FilingStep

    /**
     * A ticked batch being named: one stem, numbered across the lot, or kept
     * as they are.
     */
    data class NameBatch(val files: List<SpottedFile>) : FilingStep

    /**
     * Naming comes before choosing a folder, because the name is what decides
     * the folder. You call it what it is; the rule that matches that name then
     * puts the picker where that kind of thing goes.
     */
    data class Rename(
        val file: SpottedFile,
        val initial: String,
        val suggestions: List<String>,
    ) : FilingStep

    /**
     * A batch with its folder chosen, shown in full before anything runs:
     * every final name, and which of them will divert to Duplicates.
     */
    data class ConfirmBatch(
        val files: List<SpottedFile>,
        val tree: Uri,
        val destination: String,
        /** Every file's final name, keyed by its path. */
        val names: Map<String, String>,
        /** Final names already present in the destination. */
        val clashes: Set<String>,
        /** Set when the destination could not be checked for clashes. */
        val listingNote: String? = null,
    ) : FilingStep

    data class Working(val message: String) : FilingStep

    data class Report(val outcomes: List<MoveOutcome>) : FilingStep
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private companion object {
        /** Enough to find something; not so many that the screen becomes a file manager. */
        const val IN_FOLDERS_LIMIT = 60

        /**
         * startForegroundService only queues the start, so the service is not
         * running yet when the switch flips. Long enough for onCreate to have
         * happened, short enough that a real failure still surfaces.
         */
        const val SERVICE_START_GRACE_MILLIS = 6_000L
    }

    private val app = application
    private val store = FileStore.get(application)
    private val mover = Mover(application)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val waiting: StateFlow<List<SpottedFile>> = store.waiting
    val ignored: StateFlow<List<SpottedFile>> = store.ignored
    val problems = store.problems
    val watching: StateFlow<Boolean> = store.watching
    val suggestionSettings = store.suggestions
    val filed = store.filed
    val rules = store.rules
    val extraRoots = store.extraRoots

    /** When a service start was last asked for. Read by readReadiness below. */
    private var startRequestedAt = 0L

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

    /** The in-flight question to Claude, so it can be given up on. */
    private var consultJob: Job? = null

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
        // Just after asking for a start there is nothing running yet, and saying
        // so would accuse Android of killing a service it has not begun.
        serviceRunning = WatchService.isRunning ||
            System.currentTimeMillis() - startRequestedAt < SERVICE_START_GRACE_MILLIS,
    )

    fun setWatching(on: Boolean) {
        store.setWatching(on)
        if (on) {
            // Watching starts from now: turning it off for a fortnight and back
            // on should not offer a fortnight of downloads in one go.
            store.resetBaselines()
            startRequestedAt = System.currentTimeMillis()
            val failure = WatchService.start(app)
            if (failure != null) {
                startRequestedAt = 0L
                store.report(failure)
                store.setWatching(false)
            } else {
                // Come back once the service has had time to start, so the card
                // shows what is actually true rather than what was just asked for.
                scope.launch {
                    delay(SERVICE_START_GRACE_MILLIS)
                    refresh()
                }
            }
        } else {
            startRequestedAt = 0L
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

    // ---- naming suggestions ------------------------------------------------

    fun setApiKey(key: String) = store.setApiKey(key)

    fun setSuggestionsEnabled(on: Boolean) = store.setSuggestionsEnabled(on)

    fun setVisionEnabled(on: Boolean) = store.setVisionEnabled(on)

    /** Remember the folder whose subfolders Claude is allowed to choose between. */
    fun setLibrary(tree: Uri?) {
        if (tree == null) {
            store.setLibrary(null)
            return
        }
        try {
            app.contentResolver.takePersistableUriPermission(
                tree,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (e: SecurityException) {
            store.report(
                "Android would not let Magpie keep access to that library folder " +
                    "(${e.message ?: "permission refused"}), so it cannot be used for " +
                    "suggestions. Choose a different folder."
            )
            return
        }
        store.setLibrary(tree)
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
        for (root in WatchRoots.sortable(app)) {
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
        if (_step.value !is FilingStep.Idle) {
            store.report(
                "Magpie is already filing something. Finish that one — or cancel it — " +
                    "and then tap ${file.name} again."
            )
            return
        }
        val settings = store.suggestions.value

        // An answer already in hand from a batch fills the name box straight
        // away — that is the whole point of having asked in bulk.
        val held = takeBatchAnswer(file)
        if (held != null) {
            _step.value = askName(file, prefill = held.name)
            return
        }

        if (!settings.usable) {
            startFiling(listOf(file))
            return
        }
        _step.value = FilingStep.Consulting(file)
        consultJob = scope.launch { consult(file, settings.apiKey, settings.library) }
    }

    /**
     * Ask about everything on the waiting list in one request.
     *
     * Switching suggestions on with a backlog means a request per file
     * otherwise. Answers are held and fill the name box as each file is
     * filed, so the waiting is done once rather than every time you tap.
     */
    fun suggestForWaiting() {
        val settings = store.suggestions.value
        if (!settings.usable) {
            store.report("Turn on Ask Claude for a name and save an API key first.")
            return
        }
        val files = waiting.value.take(Suggester.BATCH_LIMIT)
        if (files.isEmpty()) {
            store.report("Nothing is waiting, so there is nothing to ask about.")
            return
        }
        if (_step.value !is FilingStep.Idle) {
            store.report("Magpie is busy filing. Finish that first.")
            return
        }

        _step.value = FilingStep.Working("Asking Claude about ${files.size} files…")
        scope.launch {
            val folders = settings.library?.let { lib ->
                when (val listing = withContext(Dispatchers.IO) { LibraryFolders.list(app, lib) }) {
                    is LibraryListing.Folders -> listing.folders
                    is LibraryListing.Failed -> {
                        store.report(listing.reason)
                        emptyList()
                    }
                }
            }.orEmpty()

            val answers = Suggester.suggestMany(files, folders.map { it.name }, settings.apiKey)
            _batchSuggestions.value = answers
            _step.value = FilingStep.Idle
            store.report(
                if (answers.isEmpty()) {
                    "Claude had nothing to suggest for those, or the request did not get " +
                        "through. Filing works as usual."
                } else {
                    "Suggestions ready for ${answers.size} of ${files.size}. Tap a file and " +
                        "its suggested name is already in the box."
                }
            )
        }
    }

    /** Answers from the last batch, used up as each file is filed. */
    private val _batchSuggestions = MutableStateFlow<Map<String, Suggestion>>(emptyMap())

    private fun takeBatchAnswer(file: SpottedFile): Suggestion? {
        val answer = _batchSuggestions.value[file.path] ?: return null
        _batchSuggestions.value = _batchSuggestions.value - file.path
        return answer
    }

    fun addRule(rule: Rule) = store.addRule(rule)

    fun removeRule(rule: Rule) = store.removeRule(rule)

    /**
     * Stop waiting on Claude and file the ordinary way. Asking can take the
     * better part of a minute on a bad connection, and nothing in this app is
     * allowed to stand between someone and their file for that long.
     */
    fun skipSuggestion() {
        val waiting = _step.value as? FilingStep.Consulting ?: return
        consultJob?.cancel()
        consultJob = null
        _step.value = askName(waiting.file, prefill = null)
    }

    /**
     * Ask Claude, then hand the answer to the user. A failure here is reported
     * and the ordinary flow carries on — a suggestion is a convenience, and it
     * must never be the thing standing between someone and their file.
     */
    private suspend fun consult(file: SpottedFile, apiKey: String, library: Uri?) {
        val offered: List<LibraryFolder> = if (library == null) {
            emptyList()
        } else {
            when (val listing = withContext(Dispatchers.IO) { LibraryFolders.list(app, library) }) {
                is LibraryListing.Folders -> listing.folders
                is LibraryListing.Failed -> {
                    store.report(listing.reason)
                    emptyList()
                }
            }
        }

        when (val result = Suggester.suggest(file, offered.map { it.name }, apiKey)) {
            is SuggestionResult.Failed -> {
                store.report(result.reason)
                if (_step.value is FilingStep.Consulting) {
                    _step.value = askName(file, prefill = null)
                }
            }

            is SuggestionResult.Ready -> {
                if (_step.value is FilingStep.Consulting) {
                    show(file, result.suggestion, offered, library)
                }
            }
        }
    }

    /**
     * Put a suggestion in front of the user. Only a folder Magpie actually
     * offered is accepted, so an invented folder name cannot send the picker
     * somewhere odd — it simply becomes a name suggestion with no folder.
     */
    private fun show(
        file: SpottedFile,
        suggestion: Suggestion,
        offered: List<LibraryFolder>,
        library: Uri?,
    ) {
        val match = offered.firstOrNull { it.name == suggestion.folder }
        _step.value = FilingStep.Suggested(
            file = file,
            suggestion = suggestion,
            folderUri = match?.let { m -> library?.let { LibraryFolders.uriFor(it, m.documentId) } },
            folderName = match?.name,
        )
    }

    /** Show a suggestion that came from a batch, looking the folder up now. */
    private suspend fun offerSuggestion(file: SpottedFile, suggestion: Suggestion, library: Uri?) {
        val offered = library?.let { lib ->
            when (val listing = withContext(Dispatchers.IO) { LibraryFolders.list(app, lib) }) {
                is LibraryListing.Folders -> listing.folders
                is LibraryListing.Failed -> emptyList()
            }
        }.orEmpty()
        show(file, suggestion, offered, library)
    }

    fun acceptSuggestion() {
        val suggested = _step.value as? FilingStep.Suggested ?: return
        _step.value = askName(suggested.file, prefill = suggested.suggestion.name)
    }

    fun declineSuggestion() {
        val suggested = _step.value as? FilingStep.Suggested ?: return
        _step.value = askName(suggested.file, prefill = null)
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
        _step.value = if (files.size == 1) {
            askName(files.first(), prefill = null)
        } else {
            // A batch gets one stem numbered across the lot — or keeps its
            // names, which is one tap. Time order, so the numbers mean
            // something.
            FilingStep.NameBatch(files.sortedBy { it.spottedAt })
        }
        return true
    }

    /** The rename step for one file, with [prefill] leading the suggestions. */
    private fun askName(file: SpottedFile, prefill: String?): FilingStep.Rename =
        FilingStep.Rename(
            file = file,
            initial = prefill ?: file.name,
            suggestions = (listOfNotNull(prefill) + Naming.suggestions(file.name)).distinct(),
        )

    /**
     * The batch has been named — or waved through. [stem] null means keep
     * every file's own name; otherwise the whole batch is numbered under it
     * in time order, exactly as a run from the backlog is.
     */
    fun confirmBatchName(stem: String?) {
        val batch = _step.value as? FilingStep.NameBatch ?: return
        if (stem == null) {
            _step.value = FilingStep.ChooseFolder(nextToken++, batch.files, lastDestination)
            return
        }
        val names = Grouping.numbered(stem, batch.files)
        if (names.isEmpty()) {
            store.report(
                "\"$stem\" leaves nothing to name these with. Try another name, or keep " +
                    "their own names."
            )
            return
        }
        pendingGroupNames = names
        // The name decides the folder here too: a rule matching it opens the
        // picker at the right place.
        val rule = Rules.match(names.values.first(), store.rules.value)
        scope.launch {
            val at = rule?.let { folderUriFor(it.folder) }
            _step.value = FilingStep.ChooseFolder(
                token = nextToken++,
                files = batch.files,
                openAt = at ?: lastDestination,
            )
        }
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
            pendingGroupNames = emptyMap()
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

        // The name was settled before the picker opened. A single file goes
        // straight to the copy; a batch stops once more to show exactly what
        // is about to happen, because a batch is the one place a mistake
        // multiplies.
        val renames = pendingGroupNames
        pendingGroupNames = emptyMap()
        if (pending.files.size == 1) {
            startMoves(pending.files, uri, rename = pending.prefill)
            return
        }

        scope.launch {
            _step.value = FilingStep.Working("Checking ${Destinations.label(app, uri)}…")
            val finalNames = pending.files.associate { file ->
                file.path to (renames[file.path] ?: file.name)
            }
            var note: String? = null
            val taken: Set<String> = withContext(Dispatchers.IO) {
                try {
                    SafDocumentStore(app, uri, Destinations.label(app, uri))
                        .list(null).map { it.name }.toSet()
                } catch (e: Exception) {
                    note = "The folder could not be checked for name clashes " +
                        "(${e.message ?: e.javaClass.simpleName}) — any clash will still " +
                        "be kept apart during the copy."
                    emptySet()
                }
            }
            _step.value = FilingStep.ConfirmBatch(
                files = pending.files,
                tree = uri,
                destination = Destinations.label(app, uri),
                names = finalNames,
                clashes = finalNames.values.filterTo(HashSet()) { it in taken },
                listingNote = note,
            )
        }
    }

    /** The preview has been read; run the batch exactly as shown. */
    fun confirmBatch() {
        val confirmed = _step.value as? FilingStep.ConfirmBatch ?: return
        startMoves(confirmed.files, confirmed.tree, confirmed.names)
    }

    fun confirmRename(newName: String) {
        val renaming = _step.value as? FilingStep.Rename ?: return
        val candidate = Naming.withExtensionOf(
            Naming.sanitise(newName),
            renaming.file.name,
        )
        if (!Naming.isUsable(candidate)) {
            // Nothing usable was left once the illegal characters went. Filing
            // it anyway would put the only copy somewhere invisible.
            store.report(
                "\"$newName\" leaves nothing to name the file with, so it would end up " +
                    "hidden. Nothing was moved — try another name."
            )
            _step.value = FilingStep.Idle
            return
        }
        // The name you just typed is what decides where it goes. A rule that
        // matches it opens the picker at that folder — which is why naming
        // first works where guessing from a download's own filename does not.
        val rule = Rules.match(candidate, store.rules.value)
        scope.launch {
            val at = rule?.let { folderUriFor(it.folder) }
            if (rule != null && at == null && store.suggestions.value.library != null) {
                store.report(
                    "The rule \"${rule.describe()}\" points at \"${rule.folder}\", which is " +
                        "not in your library any more. Choose where it goes and fix or " +
                        "forget the rule when you like."
                )
            }
            _step.value = FilingStep.ChooseFolder(
                token = nextToken++,
                files = listOf(renaming.file),
                openAt = at ?: lastDestination,
                prefill = candidate,
            )
        }
    }

    /** Where a rule's folder actually is, when the library can be read. */
    private suspend fun folderUriFor(name: String): Uri? {
        val library = store.suggestions.value.library ?: return null
        val listing = withContext(Dispatchers.IO) { LibraryFolders.list(app, library) }
        val folders = when (listing) {
            is LibraryListing.Folders -> listing.folders
            is LibraryListing.Failed -> return null
        }
        val match = folders.firstOrNull { it.name == name } ?: return null
        return LibraryFolders.uriFor(library, match.documentId)
    }

    fun cancelFiling() {
        pendingGroupNames = emptyMap()
        _step.value = FilingStep.Idle
    }

    fun dismissReport() {
        _step.value = FilingStep.Idle
        stopSelecting()
    }

    private fun startMoves(files: List<SpottedFile>, tree: Uri, rename: String?) =
        startMoves(files, tree, if (rename == null) emptyMap() else mapOf(files.first().path to rename))

    /**
     * [renames] maps a file's path to the name it should be saved under. A file
     * not in the map keeps its own name, which is what a plain batch does.
     */
    private fun startMoves(files: List<SpottedFile>, tree: Uri, renames: Map<String, String>) {
        scope.launch {
            _step.value = FilingStep.Working(
                if (files.size == 1) "Copying ${files.first().name}…"
                else "Copying ${files.size} files…"
            )

            val folder = Destinations.folderPath(tree)
            val outcomes = ArrayList<MoveOutcome>(files.size)
            for (file in files) {
                val outcome = mover.copy(file, tree, renames[file.path] ?: file.name)

                // Filing into a folder Magpie watches would otherwise have it
                // spot its own copy a moment later and offer it straight back.
                // A duplicate lands one folder deeper, so that path is marked
                // instead.
                val landed = when (outcome) {
                    is MoveOutcome.Copied ->
                        folder?.let { File(it, outcome.savedAs) }

                    is MoveOutcome.Duplicated ->
                        folder?.let { File(File(it, Safety.DUPLICATES_FOLDER), outcome.savedAs) }

                    else -> null
                }
                if (landed != null) store.markOffered(landed.absolutePath)

                // Remember which original is now redundant, so the user can be
                // told what is safe to clear up. Magpie still never removes it.
                rememberFiled(file, outcome, tree)

                // A verified copy takes the file off the waiting list: the user
                // has dealt with it. The original is still on the phone — Magpie
                // never deletes — and the report says so in as many words, but
                // it does not need offering again.
                if (outcome is MoveOutcome.Copied || outcome is MoveOutcome.Duplicated) {
                    store.drop(file.path)
                }
                if (outcome !is MoveOutcome.Failed) Notifications.cancel(app, file)

                outcomes += outcome
            }

            _selection.value = emptySet()
            _selecting.value = false
            _step.value = FilingStep.Report(outcomes)
        }
    }

    private fun rememberFiled(file: SpottedFile, outcome: MoveOutcome, tree: Uri) {
        val entry = when (outcome) {
            is MoveOutcome.Copied -> Filed(
                originalPath = file.path,
                originalName = file.name,
                size = file.size,
                destination = outcome.destination,
                savedAs = outcome.savedAs,
                tree = tree.toString(),
                document = outcome.document,
            )

            is MoveOutcome.Duplicated -> Filed(
                originalPath = file.path,
                originalName = file.name,
                size = file.size,
                destination = outcome.duplicatesFolder,
                savedAs = outcome.savedAs,
                tree = tree.toString(),
                document = outcome.document,
            )

            else -> null
        }
        if (entry != null) store.remember(entry)
    }

    /**
     * Check every clear-up note is still true, and drop the ones that are not.
     *
     * An original the user has already removed, or a copy that has since been
     * moved or deleted by something else, means the note is stale — and a list
     * that says "safe to clear" has to be right or it is worse than useless.
     */
    fun refreshFiled() {
        scope.launch {
            val checked = withContext(Dispatchers.IO) { verifyFiled(store.filed.value) }
            store.keepFiled(checked)
        }
    }

    private fun verifyFiled(entries: List<Filed>): List<Filed> {
        // One store per destination tree, not one per entry: building it is
        // cheap but the permission check behind it is not.
        val stores = HashMap<String, SafDocumentStore?>()
        return entries.filter { entry ->
            val original = File(entry.originalPath)
            val parent = original.parentFile
            // A folder Magpie cannot read is a card that is out, not proof the
            // original has gone — the entry is kept rather than guessed away.
            if (parent == null || !parent.canRead()) return@filter true
            if (!original.isFile) return@filter false

            val store = stores.getOrPut(entry.tree) {
                try {
                    val tree = Uri.parse(entry.tree)
                    SafDocumentStore(app, tree, Destinations.label(app, tree))
                } catch (e: IllegalArgumentException) {
                    null
                }
            } ?: return@filter false

            try {
                store.exists(entry.document)
            } catch (e: SecurityException) {
                // Permission to that folder is gone, so the copy cannot be
                // vouched for any more.
                false
            } catch (e: IllegalArgumentException) {
                false
            } catch (e: java.io.IOException) {
                false
            }
        }
    }

    /** The user has dealt with this one; stop offering it. */
    fun forgetFiled(originalPath: String) = store.forgetFiled(originalPath)

    /**
     * Watch a folder the user picked. The picker hands back a tree URI, but a
     * FileObserver needs a real path, so this only works for folders on your own
     * storage — which is also the only place Magpie is allowed to look.
     */
    fun addWatchedFolder(tree: Uri?) {
        if (tree == null) return
        val folder = Destinations.folderPath(tree)
        if (folder == null) {
            store.report(
                "Magpie can only watch folders on your phone's own storage or a memory " +
                    "card, and that one is somewhere it cannot follow — a cloud folder, or " +
                    "another app's. Nothing was changed."
            )
            return
        }
        Safety.refuse(folder.absolutePath, WatchRoots.volumePaths(app))?.let {
            store.report("Magpie will not watch there — $it.")
            return
        }
        if (!folder.isDirectory) {
            store.report("${folder.absolutePath} is not a folder Magpie can read. Nothing was changed.")
            return
        }
        store.addRoot(folder.absolutePath)
        // Watching starts from now for a folder just added, so switching it on
        // does not offer everything already in it.
        store.baselineFor(folder.absolutePath, System.currentTimeMillis())
        store.report(
            "Now watching ${folder.absolutePath}. What is already in it is treated as old, " +
                "and is reachable from Already in your folders."
        )
    }

    fun removeWatchedFolder(path: String) = store.removeRoot(path)

    // ---- grouping the backlog ----------------------------------------------

    /** The backlog split into runs, or empty until it has been worked out. */
    private val _groups = MutableStateFlow<List<TimeGroup>>(emptyList())
    val groups: StateFlow<List<TimeGroup>> = _groups.asStateFlow()

    /** How long a quiet spell has to be to start a new group, in minutes. */
    private val _groupGapMinutes = MutableStateFlow(Grouping.DEFAULT_GAP_MILLIS / 60_000)
    val groupGapMinutes: StateFlow<Long> = _groupGapMinutes.asStateFlow()

    /**
     * Work out the groups from what is in the watched folders.
     *
     * Reading a photo's taken-at time means opening every file, so this happens
     * off the main thread and only when asked for.
     */
    fun regroup() {
        scope.launch {
            val found = withContext(Dispatchers.IO) {
                val files = readInFolders()
                _inFolders.value = files
                Grouping.byTime(
                    files = files,
                    gap = _groupGapMinutes.value * 60_000,
                    timeOf = Taken::of,
                )
            }
            _groups.value = found
        }
    }

    fun setGroupGapMinutes(minutes: Long) {
        _groupGapMinutes.value = minutes.coerceIn(1, 72 * 60)
        if (_groups.value.isNotEmpty()) regroup()
    }

    /** Join a group with the one after it, when one event spanned a break. */
    fun mergeGroup(index: Int) {
        _groups.value = Grouping.merge(_groups.value, index)
    }

    /** Cut a group in two, when two things shared an afternoon. */
    fun splitGroup(index: Int, at: Int) {
        val groups = _groups.value
        if (index !in groups.indices) return
        val parts = Grouping.split(groups[index], at, Taken::of)
        if (parts.size == 1) return
        _groups.value = groups.subList(0, index) + parts + groups.subList(index + 1, groups.size)
    }

    /** Take a group off the list without filing it. */
    fun dropGroup(index: Int) {
        val groups = _groups.value
        if (index !in groups.indices) return
        _groups.value = groups.subList(0, index) + groups.subList(index + 1, groups.size)
    }

    /**
     * File a whole group into one folder under one [stem], numbered in time
     * order. One destination and one name for the run, which is the entire
     * point: a group cannot scatter because there is only one decision.
     */
    fun fileGroup(index: Int, stem: String, folderName: String? = null) {
        val group = _groups.value.getOrNull(index) ?: return
        if (_step.value !is FilingStep.Idle) {
            store.report("Magpie is already filing something. Finish that first.")
            return
        }
        val names = Grouping.numbered(stem, group.files)
        if (names.isEmpty()) {
            store.report(
                "\"$stem\" leaves nothing to name these with. Try another name for the group."
            )
            return
        }
        pendingGroupNames = names
        // Claude's suggested folder, when there is one, or a rule matching the
        // run's name — either way the picker opens at the right place and the
        // user still confirms.
        val rule = Rules.match(names.values.first(), store.rules.value)
        scope.launch {
            val at = folderName?.let { folderUriFor(it) } ?: rule?.let { folderUriFor(it.folder) }
            _step.value = FilingStep.ChooseFolder(
                token = nextToken++,
                files = group.files,
                openAt = at ?: lastDestination,
            )
        }
    }

    /** Claude's answer per run, keyed by the run's first file. */
    private val _runSuggestions = MutableStateFlow<Map<String, Suggestion>>(emptyMap())
    val runSuggestions: StateFlow<Map<String, Suggestion>> = _runSuggestions.asStateFlow()

    /** Runs currently being asked about, so the card can show it is busy. */
    private val _askingRuns = MutableStateFlow<Set<String>>(emptySet())
    val askingRuns: StateFlow<Set<String>> = _askingRuns.asStateFlow()

    /**
     * Show Claude a few snapshots from a run and get one stem and one folder
     * for the whole thing. Only ever on the user's tap, only with the vision
     * switch on, and only the first, middle and last frames at 512 pixels —
     * never the files themselves.
     */
    fun suggestRunName(index: Int) {
        val settings = store.suggestions.value
        if (!settings.visionUsable) {
            store.report("Turn on Let Claude look at runs, and save an API key, first.")
            return
        }
        val group = _groups.value.getOrNull(index) ?: return
        val key = group.files.first().path
        if (key in _askingRuns.value) return

        _askingRuns.value = _askingRuns.value + key
        scope.launch {
            try {
                val representatives = listOf(
                    group.files.first(),
                    group.files[group.size / 2],
                    group.files.last(),
                ).distinctBy { it.path }

                val snapshots = withContext(Dispatchers.IO) {
                    representatives.mapNotNull { Snapshots.of(it) }
                }
                val folders = settings.library?.let { lib ->
                    when (val listing = withContext(Dispatchers.IO) { LibraryFolders.list(app, lib) }) {
                        is LibraryListing.Folders -> listing.folders.map { it.name }
                        is LibraryListing.Failed -> {
                            store.report(listing.reason)
                            emptyList()
                        }
                    }
                }.orEmpty()

                val range = Formatting.timeRange(
                    group.earliest, group.latest, java.time.ZoneId.systemDefault(),
                )
                when (val result = Suggester.suggestRun(
                    snapshots = snapshots,
                    count = group.size,
                    range = range,
                    folders = folders,
                    apiKey = settings.apiKey,
                )) {
                    is SuggestionResult.Ready ->
                        _runSuggestions.value = _runSuggestions.value + (key to result.suggestion)

                    is SuggestionResult.Failed -> store.report(result.reason)
                }
            } finally {
                _askingRuns.value = _askingRuns.value - key
            }
        }
    }

    /** Names for the group being filed, applied when the folder comes back. */
    private var pendingGroupNames: Map<String, String> = emptyMap()

    // ---- building a folder tree --------------------------------------------

    /** The tree text the user has typed or pasted, kept while they edit it. */
    private val _treeText = MutableStateFlow("")
    val treeText: StateFlow<String> = _treeText.asStateFlow()

    /** What the text currently means, so the count can be shown before building. */
    private val _treePlan = MutableStateFlow<PlanResult?>(null)
    val treePlan: StateFlow<PlanResult?> = _treePlan.asStateFlow()

    /** The result of the last build, shown until dismissed. */
    private val _treeReport = MutableStateFlow<BuildReport?>(null)
    val treeReport: StateFlow<BuildReport?> = _treeReport.asStateFlow()

    /** Set when the folder picker should open to choose where to build. */
    private val _buildingAt = MutableStateFlow(0L)
    val buildingAt: StateFlow<Long> = _buildingAt.asStateFlow()

    fun setTreeText(text: String) {
        _treeText.value = text
        _treePlan.value = if (text.isBlank()) null else FolderPlan.parse(text)
    }

    /** Ask where to build. Nothing is made until a folder comes back. */
    fun chooseWhereToBuild() {
        val plan = _treePlan.value
        if (plan !is PlanResult.Ready) {
            store.report(
                (plan as? PlanResult.Rejected)?.reason
                    ?: "Type or paste a folder tree first."
            )
            return
        }
        _buildingAt.value = nextToken++
    }

    fun onBuildFolderChosen(tree: Uri?) {
        _buildingAt.value = 0L
        if (tree == null) return
        val plan = _treePlan.value as? PlanResult.Ready ?: return

        val chosen = Destinations.folderPath(tree)
        if (chosen != null) {
            Safety.refuse(chosen.absolutePath, WatchRoots.volumePaths(app))?.let {
                store.report("Magpie will not build there — $it.")
                return
            }
        }

        scope.launch {
            _step.value = FilingStep.Working("Making ${plan.folders.size} folders…")
            val report = withContext(Dispatchers.IO) {
                FolderBuilder.build(
                    plan = plan.folders,
                    store = SafDocumentStore(app, tree, Destinations.label(app, tree)),
                )
            }
            _step.value = FilingStep.Idle
            _treeReport.value = report
        }
    }

    fun dismissTreeReport() {
        _treeReport.value = null
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
