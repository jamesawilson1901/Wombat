package com.magpie.filer.move

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import com.magpie.filer.core.Formatting
import com.magpie.filer.core.Naming
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.SyncFailedException

/**
 * [DocumentStore] over the Storage Access Framework: the real thing, and the
 * one part of filing that cannot be tested off a device.
 *
 * It is kept deliberately thin — every method is a single SAF call and no
 * decisions — so that what cannot be tested is also not where the thinking
 * happens. The thinking is in [Filing], which is tested.
 *
 * There is no delete method here, and no `deleteDocument` call. That is the
 * fail-safe: filing code cannot delete because there is nothing to call.
 */
class SafDocumentStore(
    private val context: Context,
    private val treeUri: Uri,
    override val label: String,
) : DocumentStore {

    private companion object {
        const val BUFFER_BYTES = 256 * 1024

        /**
         * Queries go through the Bundle-and-signal form of the API, not the
         * older selection/sortOrder one. DocumentsProvider refuses that older
         * shape outright — "Pre-Android-O query format not supported" — and
         * nothing here needs a selection anyway.
         */
        val NO_ARGS: Bundle? = null
        val NO_SIGNAL: CancellationSignal? = null
    }

    /** The destination folder itself, when a document id of null is given. */
    private fun uriFor(document: String?): Uri = if (document == null) {
        DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri),
        )
    } else {
        DocumentsContract.buildDocumentUriUsingTree(treeUri, document)
    }

    override fun list(folder: String?): List<DocEntry> {
        val parentId = DocumentsContract.getDocumentId(uriFor(folder))
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        val found = ArrayList<DocEntry>()
        context.contentResolver.query(uri, columns, NO_ARGS, NO_SIGNAL)?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0) ?: continue
                val name = cursor.getString(1) ?: continue
                found += DocEntry(
                    id = id,
                    name = name,
                    isFolder = cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR,
                    size = if (cursor.isNull(3)) null else cursor.getLong(3),
                )
            }
        } ?: throw IOException("the folder would not list its contents")
        return found
    }

    override fun createFile(folder: String?, name: String, mime: String): String? =
        idOfCreated(
            DocumentsContract.createDocument(
                context.contentResolver,
                uriFor(folder),
                realMimeFor(name, mime),
                name,
            )
        )

    override fun createFolder(folder: String?, name: String): String? =
        idOfCreated(
            DocumentsContract.createDocument(
                context.contentResolver,
                uriFor(folder),
                DocumentsContract.Document.MIME_TYPE_DIR,
                name,
            )
        )

    /**
     * The id of a document that was actually created, or null when it was not.
     *
     * A provider that refuses returns null from its own createDocument, but the
     * platform builds a document URI out of that null without checking first —
     * so what comes back is a perfectly well-formed URI whose document id is
     * the literal string "null". Taking that at face value would mean writing a
     * copy into a document that does not exist and only finding out later, so
     * it is treated as the refusal it actually is.
     */
    private fun idOfCreated(created: Uri?): String? {
        val id = created?.let { DocumentsContract.getDocumentId(it) }
        return if (id.isNullOrBlank() || id == "null") null else id
    }

    override fun write(document: String, source: File): WriteReport {
        val notes = mutableListOf<String>()
        var written = 0L

        val descriptor = context.contentResolver.openFileDescriptor(uriFor(document), "w")
            ?: throw IOException("the destination would not open for writing")

        // AutoCloseOutputStream owns the descriptor, so the file descriptor is
        // closed exactly once however this block exits.
        ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { output ->
            FileInputStream(source).use { input ->
                val buffer = ByteArray(BUFFER_BYTES)
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    if (read > 0) {
                        output.write(buffer, 0, read)
                        written += read
                    }
                }
                output.flush()
                try {
                    // Push it out of the page cache before the size is checked,
                    // so the check is of what actually landed.
                    descriptor.fileDescriptor.sync()
                } catch (e: SyncFailedException) {
                    notes += "The destination would not confirm the write reached the " +
                        "card (${e.message ?: "sync failed"}); the size was still checked."
                }
            }
        }

        return WriteReport(written, notes)
    }

    override fun open(document: String): java.io.InputStream =
        context.contentResolver.openInputStream(uriFor(document))
            ?: throw IOException("the document would not open for reading")

    override fun size(document: String): Long? =
        column(document, DocumentsContract.Document.COLUMN_SIZE)?.toLongOrNull()

    override fun name(document: String): String? =
        column(document, DocumentsContract.Document.COLUMN_DISPLAY_NAME)

    override fun describe(folder: String?): String =
        if (folder == null) label else "$label/${com.magpie.filer.core.Safety.DUPLICATES_FOLDER}"

    /**
     * Read one column back. Returns null when the provider has nothing to say;
     * throws when the query itself fails, so the caller can tell the difference
     * and say which happened.
     */
    private fun column(document: String, name: String): String? =
        context.contentResolver
            .query(uriFor(document), arrayOf(name), NO_ARGS, NO_SIGNAL)
            ?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
            }

    /**
     * The content type to create the document with. The table in [Formatting]
     * covers the common cases; anything else goes to Android's own list before
     * falling back, because a provider handed application/octet-stream may
     * decide to append an extension of its own and quietly rename the file.
     */
    private fun realMimeFor(name: String, offered: String): String {
        if (offered != Formatting.UNKNOWN_MIME) return offered
        val extension = Naming.extension(name).removePrefix(".").lowercase()
        if (extension.isEmpty()) return Formatting.UNKNOWN_MIME
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: Formatting.UNKNOWN_MIME
    }
}
