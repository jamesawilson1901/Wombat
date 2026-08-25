package com.magpie.filer.watch

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import com.magpie.filer.move.Filed
import java.io.File

/** Something that went wrong, worded for the person holding the phone. */
data class Problem(val id: Long, val message: String)

/**
 * Everything the optional Claude suggestions need. With [enabled] off or
 * [apiKey] empty, Magpie makes no network calls at all.
 */
data class SuggestionSettings(
    val apiKey: String,
    val enabled: Boolean,
    val library: Uri?,
) {
    val usable: Boolean get() = enabled && apiKey.isNotBlank()
}

/**
 * Everything Magpie remembers between runs, and the single source of truth the
 * watcher and the screen both read.
 *
 * Kept small on purpose: a waiting list, an ignored list, the files it has
 * already offered, and when it started watching each folder. There is no move
 * history and no undo stack — those are deliberately out of scope.
 *
 * "Old" is decided by a timestamp per folder, not by remembering every file
 * that was in it. That matters: a folder with thousands of files would overflow
 * any remembered-paths list, and everything that fell off the end would be
 * offered again as though it were new.
 */
class FileStore private constructor(private val prefs: SharedPreferences) {

    companion object {
        private const val PREFS = "magpie"
        private const val KEY_WAITING = "waiting"
        private const val KEY_IGNORED = "ignored"
        private const val KEY_OFFERED = "offered"
        private const val KEY_BASELINES = "baselines"
        private const val KEY_WATCHING = "watching"
        private const val KEY_DESTINATION = "destination"
        private const val KEY_LAST_NOTIFICATION = "lastNotification"
        private const val KEY_FILED = "filed"

        /**
         * Enough to clear up a real backlog, few enough that the list stays
         * something a person reads rather than a database. The oldest go
         * first, because the newest are the ones still fresh in mind.
         */
        private const val FILED_LIMIT = 300
        private const val KEY_API_KEY = "anthropicApiKey"
        private const val KEY_SUGGESTIONS = "suggestionsEnabled"
        private const val KEY_LIBRARY = "libraryTree"

        /**
         * Only files Magpie has actually offered land here, so this grows with
         * use rather than with the size of the Downloads folder.
         */
        private const val OFFERED_LIMIT = 4_000

        @Volatile
        private var instance: FileStore? = null

        fun get(context: Context): FileStore =
            instance ?: synchronized(this) {
                instance ?: FileStore(
                    context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                ).also { instance = it }
            }
    }

    private val lock = Any()

    // Filled in by the readers below if anything on disk turns out to be
    // unreadable, then handed straight to the problem list so a reset is
    // something the user is told about rather than something that just happens.
    private val startupProblems = mutableListOf<String>()

    private val _waiting = MutableStateFlow(readFiles(KEY_WAITING, "waiting list"))
    val waiting: StateFlow<List<SpottedFile>> = _waiting.asStateFlow()

    private val _ignored = MutableStateFlow(readFiles(KEY_IGNORED, "ignored list"))
    val ignored: StateFlow<List<SpottedFile>> = _ignored.asStateFlow()

    /** Insertion-ordered, so the oldest entries fall off the end first. */
    private val offered = LinkedHashSet(readStrings(KEY_OFFERED, "list of files already offered"))

    /** Watched folder path to the moment Magpie started watching it. */
    private val baselines = readBaselines()

    private val _problems = MutableStateFlow(
        startupProblems.mapIndexed { index, message -> Problem(index + 1L, message) }
    )
    val problems: StateFlow<List<Problem>> = _problems.asStateFlow()

    private var nextProblemId = startupProblems.size + 1L

    private val _watching = MutableStateFlow(prefs.getBoolean(KEY_WATCHING, false))
    val watching: StateFlow<Boolean> = _watching.asStateFlow()

    // ---- what counts as old ------------------------------------------------

