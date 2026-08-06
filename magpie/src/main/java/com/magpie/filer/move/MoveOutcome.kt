package com.magpie.filer.move

/**
 * What happened to one file. Every case is something the user is told, in
 * words — including the awkward middle case where the copy worked and the
 * original could not be removed.
 */
sealed interface MoveOutcome {

    val fileName: String

    /** Copied, verified byte-for-byte, and the original removed. */
    data class Moved(
        override val fileName: String,
        val savedAs: String,
        val destination: String,
        val notes: List<String> = emptyList(),
    ) : MoveOutcome

    /** Copied and verified, but the original is still there. Two copies exist. */
    data class OriginalRemains(
        override val fileName: String,
        val savedAs: String,
        val destination: String,
        val originalPath: String,
        val reason: String,
    ) : MoveOutcome

    /** Nothing was moved and nothing was deleted. [reason] says why, plainly. */
    data class Failed(
        override val fileName: String,
        val reason: String,
    ) : MoveOutcome

    /** Asked to file something into the folder it is already in, under its own name. */
    data class Unchanged(
        override val fileName: String,
        val destination: String,
    ) : MoveOutcome
}
