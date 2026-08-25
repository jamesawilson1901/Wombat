package com.magpie.filer.core

import com.magpie.filer.watch.SpottedFile

/**
 * Files that belong together because they arrived together.
 *
 * [files] are in time order, oldest first.
 */
data class TimeGroup(
    val files: List<SpottedFile>,
    /** When the oldest file in the group is from. */
    val earliest: Long,
    /** When the newest file in the group is from. */
    val latest: Long,
) {
    val size: Int get() = files.size

    /** How long the whole run took, in milliseconds. */
    val span: Long get() = latest - earliest
}

/**
 * Puts a backlog into groups by when things happened.
 *
 * The problem this exists to solve: deciding where each file goes one file at a
 * time means deciding forty times about one event, and forty independent
 * answers disagree with each other. Photos from one afternoon end up spread
 * across three folders, each answer defensible on its own and the result a
 * mess.
 *
 * Grouping first turns forty decisions into one. Everything in a group shares a
 * destination and a name, so a group cannot scatter — not because the deciding
 * got cleverer, but because there is only one decision left to make.
 *
 * Nothing here talks to anything. It is arithmetic on timestamps: free,
 * instant, the same answer every time, and testable without a phone.
 */
object Grouping {

    /**
     * A new group starts when nothing has arrived for this long. Two hours is
     * long enough to hold an afternoon together and short enough to keep
     * yesterday separate from today.
     */
    const val DEFAULT_GAP_MILLIS = 2L * 60 * 60 * 1000

    /** Below this a gap splits almost every file into its own group. */
    const val SHORTEST_GAP_MILLIS = 60L * 1000

    /**
     * [files] split into runs, using [timeOf] for each file's moment and
     * starting a new run wherever the quiet between two files exceeds [gap].
     *
     * Groups come back oldest first, and so do the files inside them.
     */
    fun byTime(
        files: List<SpottedFile>,
        gap: Long = DEFAULT_GAP_MILLIS,
        timeOf: (SpottedFile) -> Long = { it.spottedAt },
    ): List<TimeGroup> {
        if (files.isEmpty()) return emptyList()
        val safeGap = gap.coerceAtLeast(SHORTEST_GAP_MILLIS)

        val sorted = files.sortedBy(timeOf)
        val groups = ArrayList<TimeGroup>()
        var run = ArrayList<SpottedFile>()
        var previous = timeOf(sorted.first())

        for (file in sorted) {
            val at = timeOf(file)
            if (run.isNotEmpty() && at - previous > safeGap) {
                groups += finish(run, timeOf)
                run = ArrayList()
            }
            run += file
            previous = at
        }
        if (run.isNotEmpty()) groups += finish(run, timeOf)
        return groups
    }

    private fun finish(run: List<SpottedFile>, timeOf: (SpottedFile) -> Long): TimeGroup =
        TimeGroup(
            files = run.toList(),
            earliest = timeOf(run.first()),
            latest = timeOf(run.last()),
        )

    /**
     * [groups] with the one at [index] and the one after it joined.
     *
     * Two hours is a guess about intent, and a guess is sometimes wrong — one
     * event either side of a lunch break comes back as two. Merging is how that
     * gets fixed, and it is the user's to do, not something to be inferred.
     */
    fun merge(groups: List<TimeGroup>, index: Int): List<TimeGroup> {
        if (index < 0 || index >= groups.size - 1) return groups
        val joined = groups[index].files + groups[index + 1].files
        val replacement = TimeGroup(
            files = joined,
            earliest = minOf(groups[index].earliest, groups[index + 1].earliest),
            latest = maxOf(groups[index].latest, groups[index + 1].latest),
        )
        return groups.subList(0, index) + replacement + groups.subList(index + 2, groups.size)
    }

    /**
     * [group] cut in two before [at], counting from zero.
     *
     * The other way a guess goes wrong: two different things on the same
     * afternoon arrive as one run.
     */
    fun split(group: TimeGroup, at: Int, timeOf: (SpottedFile) -> Long = { it.spottedAt }): List<TimeGroup> {
        if (at <= 0 || at >= group.size) return listOf(group)
        return listOf(
            finish(group.files.subList(0, at), timeOf),
            finish(group.files.subList(at, group.size), timeOf),
        )
    }

    /**
     * The names a group's files get when filed under one [stem], numbered in
     * time order: `WEDDING_01.jpg`, `WEDDING_02.jpg`, and so on. Returns each
     * file's path mapped to its new name.
     *
     * Each file keeps its own extension — a group can hold stills and video
     * together and each stays what it is. The number is padded to the width of
     * the group, so they sort correctly in any file manager.
     */
    fun numbered(stem: String, files: List<SpottedFile>): Map<String, String> {
        val cleaned = Naming.sanitise(Naming.stem(stem)).trim()
        if (cleaned.isBlank() || files.isEmpty()) return emptyMap()

        val width = maxOf(2, files.size.toString().length)
        return files.mapIndexed { index, file ->
            val number = (index + 1).toString().padStart(width, '0')
            file.path to "${cleaned}_$number${Naming.extension(file.name)}"
        }.toMap()
    }
}
