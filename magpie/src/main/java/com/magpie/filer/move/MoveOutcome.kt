package com.magpie.filer.move

/**
 * What happened to one file. Every case is something the user is told, in
 * words.
 *
 * There is no "moved" case, because Magpie never moves anything: filing is
 * copying, and the original stays exactly where it was. That is the fail-safe,
 * and it is spelled out in the type so no future code path can quietly assume
 * otherwise.
 */
sealed interface MoveOutcome {

    val fileName: String

    /** Copied and verified byte-for-byte. The original is still in place. */
    data class Copied(
        override val fileName: String,
        val savedAs: String,
        val destination: String,
        /** Where the original still is, because it was not touched. */
        val originalPath: String,
        val notes: List<String> = emptyList(),
    ) : MoveOutcome

    /**
     * Something of that name was already in the chosen folder, so the copy went
     * into a Duplicates folder inside it rather than over the top of anything.
     */
    data class Duplicated(
        override val fileName: String,
        val savedAs: String,
        val destination: String,
        /** The folder the copy actually landed in, for the user to go and look. */
        val duplicatesFolder: String,
        val originalPath: String,
        /** What was already there, so the user can judge whether it is the same file. */
        val existingSize: Long?,
        val incomingSize: Long,
        val notes: List<String> = emptyList(),
    ) : MoveOutcome {
        /** Same name and same size: almost certainly the same file twice. */
        val looksIdentical: Boolean get() = existingSize == incomingSize
    }

    /** Nothing was copied. [reason] says why, plainly. Nothing was deleted. */
    data class Failed(
        override val fileName: String,
        val reason: String,
    ) : MoveOutcome

    /**
     * Refused before anything was read or written, because a protected path was
     * involved. This is the fail-safe speaking, not a fault.
     */
    data class Refused(
        override val fileName: String,
        val reason: String,
    ) : MoveOutcome

    /** Asked to file something into the folder it is already in, under its own name. */
    data class Unchanged(
        override val fileName: String,
        val destination: String,
    ) : MoveOutcome
}
