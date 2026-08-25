package com.magpie.filer.move

import com.magpie.filer.core.Formatting
import com.magpie.filer.core.Hashing
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

    /**
     * Breathing room demanded on top of the file's own size before a copy is
     * allowed to start: filesystem overhead, and whatever else is writing to
     * the same volume at the time. Failing cleanly at the last byte is handled,
     * but refusing before the first one is better.
     */
    const val SPACE_MARGIN_BYTES = 8L * 1024 * 1024

    fun file(
        source: File,
        originalName: String,
        targetName: String,
        store: DocumentStore,
        volumes: List<String>,
        destinationFolder: File?,
        freeSpaceOf: (File) -> Long = { it.usableSpace },
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

        // Is there room? Only answerable when the destination is a real path on
        // the user's own storage; a provider with no path is left to fail its
        // own way, which the copy checks still catch. A reading of zero means
        // the volume would not say, and a guess must not become a refusal.
        if (destinationFolder != null) {
            val free = freeSpaceOf(destinationFolder)
            if (free > 0 && free < expected + SPACE_MARGIN_BYTES) {
                return MoveOutcome.Failed(
                    originalName,
                    "There is not enough room in $destination: this file is " +
                        "${Formatting.fileSize(expected)} and the destination has only " +
                        "${Formatting.fileSize(free)} free. Nothing was copied — make " +
                        "some space and try again."
                )
            }
        }

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

        // Is the same file already here under some other name? Only files of
        // exactly the same size can be, so the sizes from the listing filter
        // the candidates down before anything is read — usually to none.
        val duplicateNotes = mutableListOf<String>()
        val twin = findTwin(source, expected, here, store, duplicateNotes)
        val duplicate = clash != null || twin != null

        val into: String?
        if (!duplicate) {
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
                        "This is already in $destination" +
                            (if (twin != null) " as \"$twin\"" else " under that name") +
                            ", and the ${Safety.DUPLICATES_FOLDER} folder to put this copy " +
                            "in could not be made (${reason(made.exceptionOrNull())}). " +
                            "Nothing was written over."
                    )
                }
                id
            }
        }

        val landedIn = if (!duplicate) destination else store.describe(into)

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
        notes += duplicateNotes

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

        return if (!duplicate) {
            MoveOutcome.Copied(
                fileName = originalName,
                savedAs = savedAs,
                destination = destination,
                originalPath = source.absolutePath,
                document = created,
                notes = notes,
            )
        } else {
            MoveOutcome.Duplicated(
                fileName = originalName,
                savedAs = savedAs,
                destination = destination,
                duplicatesFolder = landedIn,
                originalPath = source.absolutePath,
                existingSize = clash?.size,
                incomingSize = expected,
                document = created,
                identical = identicalTo(source, expected, clash, twin, store),
                sameContentAs = twin,
                notes = notes,
            )
        }
    }

    /**
     * The name of a file already in the folder whose contents are exactly this
     * file's, or null when there is none.
     *
     * Two files can only have the same contents if they have the same size, so
     * the sizes already in the listing narrow the field before anything is
     * opened — on an ordinary folder that is nothing at all, and nothing gets
     * read. A folder that will not report sizes simply yields no candidates,
     * which is the safe way round: a duplicate goes unnoticed rather than a
     * different file being called one.
     */
    private fun findTwin(
        source: File,
        size: Long,
        here: List<DocEntry>,
        store: DocumentStore,
        notesInto: MutableList<String>?,
    ): String? {
        val candidates = here.filter { !it.isFolder && it.size == size }
        if (candidates.isEmpty()) return null

        val ours = attempt { Hashing.of(source) }.getOrNull() ?: return null
        for (candidate in candidates) {
            val theirs = attempt { Hashing.of(store.open(candidate.id)) }
            if (theirs.isFailure) {
                notesInto?.add(
                    "\"${candidate.name}\" is the same size as this file but could not be " +
                        "read to compare (${reason(theirs.exceptionOrNull())})."
                )
                continue
            }
            if (theirs.getOrNull() == ours) return candidate.name
        }
        return null
    }

    /**
     * Whether this file and what is already there hold the same bytes: true,
     * false, or null when it could not be established. [findTwin] has usually
     * answered it already; this only has work to do when the clash was a name
     * whose file is a different size, which settles it without reading.
     */
    private fun identicalTo(
        source: File,
        size: Long,
        clash: DocEntry?,
        twin: String?,
        store: DocumentStore,
    ): Boolean? {
        if (twin != null) return true
        if (clash == null) return null
        if (clash.size == null) return null
        if (clash.size != size) return false
        // Same name, same size, and findTwin did not match it — which means the
        // comparison itself failed, not that the contents differ.
        return null
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
