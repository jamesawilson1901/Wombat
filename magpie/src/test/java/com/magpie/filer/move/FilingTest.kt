package com.magpie.filer.move

import com.magpie.filer.core.Safety
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Filing, end to end, with real bytes on real disk.
 *
 * The two things these exist to prove are the two the user asked for and that
 * nothing else could check: that **nothing is ever deleted**, and that a name
 * already in use sends the copy to a Duplicates folder instead of over the top
 * of what is there.
 */
class FilingTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var downloads: File
    private lateinit var destination: File
    private lateinit var store: FakeDocumentStore

    /** Pretend storage volumes, so Safety lets the temp paths through. */
    private val volumes: List<String> get() = listOf(temp.root.absolutePath)

    @Before
    fun setUpDirs() {
        downloads = temp.newFolder("Download")
        destination = temp.newFolder("Papers")
        store = FakeDocumentStore(destination, label = "Papers")
    }

    private fun source(name: String, content: String): File {
        val file = File(downloads, name)
        file.writeText(content)
        return file
    }

    private fun file(
        from: File,
        as_: String = from.name,
        into: FakeDocumentStore = store,
    ): MoveOutcome = Filing.file(
        source = from,
        originalName = from.name,
        targetName = as_,
        store = into,
        volumes = volumes,
        destinationFolder = destination,
    )

    // ---- the fail-safe: nothing is ever deleted -----------------------------

    @Test
    fun `the document store offers no way to delete anything`() {
        // The fail-safe is meant to hold by construction: filing code cannot
        // delete because there is nothing to call. If someone ever adds a
        // delete method to the store, this is where they find out that it was
        // load-bearing that there wasn't one.
        val offered = DocumentStore::class.java.methods.map { it.name }
        val destructive = offered.filter {
            val lower = it.lowercase()
            lower.contains("delete") || lower.contains("remove") || lower.contains("trash")
        }
        assertEquals("DocumentStore must offer no way to destroy anything", emptyList<String>(), destructive)
    }

    @Test
    fun `a successful copy leaves the original exactly where it was`() {
        val original = source("invoice.pdf", "hello")
        val outcome = file(original)

        assertTrue(outcome.toString(), outcome is MoveOutcome.Copied)
        assertTrue("the original must survive filing", original.exists())
        assertEquals("hello", original.readText())
        assertEquals(original.absolutePath, (outcome as MoveOutcome.Copied).originalPath)
    }

    @Test
    fun `the copy really contains the bytes`() {
        val original = source("notes.txt", "the quick brown fox")
        file(original)
        assertEquals("the quick brown fox", File(destination, "notes.txt").readText())
    }

    @Test
    fun `a copy that arrives short is reported and still deletes nothing`() {
        val original = source("big.bin", "0123456789")
        store.truncateAt = 4

        val outcome = file(original)

        assertTrue(outcome.toString(), outcome is MoveOutcome.Failed)
        assertTrue("the original must survive a failure", original.exists())
        assertTrue(
            "the part-written copy must be left, not removed",
            File(destination, "big.bin").exists()
        )
        val reason = (outcome as MoveOutcome.Failed).reason
        assertTrue(reason, reason.contains("Only 4 of 10 bytes"))
        assertTrue(reason, reason.contains("never deletes"))
    }

    @Test
    fun `a size the folder disagrees with is reported and deletes nothing`() {
        val original = source("odd.bin", "0123456789")
        // Write everything, then have the folder claim a different size.
        store.truncateAt = null
        val shrunk = FakeDocumentStore(destination, "Papers")
        shrunk.renamesTo = null
        val outcome = Filing.file(
            source = original,
            originalName = original.name,
            targetName = "odd.bin",
            store = object : DocumentStore by shrunk {
                override fun size(document: String): Long = 3L
            },
            volumes = volumes,
            destinationFolder = destination,
        )

        assertTrue(outcome.toString(), outcome is MoveOutcome.Failed)
        assertTrue(original.exists())
        assertTrue((outcome as MoveOutcome.Failed).reason.contains("says the copy is 3 bytes"))
    }

    // ---- duplicates --------------------------------------------------------

    @Test
    fun `a name already taken sends the copy to the Duplicates folder`() {
        File(destination, "report.pdf").writeText("the one already here")
        val original = source("report.pdf", "the new one")

        val outcome = file(original)

        assertTrue(outcome.toString(), outcome is MoveOutcome.Duplicated)
        assertEquals(
            "what was already there must not be touched",
            "the one already here",
            File(destination, "report.pdf").readText(),
        )
        assertEquals(
            "the new one",
            File(File(destination, Safety.DUPLICATES_FOLDER), "report.pdf").readText(),
        )
    }

    @Test
    fun `a duplicate of the same size is called out as probably identical`() {
        File(destination, "same.pdf").writeText("12345")
        val original = source("same.pdf", "12345")

        val outcome = file(original) as MoveOutcome.Duplicated

        assertTrue(outcome.looksIdentical)
        assertEquals(5L, outcome.existingSize)
        assertEquals(5L, outcome.incomingSize)
    }

    @Test
    fun `a duplicate of a different size is not called identical`() {
        File(destination, "differs.pdf").writeText("12345")
        val original = source("differs.pdf", "1234567890")

        val outcome = file(original) as MoveOutcome.Duplicated

        assertFalse(outcome.looksIdentical)
        assertEquals(5L, outcome.existingSize)
        assertEquals(10L, outcome.incomingSize)
    }

    @Test
    fun `an existing Duplicates folder is reused rather than a second one made`() {
        val duplicates = File(destination, Safety.DUPLICATES_FOLDER)
        duplicates.mkdirs()
        File(duplicates, "old.pdf").writeText("something already filed")
        File(destination, "again.pdf").writeText("here first")
        val original = source("again.pdf", "the new one")

        file(original)

        val folders = destination.listFiles()!!.filter { it.isDirectory }
        assertEquals("exactly one Duplicates folder", 1, folders.size)
        assertEquals(Safety.DUPLICATES_FOLDER, folders.single().name)
        assertTrue("what was in Duplicates already must survive", File(duplicates, "old.pdf").exists())
        assertEquals("the new one", File(duplicates, "again.pdf").readText())
    }

    @Test
    fun `two duplicates in a row both end up in the one Duplicates folder`() {
        File(destination, "twice.txt").writeText("original")
        val first = source("twice.txt", "second copy")
        file(first)

        // A different source file of the same name, filed again.
        val elsewhere = temp.newFolder("Other")
        val second = File(elsewhere, "twice.txt")
        second.writeText("third copy")
        val outcome = file(second)

        assertTrue(outcome.toString(), outcome is MoveOutcome.Duplicated)
        assertEquals(1, destination.listFiles()!!.count { it.isDirectory })
    }

    @Test
    fun `a Duplicates folder that cannot be made refuses rather than overwriting`() {
        File(destination, "blocked.pdf").writeText("do not lose me")
        val original = source("blocked.pdf", "incoming")
        store.refuseFolders = true

        val outcome = file(original)

        assertTrue(outcome.toString(), outcome is MoveOutcome.Failed)
        assertEquals("do not lose me", File(destination, "blocked.pdf").readText())
        assertTrue((outcome as MoveOutcome.Failed).reason.contains("Nothing was written over"))
    }

    @Test
    fun `a folder that will not list itself is never written into`() {
        val original = source("careful.pdf", "content")
        store.listFails = "permission withdrawn"

        val outcome = file(original)

        assertTrue(outcome.toString(), outcome is MoveOutcome.Failed)
        assertFalse(
            "nothing may be created in a folder Magpie could not read first",
            File(destination, "careful.pdf").exists(),
        )
        assertTrue((outcome as MoveOutcome.Failed).reason.contains("will not write into"))
    }

    // ---- the safety guard, through the real filing path ---------------------

    @Test
    fun `filing out of a system path is refused before anything is read`() {
        val outcome = Filing.file(
            source = File("/system/build.prop"),
            originalName = "build.prop",
            targetName = "build.prop",
            store = store,
            volumes = volumes,
            destinationFolder = destination,
        )

        assertTrue(outcome.toString(), outcome is MoveOutcome.Refused)
        assertFalse(File(destination, "build.prop").exists())
        assertTrue((outcome as MoveOutcome.Refused).reason.contains("will not read from there"))
    }

    @Test
    fun `filing into a path outside the known volumes is refused`() {
        val original = source("fine.pdf", "content")

        val outcome = Filing.file(
            source = original,
            originalName = original.name,
            targetName = original.name,
            store = store,
            volumes = volumes,
            destinationFolder = File("/system/etc"),
        )

        assertTrue(outcome.toString(), outcome is MoveOutcome.Refused)
        assertTrue((outcome as MoveOutcome.Refused).reason.contains("will not write there"))
    }

    // ---- the awkward middles -----------------------------------------------

    @Test
    fun `a folder that will not report a size says so rather than failing`() {
        val original = source("quiet.pdf", "content")
        store.hidesSize = true

        val outcome = file(original)

        assertTrue(outcome.toString(), outcome is MoveOutcome.Copied)
        val notes = (outcome as MoveOutcome.Copied).notes
        assertTrue(notes.toString(), notes.any { it.contains("did not report a size back") })
    }

    @Test
    fun `a folder that renames the file says what it actually called it`() {
        val original = source("wanted.pdf", "content")
        store.renamesTo = "wanted (1).pdf"

        val outcome = file(original) as MoveOutcome.Copied

        assertEquals("wanted (1).pdf", outcome.savedAs)
        assertTrue(outcome.notes.toString(), outcome.notes.any { it.contains("Saved as") })
    }

    @Test
    fun `a folder that refuses to create anything is reported`() {
        val original = source("nope.pdf", "content")
        store.refuseCreate = true

        val outcome = file(original)

        assertTrue(outcome.toString(), outcome is MoveOutcome.Failed)
        assertTrue((outcome as MoveOutcome.Failed).reason.contains("refused to create"))
        assertTrue(original.exists())
    }

    @Test
    fun `filing into the folder it is already in under the same name changes nothing`() {
        val alreadyThere = File(destination, "here.pdf")
        alreadyThere.writeText("content")

        val outcome = Filing.file(
            source = alreadyThere,
            originalName = "here.pdf",
            targetName = "here.pdf",
            store = store,
            volumes = volumes,
            destinationFolder = destination,
        )

        assertTrue(outcome.toString(), outcome is MoveOutcome.Unchanged)
        assertEquals("content", alreadyThere.readText())
    }

    @Test
    fun `an empty new name is refused and nothing is created`() {
        val original = source("named.pdf", "content")

        val outcome = file(original, as_ = "   ")

        assertTrue(outcome.toString(), outcome is MoveOutcome.Failed)
        assertTrue(original.exists())
    }

    @Test
    fun `a file that has gone by the time filing starts is reported`() {
        val original = source("vanished.pdf", "content")
        original.delete()

        val outcome = file(original)

        assertTrue(outcome.toString(), outcome is MoveOutcome.Failed)
        assertTrue((outcome as MoveOutcome.Failed).reason.contains("no longer at"))
    }
}