    /**
     * When Magpie started watching [root]. The first call for a folder sets it
     * to [now] and returns that, so everything already in the folder is older
     * than the baseline and is never offered.
     *
     * Survives a restart on purpose: a file that arrived while the service was
     * dead is newer than the baseline, so it is still offered when Magpie comes
     * back rather than being lost.
     */
    fun baselineFor(root: String, now: Long): Long = synchronized(lock) {
        val existing = baselines[root]
        if (existing != null) return@synchronized existing
        baselines[root] = now
        writeBaselines()
        now
    }

    /**
     * Start again from now. Called when the user switches watching on, so that
     * turning it off for a fortnight and on again does not offer a fortnight of
     * downloads in one go.
     */
    fun resetBaselines() {
        synchronized(lock) {
            baselines.clear()
            writeBaselines()
        }
    }

    fun isOffered(path: String): Boolean = synchronized(lock) { path in offered }

    /**
     * Note a file as dealt with without offering it. Used for the copies Magpie
     * itself writes into a folder it is watching — otherwise it would spot its
     * own work and offer it straight back.
     */
    fun markOffered(path: String) {
        synchronized(lock) {
            if (offered.add(path)) {
                trimOffered()
                writeStrings(KEY_OFFERED, offered)
            }
        }
    }

    private fun trimOffered() {
        while (offered.size > OFFERED_LIMIT) {
            offered.remove(offered.first())
        }
    }

    // ---- the waiting list --------------------------------------------------

    /**
     * Offer a newly settled file. True means it went onto the waiting list,
     * which is also the signal to post its notification. False means it has
     * been offered before, is already waiting, or has been ignored.
     */
    fun offer(file: SpottedFile): Boolean = synchronized(lock) {
        when {
            file.path in offered -> false
            _waiting.value.any { it.path == file.path } -> false
            _ignored.value.any { it.path == file.path } -> false
            else -> {
                offered.add(file.path)
                trimOffered()
                writeStrings(KEY_OFFERED, offered)
                _waiting.value = _waiting.value + file
                writeFiles(KEY_WAITING, _waiting.value)
                true
            }
        }
    }

    /** Take a file off the waiting list: filed, or gone. */
    fun drop(path: String) {
        synchronized(lock) {
            val remaining = _waiting.value.filterNot { it.path == path }
            if (remaining.size != _waiting.value.size) {
                _waiting.value = remaining
                writeFiles(KEY_WAITING, remaining)
            }
        }
    }

    // ---- the ignored list --------------------------------------------------

    fun ignore(path: String) {
        synchronized(lock) {
            val file = _waiting.value.firstOrNull { it.path == path }
            if (file != null) {
                _waiting.value = _waiting.value.filterNot { it.path == path }
                _ignored.value = _ignored.value + file
                writeFiles(KEY_WAITING, _waiting.value)
                writeFiles(KEY_IGNORED, _ignored.value)
            }
        }
    }

    fun restore(path: String) {
        synchronized(lock) {
            val file = _ignored.value.firstOrNull { it.path == path }
            if (file != null) {
                _ignored.value = _ignored.value.filterNot { it.path == path }
                _waiting.value = _waiting.value + file
                writeFiles(KEY_WAITING, _waiting.value)
                writeFiles(KEY_IGNORED, _ignored.value)
            }
        }
    }

