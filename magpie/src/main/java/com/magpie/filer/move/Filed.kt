package com.magpie.filer.move

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * One original that has a verified copy somewhere else.
 *
 * This is not a history and it is not an undo log: it records nothing about
 * what Magpie did, only where a redundant file is sitting right now. Entries
 * exist to be checked and then to go away — an entry whose original is gone,
 * or whose copy can no longer be found, is dropped rather than kept as a
 * memento.
 *
 * The point is the cost of the fail-safe. Magpie never deletes, so Downloads
 * fills up, and the only thing standing between you and clearing it out is
 * knowing which files are safe to remove. Magpie knows. This is how it says so.
 */
data class Filed(
    /** Where the original still is. */
    val originalPath: String,
    /** What the original is called, for the list. */
    val originalName: String,
    /** Size at the time it was copied, in bytes. */
    val size: Long,
    /** The folder the copy went to, in the words the user saw. */
    val destination: String,
    /** The name the copy was actually saved under. */
    val savedAs: String,
    /** The tree the copy lives in, so the copy can be looked for again. */
    val tree: String,
    /** The copy's document id within that tree. */
    val document: String,
) {

    fun toJson(): JSONObject = JSONObject().apply {
        put(KEY_PATH, originalPath)
        put(KEY_NAME, originalName)
        put(KEY_SIZE, size)
        put(KEY_DESTINATION, destination)
        put(KEY_SAVED_AS, savedAs)
        put(KEY_TREE, tree)
        put(KEY_DOCUMENT, document)
    }

    companion object {
        private const val KEY_PATH = "path"
        private const val KEY_NAME = "name"
        private const val KEY_SIZE = "size"
        private const val KEY_DESTINATION = "destination"
        private const val KEY_SAVED_AS = "savedAs"
        private const val KEY_TREE = "tree"
        private const val KEY_DOCUMENT = "document"

        /**
         * Read one back, or null when the stored shape is not what this version
         * of Magpie writes. A entry that cannot be read is dropped quietly:
         * losing a clear-up hint costs nothing, and it is not worth a message.
         */
        fun fromJson(json: JSONObject): Filed? {
            val path = json.optString(KEY_PATH).takeIf { it.isNotBlank() } ?: return null
            val document = json.optString(KEY_DOCUMENT).takeIf { it.isNotBlank() } ?: return null
            val tree = json.optString(KEY_TREE).takeIf { it.isNotBlank() } ?: return null
            return Filed(
                originalPath = path,
                originalName = json.optString(KEY_NAME).ifBlank { path.substringAfterLast('/') },
                size = json.optLong(KEY_SIZE, 0L),
                destination = json.optString(KEY_DESTINATION).ifBlank { "the folder you chose" },
                savedAs = json.optString(KEY_SAVED_AS).ifBlank { path.substringAfterLast('/') },
                tree = tree,
                document = document,
            )
        }

        fun listToJson(entries: List<Filed>): String =
            JSONArray().apply { entries.forEach { put(it.toJson()) } }.toString()

        fun listFromJson(stored: String?): List<Filed> {
            if (stored.isNullOrBlank()) return emptyList()
            val array = try {
                JSONArray(stored)
            } catch (e: JSONException) {
                return emptyList()
            }
            val found = ArrayList<Filed>(array.length())
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                fromJson(item)?.let { found += it }
            }
            return found
        }
    }
}
