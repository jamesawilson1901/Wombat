package com.magpie.filer.move

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import com.magpie.filer.core.Formatting
import com.magpie.filer.core.Naming
import com.magpie.filer.core.Safety
import com.magpie.filer.watch.SpottedFile
import com.magpie.filer.watch.WatchRoots
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.SyncFailedException

/**
 * Copies a file into a folder chosen through the Storage Access Framework.
 *
 * **Nothing in this class deletes anything.** There is no `delete` call on any
 * path through it — not for the original, not for a copy that failed its
 * checks, not for a folder. Filing means:
 *
 *   1. refuse outright if either end is a path Magpie may not touch
 *   2. if something of that name is already there, aim at a Duplicates folder
 *      inside the destination instead, so nothing is ever written over
 *   3. copy the bytes into a freshly created document
 *   4. check the byte count, and the size the destination reports back,
 *      against the source — and check the source did not change underneath us
 *
 * The original is left where it is, every time, whether the copy worked or not.
 * If a copy fails its checks the part-written file stays too, and the user is
 * told exactly where it is so they can remove it themselves if they want to.
 * Magpie will not do it for them.
 */
class Mover(private val context: Context) {

    private companion object {
        const val BUFFER_BYTES = 256 * 1024
    }

    suspend fun copy(source: SpottedFile, treeUri: Uri, targetName: String): MoveOutcome =
        withContext(Dispatchers.IO) { copyBlocking(source, treeUri, targetName) }

