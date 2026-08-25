package com.magpie.filer.ai

import com.magpie.filer.core.Safety
import com.magpie.filer.watch.SpottedFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The planning half of the one-button sort: pure decisions over names and
 * timestamps, provable without a phone.
 */
class BigSortTest {

    private var counter = 0

    private fun spotted(name: String, at: Long = 0L): SpottedFile = SpottedFile(
        path = "/storage/emulated/0/Download/${counter++}/$name",
        name = name,
        size = 100L,
        source = "Downloads",
        spottedAt = at,
    )

    private val invoiceRule = Rule(extension = ".pdf", word = "invoice", folder = "Invoices")

    // ---- splitting ---------------------------------------------------------

    @Test
    fun `a rule settles a file before anything else sees it`() {
        val ruled = spotted("invoice march.pdf")
        val loose = spotted("holiday.jpg")

        val split = BigSort.split(listOf(ruled, loose), listOf(invoiceRule))

        assertEquals(listOf(ruled to invoiceRule), split.ruled)
        assertEquals(listOf<BigSort.Entry>(BigSort.Entry.Single(loose)), split.entries)
    }

    @Test
    fun `files close together in time become one run`() {
        val minute = 60_000L
        val run = (0 until 5).map { spotted("IMG_$it.jpg", at = it * minute) }

        val split = BigSort.split(run, emptyList(), gap = 10 * minute)

        assertEquals(1, split.entries.size)
        val entry = split.entries.single() as BigSort.Entry.Run
        assertEquals(5, entry.group.size)
    }

    @Test
    fun `a handful of stragglers stays individual files, not a run`() {
        val hour = 3_600_000L
        val few = (0 until BigSort.RUN_MIN - 1).map { spotted("doc_$it.pdf", at = it * 100L) }
        val later = spotted("far.pdf", at = 10 * hour)

        val split = BigSort.split(few + later, emptyList(), gap = hour)

        assertTrue(split.entries.all { it is BigSort.Entry.Single })
        assertEquals(BigSort.RUN_MIN, split.entries.size)
    }

    @Test
    fun `a ruled file never joins a run, even one it arrived with`() {
        val minute = 60_000L
        val files = (0 until 5).map { spotted("shot_$it.jpg", at = it * minute) } +
            spotted("invoice.pdf", at = 2 * minute)

        val split = BigSort.split(files, listOf(invoiceRule), gap = 10 * minute)

        assertEquals(1, split.ruled.size)
        val run = split.entries.single() as BigSort.Entry.Run
        assertTrue(run.group.files.none { it.name == "invoice.pdf" })
    }

    @Test
    fun `sizeOf counts the files an entry stands for`() {
        val minute = 60_000L
        val run = (0 until 6).map { spotted("a_$it.jpg", at = it * minute) }
        val split = BigSort.split(run, emptyList(), gap = 10 * minute)

        assertEquals(6, BigSort.sizeOf(split.entries.single()))
        assertEquals(1, BigSort.sizeOf(BigSort.Entry.Single(spotted("one.pdf"))))
    }

    // ---- batching ----------------------------------------------------------

    @Test
    fun `batches respect the limit and keep every entry`() {
        val entries = (0 until 60).map { BigSort.Entry.Single(spotted("f_$it.txt")) }

        val batches = BigSort.batches(entries, limit = 25)

        assertEquals(3, batches.size)
        assertEquals(listOf(25, 25, 10), batches.map { it.size })
        assertEquals(entries, batches.flatten())
    }

    @Test
    fun `a run is one entry however many files it holds`() {
        val minute = 60_000L
        val bigRun = (0 until 40).map { spotted("r_$it.jpg", at = it * minute) }
        val split = BigSort.split(bigRun, emptyList(), gap = 10 * minute)

        val batches = BigSort.batches(split.entries, limit = 25)

        // Forty files, but one decision — one entry, one batch, never split.
        assertEquals(1, batches.size)
        assertEquals(1, batches.single().size)
    }

    @Test
    fun `no entries means no batches, not one empty one`() {
        assertEquals(emptyList<List<BigSort.Entry>>(), BigSort.batches(emptyList()))
    }

    // ---- folder names Claude proposes --------------------------------------

    @Test
    fun `a proposed folder name is cleaned like any other name`() {
        assertEquals("SCREENSHOTS", BigSort.usableFolderName("SCREENSHOTS"))
        assertEquals("PHOTOS 2019", BigSort.usableFolderName("PHOTOS/2019"))
    }

    @Test
    fun `nothing usable yields null, never an empty folder`() {
        assertNull(BigSort.usableFolderName(""))
        assertNull(BigSort.usableFolderName("   "))
        assertNull(BigSort.usableFolderName("///"))
    }

    @Test
    fun `a hidden folder is refused`() {
        assertNull(BigSort.usableFolderName(".config"))
    }

    @Test
    fun `the Duplicates folder's name is not for taking`() {
        assertNull(BigSort.usableFolderName(Safety.DUPLICATES_FOLDER))
        assertNull(BigSort.usableFolderName("duplicates"))
        assertNull(BigSort.usableFolderName("DUPLICATES"))
    }

    @Test
    fun `an absurdly long name is cut to something sane`() {
        val long = "A".repeat(300)
        assertEquals(BigSort.LONGEST_FOLDER_NAME, BigSort.usableFolderName(long)?.length)
    }
}
