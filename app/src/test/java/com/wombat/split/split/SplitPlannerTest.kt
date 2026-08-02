package com.wombat.split.split

import com.wombat.split.split.SplitPlanner.PlannedPart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitPlannerTest {

    private val mb = 1024L * 1024L

    private fun file(path: String, sizeMb: Long) = SplitFile(path, sizeMb * mb)

    @Test
    fun `empty folder plans zero parts`() {
        val plan = SplitPlanner.plan(emptyList(), 29 * mb)
        assertEquals(0, plan.parts.size)
        assertEquals(0L, plan.totalBytes)
    }

    @Test
    fun `everything fits in one part when under the limit`() {
        val plan = SplitPlanner.plan(listOf(file("a.jpg", 10), file("b.jpg", 12)), 29 * mb)
        assertEquals(1, plan.parts.size)
        assertEquals(22 * mb, plan.parts[0].totalBytes)
    }

    @Test
    fun `splits across parts and no part exceeds the limit`() {
        val files = listOf(
            file("a", 20), file("b", 15), file("c", 10), file("d", 5),
        )
        val plan = SplitPlanner.plan(files, 29 * mb)
        assertEquals(2, plan.parts.size)
        assertTrue(plan.parts.all { it.totalBytes <= 29 * mb })
        // First-fit-decreasing: 20+5 and 15+10.
        val partsFiles = plan.parts.filterIsInstance<PlannedPart.Files>()
        assertEquals(setOf("a", "d"), partsFiles[0].files.map { it.relativePath }.toSet())
        assertEquals(setOf("b", "c"), partsFiles[1].files.map { it.relativePath }.toSet())
    }

    @Test
    fun `file larger than the limit is byte-chunked into limit-sized parts`() {
        val plan = SplitPlanner.plan(listOf(file("huge.zip", 40), file("ok.jpg", 5)), 29 * mb)
        val chunks = plan.parts.filterIsInstance<PlannedPart.Chunk>()
        assertEquals(2, chunks.size)
        assertEquals(1, plan.chunkedFileCount)

        assertEquals(0L, chunks[0].offset)
        assertEquals(29 * mb, chunks[0].length)
        assertEquals(29 * mb, chunks[1].offset)
        assertEquals(11 * mb, chunks[1].length)
        assertTrue(chunks.all { it.chunkCount == 2 && it.file.relativePath == "huge.zip" })
        // Chunks reassemble exactly.
        assertEquals(40 * mb, chunks.sumOf { it.length })
        // Whole-file parts come first, chunks after.
        assertTrue(plan.parts.first() is PlannedPart.Files)
    }

    @Test
    fun `exact multiple of the limit chunks with no remainder part`() {
        val plan = SplitPlanner.plan(listOf(file("big.bin", 58)), 29 * mb)
        val chunks = plan.parts.filterIsInstance<PlannedPart.Chunk>()
        assertEquals(2, chunks.size)
        assertTrue(chunks.all { it.length == 29 * mb })
    }

    @Test
    fun `no bytes are lost or duplicated`() {
        val files = (1..37).map { file("f$it", (it % 9 + 1).toLong()) } + file("big", 30)
        val plan = SplitPlanner.plan(files, 13 * mb)
        assertEquals(files.sumOf { it.size }, plan.totalBytes)
        val wholePaths = plan.parts.filterIsInstance<PlannedPart.Files>()
            .flatMap { it.files }.map { it.relativePath }
        assertEquals(wholePaths.size, wholePaths.toSet().size)
        assertTrue(plan.parts.all { it.totalBytes <= 13 * mb })
    }

    @Test
    fun `plan is deterministic for equal sizes`() {
        val files = listOf(file("b", 10), file("a", 10), file("c", 10))
        val p1 = SplitPlanner.plan(files, 29 * mb)
        val p2 = SplitPlanner.plan(files.shuffled(), 29 * mb)
        assertEquals(p1, p2)
    }
}
