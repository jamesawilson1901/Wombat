package com.magpie.filer.ai

/** What Claude proposed for one file. */
data class FilingSuggestion(
    /** The filename to use, extension included. */
    val name: String,
    /** One of the offered folder names, or empty when none of them fitted. */
    val folder: String,
    /** One short sentence saying why, shown to the user. */
    val reason: String,
)

/** The outcome of asking. A failure is a sentence, never an exception to swallow. */
sealed interface SuggestionResult {

    data class Ready(val suggestion: FilingSuggestion) : SuggestionResult

    /** [reason] is written to be read by the user, not by a developer. */
    data class Failed(val reason: String) : SuggestionResult
}
