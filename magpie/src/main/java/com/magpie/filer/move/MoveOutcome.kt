package com.magpie.filer.move

/**
 * What happened to one file. Every case is something the user is told, in
 * words.
 *
 * Filing is a **safe move**: copy first, verify byte counts at both ends, and
 * only then remove the original. The one thing Magpie ever removes is the
 * original of a move it has just verified — nothing at the destination, nothing
 * that failed, nothing it merely suspects. A move whose verification did not
 * complete leaves the original exactly where it was, and the type says which
 * happened so no code path can quietly assume otherwise.
 */
sealed interface MoveOutcome {

    val fileName: String

    /** Copied and verified byte-for-byte, then the original removed. */
    data class Copied(
        override val fileName: String,
        val savedAs: String,
        val destination: String,
        /** Where the original was — and still is, when [originalRemoved] is false. */
        val originalPath: String,
        /** The copy's document id, so it can be found again to check on. */
        val document: String,
        /**
         * True when the original was removed after verification — a real move.
         * False when removal failed, which turns the move into a copy: the
         * verified copy stands and the original is still in place.
         */
        val originalRemoved: Boolean = false,
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
        /** What was already there under this name, when the name was the clash. */
        val existingSize: Long?,
        val incomingSize: Long,
        /** The copy's document id, so it can be found again to check on. */
        val document: String,
        /**
         * True when the contents were compared and match, false when they were
         * compared and differ, null when they could not be compared at all.
         * A fingerprint settles it; a size only ever suggested.
         */
        val identical: Boolean? = null,
        /**
         * The file already in the folder with exactly these contents, when
         * there is one. It may be under a different name from this file — that
         * is a duplicate the name alone would never have caught.
         */
        val sameContentAs: String? = null,
        /** True when the original was removed after the copy verified. */
        val originalRemoved: Boolean = false,
        val notes: List<String> = emptyList(),
    ) : MoveOutcome {
        /** Whether the name was already taken, as opposed to only the contents. */
        val nameWasTaken: Boolean get() = existingSize != null
    }

    /** Nothing was moved. [reason] says why, plainly. The original is untouched. */
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
