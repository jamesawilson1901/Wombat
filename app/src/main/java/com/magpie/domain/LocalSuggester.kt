package com.magpie.domain

import com.magpie.data.Decision
import com.magpie.data.PresetFolder

/**
 * §8.1 — the layer the popup opens with: free, instant, offline. Ranking:
 *  1. prior decisions matching extension + a shared filename token
 *  2. extension-only matches from prior decisions
 *  3. most recently used presets
 *  4. most frequently used presets
 */
object LocalSuggester {

    private val STOPWORDS = setOf("the", "and", "for", "with", "from", "final", "copy", "new")

    fun extensionOf(fileName: String): String =
        RenameHeuristics.splitExtension(fileName).second.removePrefix(".").lowercase()

    fun tokens(fileName: String): List<String> {
        val (base, _) = RenameHeuristics.splitExtension(fileName)
        return base.split(Regex("""[^A-Za-z0-9]+|(?<=[a-z])(?=[A-Z])|(?<=\D)(?=\d)|(?<=\d)(?=\D)"""))
            .map { it.lowercase() }
            .filter { it.length >= 3 && !it.all(Char::isDigit) && it !in STOPWORDS }
            .distinct()
    }

    fun tokensAsString(fileName: String): String = tokens(fileName).joinToString(" ")

    fun suggest(
        fileName: String,
        decisions: List<Decision>,
        presets: List<PresetFolder>,
        limit: Int = 3,
    ): List<String> {
        val ext = extensionOf(fileName)
        val fileTokens = tokens(fileName).toSet()
        val ranked = LinkedHashSet<String>()

        // 1. extension + shared token, strongest match first (more shared
        //    tokens, then more recent).
        decisions
            .filter { it.extension == ext && it.tokens.split(' ').any { t -> t.isNotEmpty() && t in fileTokens } }
            .sortedWith(
                compareByDescending<Decision> { d -> d.tokens.split(' ').count { it in fileTokens } }
                    .thenByDescending { it.timestamp }
            )
            .forEach { ranked.add(it.chosenFolder) }

        // 2. extension-only matches, most recent first.
        decisions
            .filter { it.extension == ext }
            .sortedByDescending { it.timestamp }
            .forEach { ranked.add(it.chosenFolder) }

        // 3 + 4. recency then frequency across presets.
        presets.sortedByDescending { it.lastUsed }.filter { it.lastUsed > 0 }.forEach { ranked.add(it.path) }
        presets.sortedByDescending { it.useCount }.forEach { ranked.add(it.path) }
        presets.forEach { ranked.add(it.path) }

        // Decisions can reference folders that were since deleted as presets;
        // only ever surface current presets.
        val valid = presets.map { it.path }.toSet()
        return ranked.filter { it in valid }.take(limit)
    }
}
