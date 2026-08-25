package com.magpie.filer.move

import com.magpie.filer.core.Safety
import java.io.File

/**
 * A [DocumentStore] that is one subfolder of another store.
 *
 * The big sort files into many folders under the library in one run, without a
 * picker round-trip per folder. [Filing] always addresses "the destination
 * folder" as null; this remaps that null to [folderId], so the same tested
 * engine lands its files — and its Duplicates folder — inside the subfolder
 * instead of the library root.
 *
 * Document ids pass through untouched: they are the inner store's ids, so
 * anything recorded from an outcome can still be found through the inner store
 * later.
 */
class FolderScope(
    private val inner: DocumentStore,
    private val folderId: String,
    folderName: String,
) : DocumentStore {

    override val label: String = "${inner.label}/$folderName"

    private fun remap(folder: String?): String = folder ?: folderId

    override fun list(folder: String?): List<DocEntry> = inner.list(remap(folder))

    override fun createFile(folder: String?, name: String, mime: String): String? =
        inner.createFile(remap(folder), name, mime)

    override fun createFolder(folder: String?, name: String): String? =
        inner.createFolder(remap(folder), name)

    override fun write(document: String, source: File): WriteReport = inner.write(document, source)

    override fun open(document: String): java.io.InputStream = inner.open(document)

    override fun exists(document: String): Boolean = inner.exists(document)

    override fun size(document: String): Long? = inner.size(document)

    override fun name(document: String): String? = inner.name(document)

    override fun describe(folder: String?): String =
        if (folder == null) label else "$label/${Safety.DUPLICATES_FOLDER}"
}
