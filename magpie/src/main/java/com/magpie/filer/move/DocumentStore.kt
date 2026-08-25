package com.magpie.filer.move

import java.io.File

/** One thing sitting in a destination folder, as far as filing needs to know. */
data class DocEntry(
    val id: String,
    val name: String,
    val isFolder: Boolean,
    /** Null when the folder will not say, which is not the same as zero. */
    val size: Long?,
)

/** What a write actually did. [notes] are sentences for the user, not logs. */
data class WriteReport(val written: Long, val notes: List<String>)

/**
 * The destination folder, with the Storage Access Framework held at arm's
 * length.
 *
 * Filing rules — is this name taken, does a Duplicates folder need making, did
 * the right number of bytes arrive — are the part worth testing, and they are
 * the part that cannot be tested against a real SAF provider without a device.
 * So they talk to this instead, and the tests hand them a store backed by an
 * ordinary temporary directory.
 *
 * Every method that can fail throws rather than returning a quiet null, because
 * "the folder would not answer" and "the folder answered nothing" are different
 * things the user gets told apart.
 *
 * A document id of null always means the destination folder itself.
 */
interface DocumentStore {

    /** What the user sees this folder called. */
    val label: String

    /** @throws java.io.IOException when the folder cannot be listed at all. */
    fun list(folder: String?): List<DocEntry>

    /** Null when the folder refused, without saying why. */
    fun createFile(folder: String?, name: String, mime: String): String?

    /** Null when the folder refused, without saying why. */
    fun createFolder(folder: String?, name: String): String?

    /** Copy [source] into an already created document. */
    fun write(document: String, source: File): WriteReport

    /**
     * Read a document back, so its contents can be fingerprinted. Reading is
     * the only thing this is for; there is still no way to change or remove
     * anything that is already there.
     *
     * @throws java.io.IOException when it cannot be opened.
     */
    fun open(document: String): java.io.InputStream

    /**
     * Whether the document is still there. Used to check that a copy Magpie
     * made has not since been moved or removed by someone else, before telling
     * the user an original is safe to clear up.
     */
    fun exists(document: String): Boolean

    /** The size the folder reports back. Null when it will not say. */
    fun size(document: String): Long?

    /** The name the folder actually used. Null when it will not say. */
    fun name(document: String): String?

    /** The path a user would recognise for a document, for the report. */
    fun describe(folder: String?): String
}
