package com.magpie.filer.move

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import com.magpie.filer.core.Formatting
import com.magpie.filer.watch.SpottedFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.SyncFailedException

/**
 * Moves a file into a folder chosen through the Storage Access Framework.
 *
 * There is no raw move anywhere in here, and there is no path on which the
 * original is deleted before the copy has been checked:
 *
 *   1. copy the bytes into a freshly created document
 *   2. check the byte count, and the size the destination reports back,
 *      against the source — and check the source did not change underneath us
 *   3. only then delete the original
 *
 * If step 1 or 2 fails, the part-written copy is removed and the original is
 * left exactly as it was. If step 3 fails, the user is told in as many words
 * that two copies now exist.
 */
class Mover(private val context: Context) {

    private companion object {
        const val BUFFER_BYTES = 256 * 1024
    }

    suspend fun move(source: SpottedFile, treeUri: Uri, targetName: String): MoveOutcome =
        withContext(Dispatchers.IO) { moveBlocking(source, treeUri, targetName) }

    private fun moveBlocking(source: SpottedFile, treeUri: Uri, targetName: String): MoveOutcome {
        val name = source.name
        val file = source.file
        val destination = Destinations.label(context, treeUri)

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
            return MoveOutcome.Failed(name, "The new name was empty, so nothing was moved.")
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

        val created = attempt {
            DocumentsContract.createDocument(
                resolver,
                folder,
                Formatting.mimeType(targetName),
                targetName,
            )
        }.getOrElse {
            return MoveOutcome.Failed(
                name,
                "Magpie could not create \"$targetName\" in $destination (${reason(it)})."
            )
        } ?: return MoveOutcome.Failed(
            name,
            "$destination refused to create \"$targetName\". The folder may be read-only, " +
                "or the card may be full."
        )

        val notes = mutableListOf<String>()

        val copied = attempt { copyInto(file, created) }.getOrElse { failure ->
            return MoveOutcome.Failed(
                name,
                "Copying into $destination failed (${reason(failure)})." +
                    tidyUp(created, destination, targetName)
            )
        }
        notes += copied.notes

        // Verification. Any mismatch and the copy goes, not the original.
        val sourceNow = file.length()
        if (sourceNow != expected) {
            return MoveOutcome.Failed(
                name,
                "The file changed while it was being copied — it was " +
                    "${Formatting.fileSize(expected)} when Magpie started and " +
                    "${Formatting.fileSize(sourceNow)} when it finished. Nothing was deleted." +
                    tidyUp(created, destination, targetName)
            )
        }
        if (copied.written != expected) {
            return MoveOutcome.Failed(
                name,
                "Only ${copied.written} of $expected bytes arrived in $destination. " +
                    "Your original is untouched." + tidyUp(created, destination, targetName)
            )
        }

        val sizeLookup = attempt { column(created, DocumentsContract.Document.COLUMN_SIZE) }
        val reported = sizeLookup.getOrNull()?.toLongOrNull()
        when {
            sizeLookup.isFailure -> notes +=
                "$destination would not say how big the copy is " +
                    "(${reason(sizeLookup.exceptionOrNull())}), so the check used the " +
                    "${copied.written} bytes Magpie wrote."

            reported == null -> notes +=
                "$destination did not report a size back, so the check used the " +
                    "${copied.written} bytes Magpie wrote."

            reported != expected -> return MoveOutcome.Failed(
                name,
                "$destination says the copy is $reported bytes, but the original is " +
                    "$expected bytes. Your original is untouched." +
                    tidyUp(created, destination, targetName)
            )
        }

        val nameLookup = attempt { column(created, DocumentsContract.Document.COLUMN_DISPLAY_NAME) }
        if (nameLookup.isFailure) {
            notes += "$destination would not say what it named the file " +
                "(${reason(nameLookup.exceptionOrNull())}). Magpie asked for \"$targetName\"."
        }
        val savedAs = nameLookup.getOrNull() ?: targetName
        if (savedAs != targetName) {
            notes += "Saved as \"$savedAs\" rather than \"$targetName\" — the folder chose " +
                "the name, usually because something with that name was already there."
        }

        val deleted = attempt { file.delete() }
        val deleteFailure = when {
            deleted.isFailure -> reason(deleted.exceptionOrNull())
            deleted.getOrDefault(false) -> null
            else -> "Android refused the delete"
        }
        if (deleteFailure != null) {
            return MoveOutcome.OriginalRemains(
                fileName = name,
                savedAs = savedAs,
                destination = destination,
                originalPath = file.absolutePath,
                reason = deleteFailure,
            )
        }

        return MoveOutcome.Moved(name, savedAs, destination, notes)
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
     * Remove a copy that failed its checks. Returns a sentence to append to the
     * failure message either way — the user needs to know whether there is now
     * a broken file sitting in the destination.
     */
    private fun tidyUp(created: Uri, destination: String, targetName: String): String {
        val removed = attempt {
            DocumentsContract.deleteDocument(context.contentResolver, created)
        }
        val leftBehind = " so an incomplete \"$targetName\" may be sitting in $destination — " +
            "delete it by hand. Your original is untouched."
        return when {
            removed.getOrDefault(false) ->
                " The part-written copy was removed; your original is untouched."

            removed.isFailure ->
                " The part-written copy could not be removed either " +
                    "(${reason(removed.exceptionOrNull())}),$leftBehind"

            else -> " $destination refused to remove the part-written copy,$leftBehind"
        }
    }

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