    private fun copyBlocking(source: SpottedFile, treeUri: Uri, targetName: String): MoveOutcome {
        val name = source.name
        val file = source.file
        val destination = Destinations.label(context, treeUri)
        val volumes = WatchRoots.volumePaths(context)

        // The fail-safe, before a single byte is read. Both ends are checked:
        // where the file is now, and where it is being asked to go.
        Safety.refuse(file.absolutePath, volumes)?.let {
            return MoveOutcome.Refused(name, "Magpie will not read from there — $it.")
        }
        Destinations.folderPath(treeUri)?.let { chosen ->
            Safety.refuse(chosen.absolutePath, volumes)?.let {
                return MoveOutcome.Refused(name, "Magpie will not write there — $it.")
            }
        }

        if (!file.exists()) {
            return MoveOutcome.Failed(
                name,
                "It is no longer at ${file.absolutePath}. Something else moved or deleted it."
            )
        }
        if (!file.isFile) {
            return MoveOutcome.Failed(name, "${file.absolutePath} is a folder, not a file.")
        }
        if (!file.canRead()) {
            return MoveOutcome.Failed(
                name,
                "Android will not let Magpie read ${file.absolutePath}. All-files access " +
                    "may have been switched off."
            )
        }
        if (targetName.isBlank()) {
            return MoveOutcome.Failed(name, "The new name was empty, so nothing was copied.")
        }
        if (isSameFolder(treeUri, file.parentFile) && targetName == name) {
            return MoveOutcome.Unchanged(name, destination)
        }

        val expected = file.length()
        val resolver = context.contentResolver

        val folder = attempt {
            DocumentsContract.buildDocumentUriUsingTree(
                treeUri,
                DocumentsContract.getTreeDocumentId(treeUri),
            )
        }.getOrElse {
            return MoveOutcome.Failed(
                name,
                "$destination could not be opened (${reason(it)}). Choose the folder again."
            )
        }

        // Is there already something of this name? If so the copy is diverted
        // into a Duplicates folder rather than left to the provider, which
        // would otherwise quietly rename it to "thing (1).pdf" and scatter
        // near-identical files through the folder.
        val existing = attempt { childNamed(treeUri, folder, targetName) }
        if (existing.isFailure) {
            return MoveOutcome.Failed(
                name,
                "Magpie could not check whether \"$targetName\" is already in $destination " +
                    "(${reason(existing.exceptionOrNull())}), and it will not write into a " +
                    "folder it cannot read first."
            )
        }

        val clash = existing.getOrNull()
        val target: Uri
        val duplicatesInto: String?
        if (clash == null) {
            target = folder
            duplicatesInto = null
        } else {
            target = findOrCreateFolder(treeUri, folder, Safety.DUPLICATES_FOLDER).getOrElse {
                return MoveOutcome.Failed(
                    name,
                    "\"$targetName\" is already in $destination, and the " +
                        "${Safety.DUPLICATES_FOLDER} folder to put this copy in " +
                        "could not be made (${reason(it)}). Nothing was written over."
                )
            }
            duplicatesInto = "$destination/${Safety.DUPLICATES_FOLDER}"
        }

        val created = attempt {
            DocumentsContract.createDocument(resolver, target, mimeFor(targetName), targetName)
        }.getOrElse {
            return MoveOutcome.Failed(
                name,
                "Magpie could not create \"$targetName\" in " +
                    "${duplicatesInto ?: destination} (${reason(it)})."
            )
        } ?: return MoveOutcome.Failed(
            name,
            "${duplicatesInto ?: destination} refused to create \"$targetName\". The folder " +
                "may be read-only, or the card may be full."
        )

        val landedIn = duplicatesInto ?: destination
        val notes = mutableListOf<String>()

        val copied = attempt { copyInto(file, created) }.getOrElse { failure ->
            return MoveOutcome.Failed(
                name,
                "Copying into $landedIn failed (${reason(failure)})." +
                    leftBehind(landedIn, targetName)
            )
        }
        notes += copied.notes

        // Verification. A mismatch means the copy is not trustworthy; it is
        // reported and left in place, because removing it would be a delete.
        val sourceNow = file.length()
        if (sourceNow != expected) {
            return MoveOutcome.Failed(
                name,
                "The file changed while it was being copied — it was " +
                    "${Formatting.fileSize(expected)} when Magpie started and " +
                    "${Formatting.fileSize(sourceNow)} when it finished." +
                    leftBehind(landedIn, targetName)
            )
        }
        if (copied.written != expected) {
            return MoveOutcome.Failed(
                name,
                "Only ${copied.written} of $expected bytes arrived in $landedIn." +
                    leftBehind(landedIn, targetName)
            )
        }

        val sizeLookup = attempt { column(created, DocumentsContract.Document.COLUMN_SIZE) }
        val reported = sizeLookup.getOrNull()?.toLongOrNull()
        when {
            sizeLookup.isFailure -> notes +=
                "$landedIn would not say how big the copy is " +
                    "(${reason(sizeLookup.exceptionOrNull())}), so the check used the " +
                    "${copied.written} bytes Magpie wrote."

            reported == null -> notes +=
                "$landedIn did not report a size back, so the check used the " +
                    "${copied.written} bytes Magpie wrote."

            reported != expected -> return MoveOutcome.Failed(
                name,
                "$landedIn says the copy is $reported bytes, but the original is " +
                    "$expected bytes." + leftBehind(landedIn, targetName)
            )
        }

        val nameLookup = attempt { column(created, DocumentsContract.Document.COLUMN_DISPLAY_NAME) }
        if (nameLookup.isFailure) {
            notes += "$landedIn would not say what it named the file " +
                "(${reason(nameLookup.exceptionOrNull())}). Magpie asked for \"$targetName\"."
        }
        val savedAs = nameLookup.getOrNull() ?: targetName
        if (savedAs != targetName) {
            notes += "Saved as \"$savedAs\" rather than \"$targetName\" — the folder chose " +
                "the name, usually because something with that name was already there."
        }

        return if (clash == null) {
            MoveOutcome.Copied(
                fileName = name,
                savedAs = savedAs,
                destination = destination,
                originalPath = file.absolutePath,
                notes = notes,
            )
        } else {
            MoveOutcome.Duplicated(
                fileName = name,
                savedAs = savedAs,
                destination = destination,
                duplicatesFolder = landedIn,
                originalPath = file.absolutePath,
                existingSize = clash.size,
                incomingSize = expected,
                notes = notes,
            )
        }
    }

