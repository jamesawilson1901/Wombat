package com.wombat.split.split

/** A file inside the source, identified by its path relative to it. */
data class SplitFile(val relativePath: String, val size: Long)

/**
 * Pure planning logic: pack files whole into parts that each stay under the
 * byte limit. First-fit-decreasing keeps part counts close to optimal while
 * staying deterministic.
 *
 * Files larger than the limit can't be packed whole, so they are byte-chunked:
 * the planner emits one [PlannedPart.Chunk] per limit-sized slice (`.001`,
 * `.002`, … on disk), keeping every produced part under the limit.
 */
object SplitPlanner {

    sealed interface PlannedPart {
        val totalBytes: Long

        data class Files(
            val files: List<SplitFile>,
            override val totalBytes: Long,
        ) : PlannedPart

        data class Chunk(
            val file: SplitFile,
            val chunkIndex: Int,
            val chunkCount: Int,
            val offset: Long,
            val length: Long,
        ) : PlannedPart {
            override val totalBytes: Long get() = length
        }
    }

    data class SplitPlan(
        val parts: List<PlannedPart>,
        val limitBytes: Long,
    ) {
        val totalBytes: Long get() = parts.sumOf { it.totalBytes }
        val chunkedFileCount: Int
            get() = parts.filterIsInstance<PlannedPart.Chunk>()
                .distinctBy { it.file.relativePath }
                .size
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
            PlannedPart.Files(
                files = bin.sortedBy { it.relativePath },
                totalBytes = binSizes[i],
            )
        }

        val chunked = oversized
            .sortedBy { it.relativePath }
            .flatMap { file ->
                val chunkCount = ((file.size + limitBytes - 1) / limitBytes).toInt()
                (0 until chunkCount).map { i ->
                    val offset = i.toLong() * limitBytes
                    PlannedPart.Chunk(
                        file = file,
                        chunkIndex = i,
                        chunkCount = chunkCount,
                        offset = offset,
                        length = minOf(limitBytes, file.size - offset),
                    )
                }
            }

        return SplitPlan(parts = packed + chunked, limitBytes = limitBytes)
    }
}
