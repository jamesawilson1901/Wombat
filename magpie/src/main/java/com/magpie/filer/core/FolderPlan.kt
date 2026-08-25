package com.magpie.filer.core

/** One folder to make, as the run of names leading to it. */
data class PlannedFolder(val segments: List<String>) {
    /** What the user sees: "TRIAD_SHORT/04_SHOT_STILLS/S01_ARRIVAL". */
    val path: String get() = segments.joinToString("/")
    val name: String get() = segments.last()
    val depth: Int get() = segments.size
}

sealed interface PlanResult {

    /** [folders] are in the order they must be made: every parent before its children. */
    data class Ready(val folders: List<PlannedFolder>) : PlanResult

    /** Nothing will be made. [reason] says why, in words worth reading. */
    data class Rejected(val reason: String) : PlanResult
}

/**
 * Turns a pasted-out folder tree into a list of folders to make.
 *
 * The shape people actually write is an indented list, usually with a slash on
 * the end of each line:
 *
 * ```
 * TRIAD_SHORT/
 *   01_CHARACTER_MASTERS/
 *   04_SHOT_STILLS/
 *     S01_ARRIVAL/
 * ```
 *
 * Nesting comes from indentation, and a line more indented than the one above
 * it is inside it. The width does not have to be consistent and tabs and spaces
 * can be mixed, because pasted text usually is: all that matters is whether one
 * line is further in than another. A line may also carry its own slashes
 * (`04_SHOT_STILLS/S01_ARRIVAL`), which nests just the same.
 *
 * This only ever *makes* folders. Nothing is renamed, nothing is written over,
 * and a folder that is already there is left exactly as it is.
 */
object FolderPlan {

    /** Enough for a real project; few enough that a stray paste cannot run away. */
    const val MAX_FOLDERS = 300

    /** Deeper than this is almost certainly a mistake in the text. */
    const val MAX_DEPTH = 10

    /** A tab counts as this many columns when working out how far in a line is. */
    private const val TAB_WIDTH = 4

    fun parse(text: String): PlanResult {
        if (text.isBlank()) {
            return PlanResult.Rejected("There is nothing to make — the box is empty.")
        }

        // Each entry is one open ancestor: how far in it was, and its full path.
        val open = ArrayList<Pair<Int, List<String>>>()
        val made = LinkedHashSet<String>()
        val folders = ArrayList<PlannedFolder>()

        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#")) continue

            val indent = indentOf(raw)
            while (open.isNotEmpty() && open.last().first >= indent) {
                open.removeAt(open.size - 1)
            }
            val parent = open.lastOrNull()?.second.orEmpty()

            val names = line.trim('/').split('/').map { it.trim() }.filter { it.isNotEmpty() }
            if (names.isEmpty()) continue

            var here = parent
            for (name in names) {
                val cleaned = clean(name)
                    ?: return PlanResult.Rejected(
                        "\"$name\" cannot be a folder name. Names cannot start with a dot, " +
                            "cannot be \".\" or \"..\", and cannot be made only of characters " +
                            "no folder accepts."
                    )
                here = here + cleaned
                if (here.size > MAX_DEPTH) {
                    return PlanResult.Rejected(
                        "\"${here.joinToString("/")}\" is more than $MAX_DEPTH folders deep. " +
                            "Check the indenting in the text."
                    )
                }
                val path = here.joinToString("/")
                if (made.add(path)) {
                    folders += PlannedFolder(here)
                    if (folders.size > MAX_FOLDERS) {
                        return PlanResult.Rejected(
                            "That is more than $MAX_FOLDERS folders. Magpie will not make " +
                                "that many at once — build it in parts."
                        )
                    }
                }
            }
            open += indent to here
        }

        if (folders.isEmpty()) {
            return PlanResult.Rejected("No folder names could be read out of that text.")
        }
        return PlanResult.Ready(folders)
    }

    /**
     * A folder name that is safe to ask a storage provider for, or null when
     * there is nothing usable left. The same cleaning a hand-typed name gets,
     * so a pasted tree cannot do what typing could not.
     */
    private fun clean(name: String): String? {
        val trimmed = name.trim().trimEnd('.', ' ')
        if (trimmed == "." || trimmed == "..") return null
        if (trimmed.startsWith(".")) return null
        val sanitised = Naming.sanitise(trimmed)
        return sanitised.takeIf { it.isNotBlank() }
    }

    private fun indentOf(raw: String): Int {
        var width = 0
        for (character in raw) {
            when (character) {
                ' ' -> width += 1
                '\t' -> width += TAB_WIDTH
                else -> return width
            }
        }
        return width
    }
}
