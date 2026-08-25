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

    // ---- room to land -------------------------------------------------------

    @Test
    fun `a destination without room refuses before a single byte is copied`() {
        val original = source("big.bin", "0123456789")

        val outcome = Filing.file(
            source = original,
            originalName = original.name,
            targetName = original.name,
            store = store,
            volumes = volumes,
            destinationFolder = destination,
            freeSpaceOf = { 5L },
        )

        assertTrue(outcome.toString(), outcome is MoveOutcome.Failed)
        val reason = (outcome as MoveOutcome.Failed).reason
        assertTrue(reason, reason.contains("not enough room"))
        assertFalse(
            "nothing may be created when there is no room for it",
            File(destination, "big.bin").exists(),
        )
        assertTrue(original.exists())
    }

    @Test
    fun `a nearly full destination is refused even for a small file`() {
        // The margin exists for filesystem overhead and whoever else is
        // writing; a volume with only a few hundred bytes left is out of room
        // for practical purposes whatever the file's size.
        val original = source("tiny.txt", "hi")

        val outcome = Filing.file(
            source = original,
            originalName = original.name,
            targetName = original.name,
            store = store,
            volumes = volumes,
            destinationFolder = destination,
            freeSpaceOf = { 500L },
        )

        assertTrue(outcome.toString(), outcome is MoveOutcome.Failed)
    }

    @Test
    fun `enough room lets the copy proceed`() {
        val original = source("fits.txt", "0123456789")

        val outcome = Filing.file(
            source = original,
            originalName = original.name,
            targetName = original.name,
            store = store,
            volumes = volumes,
            destinationFolder = destination,
            freeSpaceOf = { 10L + Filing.SPACE_MARGIN_BYTES },
        )

        assertTrue(outcome.toString(), outcome is MoveOutcome.Copied)
    }

    @Test
    fun `a volume that will not say how much room it has is not refused on a guess`() {
        // File.usableSpace reports zero when it cannot tell, which is not the
        // same as full — refusing on it would block filing for no real reason.
        val original = source("unknown.txt", "content")

        val outcome = Filing.file(
            source = original,
            originalName = original.name,
            targetName = original.name,
            store = store,
            volumes = volumes,
            destinationFolder = destination,
            freeSpaceOf = { 0L },
        )

        assertTrue(outcome.toString(), outcome is MoveOutcome.Copied)
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
    fun `a duplicate with the same contents is known to be identical, not guessed`() {
        File(destination, "same.pdf").writeText("12345")
        val original = source("same.pdf", "12345")

        val outcome = file(original) as MoveOutcome.Duplicated

        assertEquals(true, outcome.identical)
        assertEquals("same.pdf", outcome.sameContentAs)
        assertEquals(5L, outcome.incomingSize)
    }

    @Test
    fun `a name clash whose contents differ is not called identical`() {
        File(destination, "differs.pdf").writeText("12345")
        val original = source("differs.pdf", "1234567890")

        val outcome = file(original) as MoveOutcome.Duplicated

        assertEquals(false, outcome.identical)
        assertEquals(null, outcome.sameContentAs)
        assertEquals(5L, outcome.existingSize)
        assertEquals(10L, outcome.incomingSize)
    }

    @Test
    fun `same size but different contents under the same name is not identical`() {
        // The old size-only check called this a probable duplicate. It is not.
        File(destination, "sneaky.pdf").writeText("aaaaa")
        val original = source("sneaky.pdf", "bbbbb")

        val outcome = file(original) as MoveOutcome.Duplicated

        assertEquals(null, outcome.sameContentAs)
        assertFalse("same size must not be mistaken for same file", outcome.identical == true)
    }

    // ---- the duplicate a name would never have caught ----------------------

    @Test
    fun `the same file under a different name is caught and kept apart`() {
        File(destination, "already-filed.pdf").writeText("the very same bytes")
        val original = source("brand-new-name.pdf", "the very same bytes")

        val outcome = file(original)

        assertTrue(outcome.toString(), outcome is MoveOutcome.Duplicated)
        val duplicated = outcome as MoveOutcome.Duplicated
        assertEquals("already-filed.pdf", duplicated.sameContentAs)
        assertEquals(true, duplicated.identical)
        assertFalse("the name was free; only the contents clashed", duplicated.nameWasTaken)
        assertEquals(
            "the new one must be kept apart, not filed alongside",
            "the very same bytes",
            File(File(destination, Safety.DUPLICATES_FOLDER), "brand-new-name.pdf").readText(),
        )
        assertEquals(
            "what was already there must be untouched",
            "the very same bytes",
            File(destination, "already-filed.pdf").readText(),
        )
    }

    @Test
    fun `a different file of the same size is filed normally, not as a duplicate`() {
        File(destination, "other.pdf").writeText("aaaaaaaaaa")
        val original = source("mine.pdf", "bbbbbbbbbb")

        val outcome = file(original)

        assertTrue(outcome.toString(), outcome is MoveOutcome.Copied)
        assertEquals("bbbbbbbbbb", File(destination, "mine.pdf").readText())
    }

    @Test
    fun `a folder that will not report sizes simply files normally`() {
        // No sizes means no candidates to compare, which must not turn into a
        // wrong answer either way.
        File(destination, "twin.pdf").writeText("identical bytes")
        val original = source("fresh.pdf", "identical bytes")
        store.hidesSize = true

        val outcome = file(original)

        assertTrue(outcome.toString(), outcome is MoveOutcome.Copied)
    }

    @Test
    fun `a candidate that cannot be read is reported rather than assumed`() {
        File(destination, "locked.pdf").writeText("identical bytes")
        val original = source("fresh.pdf", "identical bytes")
        store.readFails = "permission denied"

        val outcome = file(original)

        assertTrue(outcome.toString(), outcome is MoveOutcome.Copied)
        val notes = (outcome as MoveOutcome.Copied).notes
        assertTrue(notes.toString(), notes.any { it.contains("could not be read to compare") })
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