    /**
     * Drop entries whose file has genuinely been deleted. Files leave by other
     * routes — a file manager, the browser's own cleanup — and a list full of
     * names that lead nowhere is worse than an empty one.
     *
     * A file on a card that has been taken out has not been deleted, so it is
     * kept. Getting that wrong would empty the lists every time a card is
     * unplugged, and would let an ignored file come back as a notification.
     *
     * Touches the disk; call it from a background thread.
     */
    fun pruneMissing() {
        val waitingPaths = _waiting.value.map { it.path }
        val ignoredPaths = _ignored.value.map { it.path }
        val offeredPaths = synchronized(lock) { offered.toList() }

        // Thousands of stat calls, done outside the lock on purpose: the main
        // thread takes the same lock for every change to a list, and it must not
        // be made to wait behind a scan of the disk.
        val gone = (waitingPaths + ignoredPaths + offeredPaths).filterTo(HashSet(), ::deleted)
        if (gone.isEmpty()) return

        synchronized(lock) {
            val liveWaiting = _waiting.value.filterNot { it.path in gone }
            if (liveWaiting.size != _waiting.value.size) {
                _waiting.value = liveWaiting
                writeFiles(KEY_WAITING, liveWaiting)
            }
            val liveIgnored = _ignored.value.filterNot { it.path in gone }
            if (liveIgnored.size != _ignored.value.size) {
                _ignored.value = liveIgnored
                writeFiles(KEY_IGNORED, liveIgnored)
            }
            if (offered.removeAll(gone)) {
                writeStrings(KEY_OFFERED, offered)
            }
        }
    }

    /**
     * True only when the file is definitely gone. If the folder it lived in is
     * not readable, the volume is away rather than the file deleted.
     */
    private fun deleted(path: String): Boolean {
        val file = File(path)
        if (file.exists()) return false
        val parent = file.parentFile ?: return false
        return parent.isDirectory && parent.canRead()
    }

    // ---- watching ----------------------------------------------------------

    fun setWatching(on: Boolean) {
        _watching.value = on
        prefs.edit().putBoolean(KEY_WATCHING, on).apply()
    }

    /** The folder the picker should reopen at. */
    var destination: Uri?
        get() = prefs.getString(KEY_DESTINATION, null)?.let(Uri::parse)
        set(value) {
            prefs.edit().putString(KEY_DESTINATION, value?.toString()).apply()
        }

    /**
     * The last notification tap that was acted on. Android hands an activity its
     * launch intent again when the process is recreated, so without this the
     * same tap reopens the folder picker days later.
     */
    var lastHandledNotification: String?
        get() = prefs.getString(KEY_LAST_NOTIFICATION, null)
        set(value) {
            prefs.edit().putString(KEY_LAST_NOTIFICATION, value).apply()
        }

    // ---- naming suggestions ------------------------------------------------

    private val _suggestions = MutableStateFlow(
        SuggestionSettings(
            apiKey = prefs.getString(KEY_API_KEY, "").orEmpty(),
            enabled = prefs.getBoolean(KEY_SUGGESTIONS, false),
            library = prefs.getString(KEY_LIBRARY, null)?.let(Uri::parse),
        )
    )
    val suggestions: StateFlow<SuggestionSettings> = _suggestions.asStateFlow()

    /**
     * The key lives in the app's private preferences, which no other app can
     * read. It is never logged, and never leaves the phone except as the
     * authorisation header on a request to Anthropic.
     */
    fun setApiKey(key: String) {
        val trimmed = key.trim()
        prefs.edit().putString(KEY_API_KEY, trimmed).apply()
        _suggestions.value = _suggestions.value.copy(apiKey = trimmed)
    }

    fun setSuggestionsEnabled(on: Boolean) {
        prefs.edit().putBoolean(KEY_SUGGESTIONS, on).apply()
        _suggestions.value = _suggestions.value.copy(enabled = on)
    }

    fun setLibrary(tree: Uri?) {
        prefs.edit().putString(KEY_LIBRARY, tree?.toString()).apply()
        _suggestions.value = _suggestions.value.copy(library = tree)
    }

    // ---- what is safe to clear up ------------------------------------------

    private val _filed = MutableStateFlow(Filed.listFromJson(prefs.getString(KEY_FILED, null)))

    /**
     * Originals that have a verified copy somewhere else, so the user can clear
     * them up. Magpie never removes them itself — this is only the knowledge of
     * which ones are redundant, which it has and the user does not.
     */
    val filed: StateFlow<List<Filed>> = _filed.asStateFlow()

    fun remember(entry: Filed) {
        synchronized(lock) {
            // One entry per original: filing the same file twice replaces the
            // note rather than listing it twice.
            val kept = _filed.value.filterNot { it.originalPath == entry.originalPath }
            saveFiled(kept + entry)
        }
    }

