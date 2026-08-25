package com.magpie.filer.move

import java.io.File
import java.io.IOException

/**
 * A [DocumentStore] backed by a real directory on disk, so filing can be tested
 * end to end — bytes and all — without a phone.
 *
 * The awkward behaviours of real storage providers are switchable, because they
 * are exactly the cases worth testing: a folder that will not list itself, one
 * that will not say how big a file is, one that renames what it is given.
 */
class FakeDocumentStore(
    private val root: File,
    override val label: String = "Test folder",
) : DocumentStore {

    /** Set to make [list] throw, like a provider that has lost permission. */
    var listFails: String? = null

    /** Set to make [createFile] return null, like a full or read-only card. */
    var refuseCreate = false

    /** Set to make [createFolder] return null. */
    var refuseFolders = false

    /** Set to make [size] return null, like a provider that will not say. */
    var hidesSize = false

    /** Set to make [size] throw, like a query that fails outright. */
    var sizeFails: String? = null

    /** Set to have the store save under a different name than it was asked. */
    var renamesTo: String? = null

    /** Set to have [write] stop short, like a card filling up mid-copy. */
    var truncateAt: Long? = null

    /** Every document id ever created, so a test can prove nothing vanished. */
    val created = mutableListOf<String>()

    private fun resolve(document: String?): File =
        if (document == null) root else File(root, document)

    override fun list(folder: String?): List<DocEntry> {
        listFails?.let { throw IOException(it) }
        val directory = resolve(folder)
        val children = directory.listFiles() ?: throw IOException("cannot list ${directory.name}")
        return children.map { child ->
            DocEntry(
                id = child.relativeTo(root).path,
                name = child.name,
                isFolder = child.isDirectory,
                size = if (child.isDirectory) null else child.length(),
            )
        }
    }

    override fun createFile(folder: String?, name: String, mime: String): String? {
        if (refuseCreate) return null
        val saved = renamesTo ?: name
        val target = File(resolve(folder), saved)
        target.parentFile?.mkdirs()
        target.createNewFile()
        val id = target.relativeTo(root).path
        created += id
        return id
    }

    override fun createFolder(folder: String?, name: String): String? {
        if (refuseFolders) return null
        val target = File(resolve(folder), name)
        if (!target.isDirectory && !target.mkdirs()) return null
        val id = target.relativeTo(root).path
        created += id
        return id
    }

    override fun write(document: String, source: File): WriteReport {
        val target = resolve(document)
        val bytes = source.readBytes()
        val limit = truncateAt
        val out = if (limit == null) bytes else bytes.copyOfRange(0, limit.toInt().coerceAtMost(bytes.size))
        target.writeBytes(out)
        return WriteReport(out.size.toLong(), emptyList())
    }

    override fun size(document: String): Long? {
        sizeFails?.let { throw IOException(it) }
        if (hidesSize) return null
        return resolve(document).length()
    }

    override fun name(document: String): String? = resolve(document).name

    override fun describe(folder: String?): String =
        if (folder == null) label else "$label/$folder"
}
