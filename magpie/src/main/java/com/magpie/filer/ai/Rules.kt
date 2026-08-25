package com.magpie.filer.ai

import com.magpie.filer.core.Naming
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * One thing you have told Magpie to do again.
 *
 * A rule matches on the two things that are actually in a filename: its
 * extension, and a word somewhere in it. Both are things you can look at and
 * predict. Nothing here learns quietly in the background — a rule exists
 * because you agreed to it, and it says exactly what it will do.
 */
data class Rule(
    /** Lowercase, with the dot: ".pdf". Empty means any extension. */
    val extension: String,
    /** Lowercase word that must appear in the name. Empty means any name. */
    val word: String,
    /** The folder name to file into. */
    val folder: String,
) {
    /** A rule that matches everything would file the whole world; it is not allowed. */
    val usable: Boolean get() = folder.isNotBlank() && (extension.isNotBlank() || word.isNotBlank())

    fun describe(): String = buildString {
        append("Anything ")
        if (word.isNotBlank()) append("with \"$word\" in the name ")
        if (extension.isNotBlank()) append("ending in $extension ")
        append("→ ").append(folder)
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("extension", extension)
        put("word", word)
        put("folder", folder)
    }
}

/**
 * The rules, and the one place that decides whether one applies.
 *
 * This is the cheap half of the suggestion feature: filing is repetitive, so
 * once you have told Magpie where this kind of file goes it can answer
 * instantly, offline, and without spending anything — and the API is left for
 * files that are genuinely new.
 */
object Rules {

    /** Words shorter than this match too much to be worth offering. */
    private const val SHORTEST_WORD = 3

    /**
     * The first rule that matches [fileName], or null.
     *
     * Rules are tried in order, so a more specific one added later can be moved
     * above a general one. A rule with both an extension and a word needs both.
     */
    fun match(fileName: String, rules: List<Rule>): Rule? {
        val extension = Naming.extension(fileName).lowercase()
        val lowered = fileName.lowercase()
        return rules.firstOrNull { rule ->
            if (!rule.usable) return@firstOrNull false
            if (rule.extension.isNotBlank() && rule.extension != extension) return@firstOrNull false
            if (rule.word.isNotBlank() && !lowered.contains(rule.word)) return@firstOrNull false
            true
        }
    }

    /**
     * A rule worth offering after filing [fileName] into [folder], or null when
     * there is nothing distinctive enough to be worth remembering.
     *
     * The word is taken from the filename only — never the date, the site it
     * came from, or the folder — which is the same rule the local tidying and
     * the suggestions follow.
     */
    fun suggestFor(fileName: String, folder: String): Rule? {
        if (folder.isBlank()) return null
        val extension = Naming.extension(fileName).lowercase()
        val word = distinctiveWord(fileName)
        val rule = Rule(extension = extension, word = word, folder = folder)
        return if (rule.usable) rule else null
    }

    /**
     * The longest word in the name that is made only of letters. Numbers,
     * hashes and dates are skipped: they are what makes one download different
     * from the next, not what makes it the same kind of thing.
     */
    private fun distinctiveWord(fileName: String): String {
        val stem = Naming.stem(fileName).lowercase()
        return stem
            .split(' ', '_', '-', '.', '(', ')', '[', ']', '+', ',')
            .filter { it.length >= SHORTEST_WORD && it.all { c -> c in 'a'..'z' } }
            .maxByOrNull { it.length }
            .orEmpty()
    }

    // ---- storage -----------------------------------------------------------

    fun toJson(rules: List<Rule>): String =
        JSONArray().apply { rules.forEach { put(it.toJson()) } }.toString()

    fun fromJson(stored: String?): List<Rule> {
        if (stored.isNullOrBlank()) return emptyList()
        val array = try {
            JSONArray(stored)
        } catch (e: JSONException) {
            return emptyList()
        }
        val found = ArrayList<Rule>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val rule = Rule(
                extension = item.optString("extension").lowercase(),
                word = item.optString("word").lowercase(),
                folder = item.optString("folder"),
            )
            if (rule.usable) found += rule
        }
        return found
    }
}
