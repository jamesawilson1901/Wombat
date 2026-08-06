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
import java.io.File

/** Something that went wrong, worded for the person holding the phone. */
data class Problem(val id: Long, val message: String)

/**
 * Everything Magpie remembers between runs, and the single source of truth the
 * watcher and the screen both read.
 *
 * Kept small on purpose: a waiting list, an ignored list, the set of files
 * already seen, and whether watching is on. There is no move history and no
 * undo stack — those are deliberately out of scope.
 */
class FileStore private constructor(private val prefs: SharedPreferences) {

    companion object {
        private const val PREFS = "magpie"
        private const val KEY_WAITING = "waiting"
        private const val KEY_IGNORED = "ignored"
        private const val KEY_SEEN = "seen"
        private const val KEY_WATCHING = "watching"
        private const val KEY_DESTINATION = "destination"

        /** Plenty for months of downloads, and pruned against the disk anyway. */
        private const val SEEN_LIMIT = 4_000

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
    private val seen = LinkedHashSet(readStrings(KEY_SEEN, "list of files already seen"))

    private val _problems = MutableStateFlow(
        startupProblems.mapIndexed { index, message -> Problem(index + 1L, message) }
    )
    val problems: StateFlow<List<Problem>> = _problems.asStateFlow()

    private var nextProblemId = startupProblems.size + 1L

    private val _watching = MutableStateFlow(prefs.getBoolean(KEY_WATCHING, false))
    val watching: StateFlow<Boolean> = _watching.asStateFlow()

    // ---- what has already been offered -------------------------------------

    fun isKnown(path: String): Boolean = synchronized(lock) { path in seen }

    /** Mark a file as old without offering it. This is how the baseline is built. */
    fun remember(path: String) {
        synchronized(lock) {
            if (seen.add(path)) {
                trimSeen()
                writeStrings(KEY_SEEN, seen)
            }
        }
    }

    fun rememberAll(paths: Collection<String>) {
        if (paths.isEmpty()) return
        synchronized(lock) {
            var changed = false
            for (path in paths) changed = seen.add(path) || changed
            if (changed) {
                trimSeen()
                writeStrings(KEY_SEEN, seen)
            }
        }
    }

    private fun trimSeen() {
        while (seen.size > SEEN_LIMIT) {
            seen.remove(seen.first())
        }
    }

    // ---- the waiting list --------------------------------------------------

    /**
     * Offer a newly settled file. True means it went onto the waiting list,
     * which is also the signal to post its notification. False means it was
     * already known, already waiting, or has been ignored.
     */
    fun offer(file: SpottedFile): Boolean = synchronized(lock) {
        when {
            file.path in seen -> false
            _waiting.value.any { it.path == file.path } -> false
            _ignored.value.any { it.path == file.path } -> false
            else -> {
                seen.add(file.path)
                trimSeen()
                writeStrings(KEY_SEEN, seen)
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

    fun forgetIgnored(path: String) {
        synchronized(lock) {
            val remaining = _ignored.value.filterNot { it.path == path }
            if (remaining.size != _ignored.value.size) {
                _ignored.value = remaining
                writeFiles(KEY_IGNORED, remaining)
            }
        }
    }

    /**
     * Drop entries whose file is no longer on disk. Files leave by other routes
     * — a file manager, the browser's own cleanup — and a list full of names
     * that lead nowhere is worse than an empty one.
     *
     * Touches the disk; call it from a background thread.
     */
    fun pruneMissing() {
        synchronized(lock) {
            val liveWaiting = _waiting.value.filter { File(it.path).exists() }
            if (liveWaiting.size != _waiting.value.size) {
                _waiting.value = liveWaiting
                writeFiles(KEY_WAITING, liveWaiting)
            }
            val liveIgnored = _ignored.value.filter { File(it.path).exists() }
            if (liveIgnored.size != _ignored.value.size) {
                _ignored.value = liveIgnored
                writeFiles(KEY_IGNORED, liveIgnored)
            }
            val liveSeen = seen.filterTo(LinkedHashSet()) { File(it).exists() }
            if (liveSeen.size != seen.size) {
                seen.clear()
                seen.addAll(liveSeen)
                writeStrings(KEY_SEEN, seen)
            }
        }
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