    /** Forget one, because the user has dealt with it or it is no longer true. */
    fun forgetFiled(originalPath: String) {
        synchronized(lock) {
            saveFiled(_filed.value.filterNot { it.originalPath == originalPath })
        }
    }

    /** Keep only the entries [stillTrue] vouches for. */
    fun keepFiled(stillTrue: List<Filed>) {
        synchronized(lock) { saveFiled(stillTrue) }
    }

    private fun saveFiled(entries: List<Filed>) {
        val capped = entries.takeLast(FILED_LIMIT)
        prefs.edit().putString(KEY_FILED, Filed.listToJson(capped)).apply()
        _filed.value = capped
    }

    // ---- problems ----------------------------------------------------------

    /**
     * Surface a failure. Repeats of the same message collapse into one entry,
     * so a folder that cannot be read on every sweep says so once.
     */
    fun report(message: String) {
        synchronized(lock) {
            if (_problems.value.none { it.message == message }) {
                _problems.value = _problems.value + Problem(nextProblemId++, message)
            }
        }
    }

    fun dismissProblem(id: Long) {
        synchronized(lock) {
            _problems.value = _problems.value.filterNot { it.id == id }
        }
    }

    // ---- storage -----------------------------------------------------------

    private fun readFiles(key: String, description: String): List<SpottedFile> {
        val array = readArray(key, description) ?: return emptyList()
        val out = ArrayList<SpottedFile>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val path = item.optString("path")
            if (path.isEmpty()) continue
            out += SpottedFile(
                path = path,
                name = item.optString("name", File(path).name),
                size = item.optLong("size", 0L),
                source = item.optString("source", "Downloads"),
                spottedAt = item.optLong("spottedAt", 0L),
            )
        }
        return out
    }

    private fun writeFiles(key: String, files: List<SpottedFile>) {
        val array = JSONArray()
        for (file in files) {
            array.put(
                JSONObject()
                    .put("path", file.path)
                    .put("name", file.name)
                    .put("size", file.size)
                    .put("source", file.source)
                    .put("spottedAt", file.spottedAt)
            )
        }
        prefs.edit().putString(key, array.toString()).apply()
    }

    private fun readStrings(key: String, description: String): List<String> {
        val array = readArray(key, description) ?: return emptyList()
        return (0 until array.length()).mapNotNull { array.optString(it).ifEmpty { null } }
    }

    private fun writeStrings(key: String, values: Collection<String>) {
        val array = JSONArray()
        for (value in values) array.put(value)
        prefs.edit().putString(key, array.toString()).apply()
    }

    private fun readBaselines(): MutableMap<String, Long> {
        val raw = prefs.getString(KEY_BASELINES, null) ?: return HashMap()
        return try {
            val json = JSONObject(raw)
            val out = HashMap<String, Long>(json.length())
            for (key in json.keys()) out[key] = json.optLong(key)
            out
        } catch (e: JSONException) {
            prefs.edit().remove(KEY_BASELINES).apply()
            startupProblems += "Magpie's record of when it started watching each folder " +
                "could not be read (${e.message ?: "it was not valid JSON"}). Watching starts " +
                "again from now, so anything already in those folders is treated as old."
            HashMap()
        }
    }

    private fun writeBaselines() {
        val json = JSONObject()
        for ((root, at) in baselines) json.put(root, at)
        prefs.edit().putString(KEY_BASELINES, json.toString()).apply()
    }

    private fun readArray(key: String, description: String): JSONArray? {
        val raw = prefs.getString(key, null) ?: return null
        return try {
            JSONArray(raw)
        } catch (e: JSONException) {
            prefs.edit().remove(key).apply()
            startupProblems += "Magpie's saved $description could not be read " +
                "(${e.message ?: "it was not valid JSON"}), so it has been emptied. " +
                "No files were touched — everything is still where it was."
            null
        }
    }
}
