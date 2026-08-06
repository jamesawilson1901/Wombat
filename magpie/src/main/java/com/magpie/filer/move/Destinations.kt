package com.magpie.filer.move

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile

/** Turning a tree URI into something worth showing a person. */
object Destinations {

    /**
     * The folder's own name. Cosmetic: if the provider will not say, this falls
     * back to the tail of the document id, and then to a plain phrase, rather
     * than putting a URI in front of the user.
     */
    fun label(context: Context, treeUri: Uri): String {
        val name = try {
            DocumentFile.fromTreeUri(context, treeUri)?.name
        } catch (e: IllegalArgumentException) {
            null
        }
        if (!name.isNullOrBlank()) return name

        val id = try {
            DocumentsContract.getTreeDocumentId(treeUri)
        } catch (e: IllegalArgumentException) {
            null
        } ?: return "the chosen folder"

        return id.substringAfterLast(':').substringAfterLast('/').ifBlank { id }
    }
}
