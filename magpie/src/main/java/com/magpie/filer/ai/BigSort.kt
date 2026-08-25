package com.magpie.filer.ai

import com.magpie.filer.core.Grouping
import com.magpie.filer.core.Naming
import com.magpie.filer.core.Safety
import com.magpie.filer.core.TimeGroup
import com.magpie.filer.watch.SpottedFile

/**
 * The planning half of the one-button sort: which files the rules settle, which
 * belong together as a run, and how the rest are packed into requests.
 *
 * Nothing here talks to anything — it is arithmetic over names and timestamps,
 * so the whole shape of a big sort is testable without a phone or a network.
 * The asking is [Suggester.sortMany]; the copying is [com.magpie.filer.move.Filing].
 */
object BigSort {

    /**
     * A run needs this many files before it is treated as one thing. Below it,
     * files are decided one by one — three screenshots an hour apart are not an
     * event, and calling them one would name them as one.
     */
    const val RUN_MIN = 4

    /** One thing to decide: a single file, or a whole run that shares one decision. */
    sealed interface Entry {
        data class Single(val file: SpottedFile) : Entry
        data class Run(val group: TimeGroup) : Entry
    }

    /** How many files an entry stands for. */
    fun sizeOf(entry: Entry): Int = when (entry) {
        is Entry.Single -> 1
        is Entry.Run -> entry.group.size
    }

    data class Split(
        /** Settled instantly by the user's own rules — no request needed. */
        val ruled: List<Pair<SpottedFile, Rule>>,
        /** Everything else, runs kept whole, for Claude to decide. */
        val entries: List<Entry>,
    )

    /**
     * Divide [files] into what the rules already answer and what needs asking
     * about. Rules win over everything — each one is a decision the user made
     * personally — and a ruled file never joins a run, because its destination
     * is already fixed.
     */
    fun split(
        files: List<SpottedFile>,
        rules: List<Rule>,
        gap: Long = Grouping.DEFAULT_GAP_MILLIS,
        timeOf: (SpottedFile) -> Long = { it.spottedAt },
    ): Split {
        val ruled = ArrayList<Pair<SpottedFile, Rule>>()
        val loose = ArrayList<SpottedFile>()
        for (file in files) {
            val rule = Rules.match(file.name, rules)
            if (rule != null) ruled += file to rule else loose += file
        }

        val entries = ArrayList<Entry>()
        for (group in Grouping.byTime(loose, gap, timeOf)) {
            if (group.size >= RUN_MIN) {
                entries += Entry.Run(group)
            } else {
                group.files.forEach { entries += Entry.Single(it) }
            }
        }
        return Split(ruled, entries)
    }

    /**
     * Pack [entries] into request-sized batches. A run counts as one entry
     * however many files it holds — it is one decision — and a run is never
     * split across batches, because half an event deciding differently from the
     * other half is exactly the scattering this exists to prevent.
     */
    fun batches(entries: List<Entry>, limit: Int = Suggester.BATCH_LIMIT): List<List<Entry>> {
        if (entries.isEmpty()) return emptyList()
        val safeLimit = limit.coerceAtLeast(1)
        val out = ArrayList<List<Entry>>()
        var batch = ArrayList<Entry>()
        for (entry in entries) {
            if (batch.size >= safeLimit) {
                out += batch
                batch = ArrayList()
            }
            batch += entry
        }
        if (batch.isNotEmpty()) out += batch
        return out
    }

    /** No point creating more than this from one sort; past it the cure is the disease. */
    const val LONGEST_FOLDER_NAME = 60

    /**
     * A folder name Claude proposed, made safe to create — or null when nothing
     * usable is left of it. The same distrust a filename gets, plus the names a
     * folder must never take: the Duplicates folder's, and anything hidden.
     */
    fun usableFolderName(proposed: String): String? {
        val cleaned = Naming.sanitise(proposed).trim().take(LONGEST_FOLDER_NAME).trim()
        if (cleaned.isBlank()) return null
        if (cleaned.startsWith(".")) return null
        if (cleaned.equals(Safety.DUPLICATES_FOLDER, ignoreCase = true)) return null
        return cleaned
    }
}
