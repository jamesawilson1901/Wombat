package com.magpie.filer.ai

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Magpie's settings as a file you can keep.
 *
 * Rules accrete real value — each one is a decision you made once and never
 * want to make again — and losing them to a reinstall or a new phone would
 * sting. This writes the portable ones down as JSON and reads them back.
 *
 * **The API key is never included.** A settings file gets copied to cloud
 * drives and pasted into chats; a credential does not belong in it. Neither
 * does the library folder: its permission grant is this-device-only and would
 * import as a folder Magpie cannot actually open.
 */
object SettingsFile {

    private const val VERSION = 1
    private const val KEY_MARK = "magpie"
    private const val KEY_RULES = "rules"
    private const val KEY_FOLDERS = "watchedFolders"
    private const val KEY_GAP = "groupGapMinutes"

    /** What travels: the settings that mean the same thing on any device. */
    data class Portable(
        val rules: List<Rule> = emptyList(),
        val watchedFolders: List<String> = emptyList(),
        val groupGapMinutes: Long? = null,
    )

    fun write(portable: Portable): String = JSONObject().apply {
        put(KEY_MARK, VERSION)
        put(KEY_RULES, JSONArray(Rules.toJson(portable.rules)))
        put(KEY_FOLDERS, JSONArray(portable.watchedFolders))
        portable.groupGapMinutes?.let { put(KEY_GAP, it) }
    }.toString(2)

    /**
     * Read a settings file back, or null when it is not one.
     *
     * Anything readable is kept and anything not is dropped — a file from a
     * newer or older Magpie yields what it can rather than nothing. A rule
     * that would match everything is dropped here too, the same as anywhere
     * else one might come from.
     */
    fun read(text: String?): Portable? {
        if (text.isNullOrBlank()) return null
        val json = try {
            JSONObject(text)
        } catch (e: JSONException) {
            return null
        }
        if (!json.has(KEY_MARK)) return null

        val folders = json.optJSONArray(KEY_FOLDERS)?.let { array ->
            (0 until array.length()).mapNotNull { index ->
                array.optString(index).takeIf { it.startsWith("/") }
            }
        }.orEmpty()

        val gap = if (json.has(KEY_GAP)) {
            json.optLong(KEY_GAP).takeIf { it > 0 }
        } else {
            null
        }

        return Portable(
            rules = Rules.fromJson(json.optJSONArray(KEY_RULES)?.toString()),
            watchedFolders = folders,
            groupGapMinutes = gap,
        )
    }
}
