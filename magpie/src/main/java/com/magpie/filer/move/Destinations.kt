package com.magpie.filer.move

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import java.io.File

/** Turning a tree URI into something worth showing a person, or a real path. */
object Destinations {

    private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

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

    /**
     * Where the chosen folder actually is on disk, when that can be worked out.
     *
     * Only the system's own storage provider is understood — the one the picker
     * uses for anything on the phone or the card. Anything else returns null,
     * and callers fall back to treating the destination as opaque.
     */
    fun folderPath(treeUri: Uri): File? {
        if (treeUri.authority != EXTERNAL_STORAGE_AUTHORITY) return null
        val id = try {
            DocumentsContract.getTreeDocumentId(treeUri)
        } catch (e: IllegalArgumentException) {
            return null
        }
        val volume = id.substringBefore(':')
        val relative = id.substringAfter(':', "").trim('/')

        @Suppress("DEPRECATION")
        val root = if (volume == "primary") {
            Environment.getExternalStorageDirectory()
        } else {
            File("/storage/$volume")
        } ?: return null

        return if (relative.isEmpty()) root else File(root, relative)
    }
}
