package com.magpie.filer.ai

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

/** One folder Claude is allowed to choose between. */
data class LibraryFolder(val name: String, val documentId: String)

sealed interface LibraryListing {
    data class Folders(val folders: List<LibraryFolder>) : LibraryListing
    data class Failed(val reason: String) : LibraryListing
}

/**
 * The folders directly inside the user's chosen library folder.
 *
 * Claude only ever picks from this list, and the list only ever contains
 * folders that really exist, so a suggested destination cannot be invented.
 */
object LibraryFolders {

    /** More than this and the prompt stops being worth its tokens. */
    private const val LIMIT = 80

    fun list(context: Context, treeUri: Uri): LibraryListing {
        val parent = try {
            DocumentsContract.getTreeDocumentId(treeUri)
        } catch (e: IllegalArgumentException) {
            return LibraryListing.Failed(
                "That library folder could not be read (${e.message ?: "not a folder Magpie can open"}). " +
                    "Choose it again."
            )
        }

        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parent)
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )

        val found = ArrayList<LibraryFolder>()
        try {
            context.contentResolver.query(children, columns, null, null, null)?.use { cursor ->
                while (cursor.moveToNext() && found.size < LIMIT) {
                    if (cursor.getString(2) != DocumentsContract.Document.MIME_TYPE_DIR) continue
                    val name = cursor.getString(1) ?: continue
                    val id = cursor.getString(0) ?: continue
                    found += LibraryFolder(name, id)
                }
            } ?: return LibraryListing.Failed(
                "Android returned nothing at all for the library folder. Choose it again."
            )
        } catch (e: SecurityException) {
            return LibraryListing.Failed(
                "Magpie no longer has permission to read the library folder " +
                    "(${e.message ?: "permission withdrawn"}). Choose it again."
            )
        } catch (e: IllegalArgumentException) {
            return LibraryListing.Failed(
                "The library folder could not be listed (${e.message ?: "no detail"}). Choose it again."
            )
        }

        return LibraryListing.Folders(found.sortedBy { it.name.lowercase() })
    }

    /** The URI to open the system picker at, so it lands on the suggested folder. */
    fun uriFor(treeUri: Uri, documentId: String): Uri? = try {
        DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
    } catch (e: IllegalArgumentException) {
        null
    }
}
