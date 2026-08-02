package com.wombat.split.split

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
        assertEquals(setOf("a", "d"), plan.parts[0].files.map { it.relativePath }.toSet())
        assertEquals(setOf("b", "c"), plan.parts[1].files.map { it.relativePath }.toSet())
    }

    @Test
    fun `file larger than the limit gets its own flagged part`() {
        val plan = SplitPlanner.plan(listOf(file("huge.zip", 40), file("ok.jpg", 5)), 29 * mb)
        assertEquals(2, plan.parts.size)
        assertEquals(1, plan.oversizedCount)
        val flagged = plan.parts.single { it.exceedsLimit }
        assertEquals("huge.zip", flagged.files.single().relativePath)
    }

    @Test
    fun `no file is lost or duplicated`() {
        val files = (1..37).map { file("f$it", (it % 9 + 1).toLong()) }
        val plan = SplitPlanner.plan(files, 13 * mb)
        val planned = plan.parts.flatMap { it.files }.map { it.relativePath }
        assertEquals(files.size, planned.size)
        assertEquals(files.map { it.relativePath }.toSet(), planned.toSet())
        assertTrue(plan.parts.filterNot { it.exceedsLimit }.all { it.totalBytes <= 13 * mb })
    }

    @Test
    fun `plan is deterministic for equal sizes`() {
        val files = listOf(file("b", 10), file("a", 10), file("c", 10))
        val p1 = SplitPlanner.plan(files, 29 * mb)
        val p2 = SplitPlanner.plan(files.shuffled(), 29 * mb)
        assertEquals(
            p1.parts.map { part -> part.files.map { it.relativePath } },
            p2.parts.map { part -> part.files.map { it.relativePath } },
        )
    }
}
