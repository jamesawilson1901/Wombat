package com.wombat.split.split

/** A file inside the source folder, identified by its path relative to it. */
data class SplitFile(val relativePath: String, val size: Long)

/**
 * Pure planning logic: pack files whole into parts that each stay under the
 * byte limit. First-fit-decreasing keeps part counts close to optimal while
 * staying deterministic. Files larger than the limit can never fit; each gets
 * its own part flagged [SplitPart.exceedsLimit] so the UI can warn.
 */
object SplitPlanner {

    data class SplitPart(
        val files: List<SplitFile>,
        val totalBytes: Long,
        val exceedsLimit: Boolean,
    )

    data class SplitPlan(
        val parts: List<SplitPart>,
        val limitBytes: Long,
    ) {
        val totalBytes: Long get() = parts.sumOf { it.totalBytes }
        val oversizedCount: Int get() = parts.count { it.exceedsLimit }
    }

    fun plan(files: List<SplitFile>, limitBytes: Long): SplitPlan {
        require(limitBytes > 0) { "limitBytes must be > 0, was $limitBytes" }

        val sorted = files.sortedWith(
            compareByDescending<SplitFile> { it.size }.thenBy { it.relativePath }
        )

        val bins = mutableListOf<MutableList<SplitFile>>()
        val binSizes = mutableListOf<Long>()
        val oversized = mutableListOf<SplitFile>()

        for (file in sorted) {
            if (file.size > limitBytes) {
                oversized += file
                continue
            }
            val index = binSizes.indices.firstOrNull { binSizes[it] + file.size <= limitBytes }
            if (index == null) {
                bins += mutableListOf(file)
                binSizes += file.size
            } else {
                bins[index] += file
                binSizes[index] += file.size
            }
        }

        val packed = bins.mapIndexed { i, bin ->
            SplitPart(
                files = bin.sortedBy { it.relativePath },
                totalBytes = binSizes[i],
                exceedsLimit = false,
            )
        }
        val flagged = oversized.map { SplitPart(listOf(it), it.size, exceedsLimit = true) }

        return SplitPlan(parts = packed + flagged, limitBytes = limitBytes)
    }
}
