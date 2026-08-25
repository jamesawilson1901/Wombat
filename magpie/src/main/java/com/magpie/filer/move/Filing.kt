package com.magpie.filer.move

import com.magpie.filer.core.Formatting
import com.magpie.filer.core.Safety
import java.io.File
import java.io.IOException

/**
 * What filing one file actually does, with no Android in it.
 *
 * Everything that decides something lives here — the safety refusals, whether a
 * name is already taken, whether a Duplicates folder is needed, whether the
 * bytes that arrived match the bytes that left — so all of it can be tested
 * against a [DocumentStore] backed by a temporary directory, on every CI run,
 * without a phone.
 *
 * **Nothing here deletes.** There is no call to reach for: [DocumentStore] has
 * no delete method, so the fail-safe holds by construction rather than by
 * everyone remembering.
 */
object Filing {

    fun file(
        source: File,
        originalName: String,
        targetName: String,
        store: DocumentStore,
        volumes: List<String>,
        destinationFolder: File?,
    ): MoveOutcome {
        val destination = store.label

        // The fail-safe, before a single byte is read. Both ends are checked:
        // where the file is now, and where it is being asked to go.
        Safety.refuse(source.absolutePath, volumes)?.let {
            return MoveOutcome.Refused(originalName, "Magpie will not read from there — $it.")
        }
        destinationFolder?.let { folder ->
            Safety.refuse(folder.absolutePath, volumes)?.let {
                return MoveOutcome.Refused(originalName, "Magpie will not write there — $it.")
            }
        }

        if (!source.exists()) {
            return MoveOutcome.Failed(
                originalName,
                "It is no longer at ${source.absolutePath}. Something else moved or deleted it."
            )
        }
        if (!source.isFile) {
            return MoveOutcome.Failed(originalName, "${source.absolutePath} is a folder, not a file.")
        }
        if (!source.canRead()) {
            return MoveOutcome.Failed(
                originalName,
                "Android will not let Magpie read ${source.absolutePath}. All-files access " +
                    "may have been switched off."
            )
        }
        if (targetName.isBlank()) {
            return MoveOutcome.Failed(originalName, "The new name was empty, so nothing was copied.")
        }
        if (destinationFolder?.absolutePath == source.parentFile?.absolutePath &&
            targetName == originalName
        ) {
            return MoveOutcome.Unchanged(originalName, destination)
        }

        val expected = source.length()

        // Is that name already taken? If so the copy is diverted into a
        // Duplicates folder rather than left to the provider, which would
        // otherwise quietly rename it to "thing (1).pdf" and scatter
        // near-identical files through the folder.
        val listing = attempt { store.list(null) }
        if (listing.isFailure) {
            return MoveOutcome.Failed(
                originalName,
                "Magpie could not check whether \"$targetName\" is already in $destination " +
                    "(${reason(listing.exceptionOrNull())}), and it will not write into a " +
                    "folder it cannot read first."
            )
        }
        val here = listing.getOrDefault(emptyList())
        val clash = here.firstOrNull { it.name == targetName }

        val into: String?
        if (clash == null) {
            into = null
        } else {
            val existing = here.firstOrNull {
                it.name == Safety.DUPLICATES_FOLDER && it.isFolder
            }
            into = if (existing != null) {
                existing.id
            } else {
                val made = attempt { store.createFolder(null, Safety.DUPLICATES_FOLDER) }
                val id = made.getOrNull()
                if (made.isFailure || id == null) {
                    return MoveOutcome.Failed(
                        originalName,
                        "\"$targetName\" is already in $destination, and the " +
                            "${Safety.DUPLICATES_FOLDER} folder to put this copy in could not " +
                            "be made (${reason(made.exceptionOrNull())}). Nothing was " +
                            "written over."
                    )
                }
                id
            }
        }

        val landedIn = if (clash == null) destination else store.describe(into)

        val creation = attempt { store.createFile(into, targetName, Formatting.mimeType(targetName)) }
        if (creation.isFailure) {
            return MoveOutcome.Failed(
                originalName,
                "Magpie could not create \"$targetName\" in $landedIn " +
                    "(${reason(creation.exceptionOrNull())})."
            )
        }
        val created = creation.getOrNull() ?: return MoveOutcome.Failed(
            originalName,
            "$landedIn refused to create \"$targetName\". The folder may be read-only, " +
                "or the card may be full."
        )

        val notes = mutableListOf<String>()

        val written = attempt { store.write(created, source) }.getOrElse { failure ->
            return MoveOutcome.Failed(
                originalName,
                "Copying into $landedIn failed (${reason(failure)})." +
                    leftBehind(landedIn, targetName)
            )
        }
        notes += written.notes

        // Verification. A mismatch means the copy is not trustworthy; it is
        // reported and left in place, because removing it would be a delete.
        val sourceNow = source.length()
        if (sourceNow != expected) {
            return MoveOutcome.Failed(
                originalName,
                "The file changed while it was being copied — it was " +
                    "${Formatting.fileSize(expected)} when Magpie started and " +
                    "${Formatting.fileSize(sourceNow)} when it finished." +
                    leftBehind(landedIn, targetName)
            )
        }
        if (written.written != expected) {
            return MoveOutcome.Failed(
                originalName,
                "Only ${written.written} of $expected bytes arrived in $landedIn." +
                    leftBehind(landedIn, targetName)
            )
        }

        val sizeLookup = attempt { store.size(created) }
        val reported = sizeLookup.getOrNull()
        when {
            sizeLookup.isFailure -> notes +=
                "$landedIn would not say how big the copy is " +
                    "(${reason(sizeLookup.exceptionOrNull())}), so the check used the " +
                    "${written.written} bytes Magpie wrote."

            reported == null -> notes +=
                "$landedIn did not report a size back, so the check used the " +
                    "${written.written} bytes Magpie wrote."

            reported != expected -> return MoveOutcome.Failed(
                originalName,
                "$landedIn says the copy is $reported bytes, but the original is " +
                    "$expected bytes." + leftBehind(landedIn, targetName)
            )
        }

        val nameLookup = attempt { store.name(created) }
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
                fileName = originalName,
                savedAs = savedAs,
                destination = destination,
                originalPath = source.absolutePath,
                notes = notes,
            )
        } else {
            MoveOutcome.Duplicated(
                fileName = originalName,
                savedAs = savedAs,
                destination = destination,
                duplicatesFolder = landedIn,
                originalPath = source.absolutePath,
                existingSize = clash.size,
                incomingSize = expected,
                notes = notes,
            )
        }
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

    /**
     * Run a store operation, keeping the exception rather than swallowing it.
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
