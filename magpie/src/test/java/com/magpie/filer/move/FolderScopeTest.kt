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
 * The big sort files into many library subfolders without a picker round-trip,
 * by scoping the store to one subfolder. What matters is that the whole tested
 * filing engine — duplicates and all — lands inside the subfolder, and that
 * nothing leaks into the library root.
 */
class FolderScopeTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var downloads: File
    private lateinit var library: File
    private lateinit var inner: FakeDocumentStore

    private val volumes: List<String> get() = listOf(temp.root.absolutePath)

    @Before
    fun setUp() {
        downloads = temp.newFolder("Download")
        library = temp.newFolder("Library")
        inner = FakeDocumentStore(library, label = "Library")
    }

    private fun scoped(folder: String): FolderScope {
        val id = requireNotNull(inner.createFolder(null, folder))
        return FolderScope(inner, id, folder)
    }

    private fun source(name: String, content: String): File {
        val file = File(downloads, name)
        file.writeText(content)
        return file
    }

    @Test
    fun `a file lands inside the subfolder, not the library root`() {
        val outcome = Filing.file(
            source = source("holiday.jpg", "sunny"),
            originalName = "holiday.jpg",
            targetName = "holiday.jpg",
            store = scoped("PHOTOS"),
            volumes = volumes,
            destinationFolder = File(library, "PHOTOS"),
        )

        assertTrue(outcome.toString(), outcome is MoveOutcome.Copied)
        assertEquals("sunny", File(File(library, "PHOTOS"), "holiday.jpg").readText())
        assertFalse("nothing may land in the root", File(library, "holiday.jpg").exists())
    }

    @Test
    fun `a duplicate's Duplicates folder is made inside the subfolder`() {
        val photos = scoped("PHOTOS")
        File(File(library, "PHOTOS"), "snap.jpg").writeText("first")

        val outcome = Filing.file(
            source = source("snap.jpg", "second"),
            originalName = "snap.jpg",
            targetName = "snap.jpg",
            store = photos,
            volumes = volumes,
            destinationFolder = File(library, "PHOTOS"),
        )

        assertTrue(outcome.toString(), outcome is MoveOutcome.Duplicated)
        assertEquals(
            "second",
            File(File(File(library, "PHOTOS"), Safety.DUPLICATES_FOLDER), "snap.jpg").readText(),
        )
        assertFalse(
            "the root must not grow a Duplicates folder",
            File(library, Safety.DUPLICATES_FOLDER).exists(),
        )
    }

    @Test
    fun `the label names the subfolder so reports read right`() {
        val photos = scoped("PHOTOS")
        assertEquals("Library/PHOTOS", photos.label)
        assertEquals("Library/PHOTOS", photos.describe(null))
        assertEquals(
            "Library/PHOTOS/${Safety.DUPLICATES_FOLDER}",
            photos.describe("anything"),
        )
    }

    @Test
    fun `listing the scoped root lists the subfolder`() {
        val photos = scoped("PHOTOS")
        File(File(library, "PHOTOS"), "one.jpg").writeText("x")
        File(library, "rootfile.txt").writeText("y")

        val names = photos.list(null).map { it.name }

        assertEquals(listOf("one.jpg"), names)
    }

    @Test
    fun `document ids pass through so the copy can be found again later`() {
        val photos = scoped("PHOTOS")
        val outcome = Filing.file(
            source = source("keep.pdf", "content"),
            originalName = "keep.pdf",
            targetName = "keep.pdf",
            store = photos,
            volumes = volumes,
            destinationFolder = File(library, "PHOTOS"),
        ) as MoveOutcome.Copied

        // The id must resolve against the *inner* store, because that is what
        // later verification builds from the library tree.
        assertTrue(inner.exists(outcome.document))
    }
}