    /**
     * The content type to create the document with. The built-in table covers
     * the common cases; anything else goes to Android's own list before falling
     * back, because a provider handed application/octet-stream may decide to
     * append an extension of its own and quietly rename the file.
     */
    private fun mimeFor(name: String): String {
        val known = Formatting.mimeType(name)
        if (known != Formatting.UNKNOWN_MIME) return known
        val extension = Naming.extension(name).removePrefix(".").lowercase()
        if (extension.isEmpty()) return Formatting.UNKNOWN_MIME
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: Formatting.UNKNOWN_MIME
    }

    // ---- looking before writing --------------------------------------------

    private class Child(val documentId: String, val size: Long?, val isFolder: Boolean)

    /** The child of [folder] with exactly this display name, or null. */
    private fun childNamed(treeUri: Uri, folder: Uri, name: String): Child? =
        children(treeUri, folder).firstOrNull { it.first == name }?.second

    private fun children(treeUri: Uri, folder: Uri): List<Pair<String, Child>> {
        val parentId = DocumentsContract.getDocumentId(folder)
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        val found = ArrayList<Pair<String, Child>>()
        context.contentResolver.query(uri, columns, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0) ?: continue
                val displayName = cursor.getString(1) ?: continue
                val isFolder = cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR
                val size = if (cursor.isNull(3)) null else cursor.getLong(3)
                found += displayName to Child(id, size, isFolder)
            }
        } ?: throw IOException("the folder would not list its contents")
        return found
    }

    /**
     * The Duplicates folder inside [parent], made if it is not there yet. An
     * existing one is reused rather than a second one created beside it.
     */
    private fun findOrCreateFolder(treeUri: Uri, parent: Uri, name: String): Result<Uri> = attempt {
        val existing = children(treeUri, parent).firstOrNull { it.first == name && it.second.isFolder }
        if (existing != null) {
            DocumentsContract.buildDocumentUriUsingTree(treeUri, existing.second.documentId)
        } else {
            DocumentsContract.createDocument(
                context.contentResolver,
                parent,
                DocumentsContract.Document.MIME_TYPE_DIR,
                name,
            ) ?: throw IOException("the folder refused to create $name")
        }
    }

    // ---- copying -----------------------------------------------------------

    private class Copied(val written: Long, val notes: List<String>)

    private fun copyInto(source: File, destination: Uri): Copied {
        val notes = mutableListOf<String>()
        var written = 0L

        val descriptor = context.contentResolver.openFileDescriptor(destination, "w")
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

        return Copied(written, notes)
    }

    /**
     * The sentence appended to every failure that happened after a document was
     * created. Magpie does not remove the part-written file, because it does not
     * remove anything — so it says where it is instead.
     */
    private fun leftBehind(destination: String, targetName: String): String =
        " An incomplete \"$targetName\" is now in $destination. Magpie never deletes " +
            "anything, so remove it yourself if you do not want it. Your original is " +
            "untouched, exactly where it was."

    // ---- reading the destination back --------------------------------------

    /**
     * Read one column of the created document back. Returns null when the
     * provider has nothing to say; throws when the query itself fails, so the
     * caller can tell the difference and say which happened.
     */
    private fun column(document: Uri, name: String): String? =
        context.contentResolver.query(document, arrayOf(name), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
            }

    /** True when the chosen folder is the one the file is already in. */
    private fun isSameFolder(treeUri: Uri, sourceFolder: File?): Boolean {
        if (sourceFolder == null) return false
        val chosen = Destinations.folderPath(treeUri) ?: return false
        return chosen.absolutePath == sourceFolder.absolutePath
    }

    // ---- failure plumbing --------------------------------------------------

    /**
     * Run a file operation, keeping the exception rather than swallowing it.
     * Every caller turns the failure into something the user reads.
     */
    private inline fun <T> attempt(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: IOException) {
        Result.failure(e)
    } catch (e: SecurityException) {
        Result.failure(e)
    } catch (e: IllegalArgumentException) {
        Result.failure(e)
    } catch (e: IllegalStateException) {
        Result.failure(e)
    } catch (e: UnsupportedOperationException) {
        Result.failure(e)
    }

    private fun reason(error: Throwable?): String =
        error?.message?.takeIf { it.isNotBlank() }
            ?: error?.javaClass?.simpleName
            ?: "no reason given"
}
