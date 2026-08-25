package com.magpie.filer.move

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import java.io.File
import java.io.FileNotFoundException

/**
 * A genuine [DocumentsProvider] backed by an ordinary directory.
 *
 * This is what makes the Storage Access Framework glue testable: with this
 * registered, `DocumentsContract.createDocument`, `buildChildDocumentsUriUsingTree`
 * and `openFileDescriptor` all take their real code paths, and
 * [SafDocumentStore] is exercised as written rather than described.
 *
 * A document id is [ROOT_ID] for the root itself, and "root/<relative path>"
 * for everything inside it.
 */
class TestDocumentsProvider : DocumentsProvider() {

    companion object {
        const val AUTHORITY = "com.magpie.filer.test.documents"

        /**
         * The document id of the root. It has to be something: a tree URI with
         * an empty document id gives "content://authority/tree/", which
         * DocumentsContract cannot parse, and every call throws
         * IllegalArgumentException. Real providers always name their root.
         */
        const val ROOT_ID = "root"

        /** Set before the provider is created; every instance shares it. */
        @Volatile
        var root: File? = null

        /** Set to make every query return null, like a provider in a bad way. */
        @Volatile
        var failQueries = false

        /** Set to leave the size column empty, as some real providers do. */
        @Volatile
        var hideSizes = false

        /** Set to have the provider save under a different name than asked. */
        @Volatile
        var renameTo: String? = null

        /** Set to refuse every creation, like a full or read-only card. */
        @Volatile
        var refuseCreates = false

        private val DOC_COLUMNS = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_FLAGS,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )

        fun reset(into: File) {
            root = into
            failQueries = false
            hideSizes = false
            renameTo = null
            refuseCreates = false
        }
    }

    private val base: File get() = requireNotNull(root) { "TestDocumentsProvider.root not set" }

    private fun fileFor(documentId: String): File =
        if (documentId == ROOT_ID) base else File(base, documentId.removePrefix("$ROOT_ID/"))

    private fun idFor(file: File): String =
        if (file.absolutePath == base.absolutePath) {
            ROOT_ID
        } else {
            "$ROOT_ID/" + file.relativeTo(base).path.replace(File.separatorChar, '/')
        }

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor =
        MatrixCursor(projection ?: arrayOf(DocumentsContract.Root.COLUMN_ROOT_ID))

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor? {
        if (failQueries) return null
        val file = fileFor(documentId)
        if (!file.exists()) throw FileNotFoundException(documentId)
        val cursor = MatrixCursor(projection ?: DOC_COLUMNS)
        addRow(cursor, file)
        return cursor
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        if (failQueries) return null
        val parent = fileFor(parentDocumentId)
        val cursor = MatrixCursor(projection ?: DOC_COLUMNS)
        parent.listFiles()?.sortedBy { it.name }?.forEach { addRow(cursor, it) }
        return cursor
    }

    private fun addRow(cursor: MatrixCursor, file: File) {
        val row = cursor.newRow()
        for (column in cursor.columnNames) {
            when (column) {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID -> row.add(column, idFor(file))
                DocumentsContract.Document.COLUMN_DISPLAY_NAME -> row.add(column, file.name)
                DocumentsContract.Document.COLUMN_MIME_TYPE -> row.add(
                    column,
                    if (file.isDirectory) DocumentsContract.Document.MIME_TYPE_DIR
                    else "application/octet-stream",
                )
                DocumentsContract.Document.COLUMN_SIZE -> row.add(
                    column,
                    if (hideSizes || file.isDirectory) null else file.length(),
                )
                DocumentsContract.Document.COLUMN_FLAGS -> row.add(
                    column,
                    DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE,
                )
                DocumentsContract.Document.COLUMN_LAST_MODIFIED -> row.add(column, file.lastModified())
                else -> row.add(column, null)
            }
        }
    }

    override fun createDocument(
        parentDocumentId: String,
        mimeType: String,
        displayName: String,
    ): String? {
        if (refuseCreates) return null
        val parent = fileFor(parentDocumentId)
        val name = renameTo ?: displayName
        val target = File(parent, name)
        if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
            if (!target.isDirectory && !target.mkdirs()) return null
        } else {
            target.parentFile?.mkdirs()
            target.createNewFile()
        }
        return idFor(target)
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        val file = fileFor(documentId)
        val flags = if (mode.contains("w")) {
            ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE
        } else {
            ParcelFileDescriptor.MODE_READ_ONLY
        }
        return ParcelFileDescriptor.open(file, flags)
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean =
        documentId.startsWith(parentDocumentId)
}
