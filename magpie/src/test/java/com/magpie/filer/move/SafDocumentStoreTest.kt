package com.magpie.filer.move

import android.Manifest
import android.content.Context
import android.content.pm.ProviderInfo
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import com.magpie.filer.core.Safety
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

/**
 * The Storage Access Framework glue, against a real [android.provider.DocumentsProvider].
 *
 * This is the part that used to be untestable: every method of
 * [SafDocumentStore] is one `DocumentsContract` call, and those calls only
 * mean anything with a provider on the other end. Robolectric runs the real
 * Android framework on the JVM, and [TestDocumentsProvider] is a genuine
 * provider backed by a temporary directory, so these exercise the code as
 * written — tree URIs, document ids, cursors, file descriptors and all.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SafDocumentStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var root: File
    private lateinit var treeUri: Uri
    private lateinit var store: SafDocumentStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        root = temp.newFolder("destination")
        TestDocumentsProvider.reset(root)

        // DocumentsProvider.attachInfo refuses to start unless all four of
        // these are set — it is exported, it grants URI permissions, and both
        // sides are guarded by MANAGE_DOCUMENTS. That is the real framework
        // check, not a Robolectric quirk, so the provider under test is set up
        // exactly as Android would demand of a real one.
        val info = ProviderInfo().apply {
            authority = TestDocumentsProvider.AUTHORITY
            exported = true
            grantUriPermissions = true
            readPermission = Manifest.permission.MANAGE_DOCUMENTS
            writePermission = Manifest.permission.MANAGE_DOCUMENTS
        }
        Robolectric.buildContentProvider(TestDocumentsProvider::class.java).create(info)
        treeUri = DocumentsContract.buildTreeDocumentUri(
            TestDocumentsProvider.AUTHORITY,
            TestDocumentsProvider.ROOT_ID,
        )
        store = SafDocumentStore(context, treeUri, "Papers")
    }

    @After
    fun tearDown() {
        TestDocumentsProvider.root = null
    }

    // ---- listing -----------------------------------------------------------

    @Test
    fun `an empty folder lists as empty, not as a failure`() {
        assertEquals(emptyList<DocEntry>(), store.list(null))
    }

    @Test
    fun `files and folders come back with names, kinds and sizes`() {
        File(root, "one.pdf").writeText("12345")
        File(root, "Sub").mkdirs()

        val entries = store.list(null).sortedBy { it.name }

        assertEquals(2, entries.size)
        val folder = entries.first { it.name == "Sub" }
        val document = entries.first { it.name == "one.pdf" }
        assertTrue(folder.isFolder)
        assertFalse(document.isFolder)
        assertEquals(5L, document.size)
    }

    @Test
    fun `a provider that answers nothing throws rather than looking empty`() {
        File(root, "here.pdf").writeText("x")
        TestDocumentsProvider.failQueries = true

        // An empty list would tell Filing that no name is taken, which is
        // exactly the wrong thing to believe before writing.
        try {
            store.list(null)
            throw AssertionError("expected the listing to fail loudly")
        } catch (e: IOException) {
            assertNotNull(e.message)
        }
    }

    @Test
    fun `a size the provider will not report comes back as null, not zero`() {
        File(root, "quiet.pdf").writeText("12345")
        TestDocumentsProvider.hideSizes = true

        assertNull(store.list(null).single().size)
    }

    // ---- creating ----------------------------------------------------------

    @Test
    fun `creating a file really makes one`() {
        val id = store.createFile(null, "new.pdf", "application/pdf")

        assertNotNull(id)
        assertTrue(File(root, "new.pdf").isFile)
    }

    @Test
    fun `creating a folder really makes one`() {
        val id = store.createFolder(null, Safety.DUPLICATES_FOLDER)

        assertNotNull(id)
        assertTrue(File(root, Safety.DUPLICATES_FOLDER).isDirectory)
    }

    @Test
    fun `a file can be created inside a folder that was just created`() {
        val folder = requireNotNull(store.createFolder(null, Safety.DUPLICATES_FOLDER))

        val id = store.createFile(folder, "inside.pdf", "application/pdf")

        assertNotNull(id)
        assertTrue(File(File(root, Safety.DUPLICATES_FOLDER), "inside.pdf").isFile)
    }

    @Test
    fun `a provider that refuses to create returns null rather than throwing`() {
        TestDocumentsProvider.refuseCreates = true
        assertNull(store.createFile(null, "nope.pdf", "application/pdf"))
    }

    // ---- writing and reading back ------------------------------------------

    @Test
    fun `bytes written through a file descriptor really land`() {
        val source = temp.newFile("source.bin")
        source.writeText("the quick brown fox")
        val id = requireNotNull(store.createFile(null, "copy.bin", "application/octet-stream"))

        val report = store.write(id, source)

        assertEquals(19L, report.written)
        assertEquals("the quick brown fox", File(root, "copy.bin").readText())
    }

    @Test
    fun `a large file is written whole, across buffer boundaries`() {
        // Bigger than the 256 KiB copy buffer, so the loop runs many times.
        val bytes = ByteArray(700_000) { (it % 251).toByte() }
        val source = temp.newFile("big.bin")
        source.writeBytes(bytes)
        val id = requireNotNull(store.createFile(null, "big-copy.bin", "application/octet-stream"))

        val report = store.write(id, source)

        assertEquals(700_000L, report.written)
        assertTrue(
            "every byte must survive the copy",
            bytes.contentEquals(File(root, "big-copy.bin").readBytes()),
        )
    }

    @Test
    fun `an empty file copies without complaint`() {
        val source = temp.newFile("empty.bin")
        val id = requireNotNull(store.createFile(null, "empty-copy.bin", "application/octet-stream"))

        assertEquals(0L, store.write(id, source).written)
    }

    @Test
    fun `the size read back matches what was written`() {
        val source = temp.newFile("sized.bin")
        source.writeText("0123456789")
        val id = requireNotNull(store.createFile(null, "sized-copy.bin", "application/octet-stream"))
        store.write(id, source)

        assertEquals(10L, store.size(id))
    }

    @Test
    fun `the name read back is the name the provider used`() {
        val id = requireNotNull(store.createFile(null, "asked.pdf", "application/pdf"))
        assertEquals("asked.pdf", store.name(id))
    }

    @Test
    fun `a provider that renames the file is caught by reading the name back`() {
        TestDocumentsProvider.renameTo = "provider-chose.pdf"

        val id = requireNotNull(store.createFile(null, "asked.pdf", "application/pdf"))

        assertEquals("provider-chose.pdf", store.name(id))
        assertTrue(File(root, "provider-chose.pdf").isFile)
        assertFalse(File(root, "asked.pdf").exists())
    }

    @Test
    fun `a size the provider hides comes back null so the caller can say so`() {
        val source = temp.newFile("hidden.bin")
        source.writeText("12345")
        val id = requireNotNull(store.createFile(null, "hidden-copy.bin", "application/octet-stream"))
        store.write(id, source)
        TestDocumentsProvider.hideSizes = true

        assertNull(store.size(id))
    }

    // ---- the whole thing, through Filing -----------------------------------

    @Test
    fun `filing end to end over real SAF leaves the original and copies the bytes`() {
        val downloads = temp.newFolder("Download")
        val original = File(downloads, "invoice.pdf")
        original.writeText("real content")

        val outcome = Filing.file(
            source = original,
            originalName = "invoice.pdf",
            targetName = "invoice.pdf",
            store = store,
            volumes = listOf(temp.root.absolutePath),
            destinationFolder = null,
        )

        assertTrue(outcome.toString(), outcome is MoveOutcome.Copied)
        assertTrue("the original must survive", original.exists())
        assertEquals("real content", File(root, "invoice.pdf").readText())
    }

    @Test
    fun `a duplicate over real SAF goes to the Duplicates folder and overwrites nothing`() {
        File(root, "report.pdf").writeText("already here")
        val downloads = temp.newFolder("Download")
        val original = File(downloads, "report.pdf")
        original.writeText("the new one")

        val outcome = Filing.file(
            source = original,
            originalName = "report.pdf",
            targetName = "report.pdf",
            store = store,
            volumes = listOf(temp.root.absolutePath),
            destinationFolder = null,
        )

        assertTrue(outcome.toString(), outcome is MoveOutcome.Duplicated)
        assertEquals("already here", File(root, "report.pdf").readText())
        assertEquals(
            "the new one",
            File(File(root, Safety.DUPLICATES_FOLDER), "report.pdf").readText(),
        )
        assertTrue("the original must survive", original.exists())
    }

    @Test
    fun `filing twice over real SAF reuses the one Duplicates folder`() {
        File(root, "twice.pdf").writeText("already here")
        val downloads = temp.newFolder("Download")

        repeat(2) { round ->
            val original = File(downloads, "twice.pdf")
            original.writeText("copy $round")
            Filing.file(
                source = original,
                originalName = "twice.pdf",
                targetName = "twice.pdf",
                store = store,
                volumes = listOf(temp.root.absolutePath),
                destinationFolder = null,
            )
        }

        assertEquals(1, root.listFiles()!!.count { it.isDirectory })
        assertEquals("already here", File(root, "twice.pdf").readText())
    }
}
