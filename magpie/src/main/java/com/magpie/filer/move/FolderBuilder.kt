package com.magpie.filer.move

import com.magpie.filer.core.PlannedFolder
import java.io.IOException

/** What building a folder tree actually did. */
data class BuildReport(
    /** Folders that were not there and now are. */
    val made: List<String> = emptyList(),
    /** Folders that were already there and were left exactly as they were. */
    val reused: List<String> = emptyList(),
    /** Folders that could not be made, each with the reason. */
    val failed: List<Pair<String, String>> = emptyList(),
) {
    val total: Int get() = made.size + reused.size + failed.size
    val allWell: Boolean get() = failed.isEmpty()
}

/**
 * Makes a folder tree inside a folder the user chose.
 *
 * The only thing this does is create folders that are not there. A folder that
 * already exists is reused, not replaced and not emptied — so running the same
 * tree twice is safe, and building a tree over the top of one you already have
 * only fills in what is missing.
 *
 * Nothing here can remove or overwrite anything: [DocumentStore] has no method
 * that would, and files are not touched at all.
 */
object FolderBuilder {

    fun build(plan: List<PlannedFolder>, store: DocumentStore): BuildReport {
        val made = ArrayList<String>()
        val reused = ArrayList<String>()
        val failed = ArrayList<Pair<String, String>>()

        // Document id of every folder reached so far, by its path. The root of
        // the chosen folder is null, which is what the store expects.
        val known = HashMap<String, String?>()
        known[""] = null

        for (folder in plan) {
            val parentPath = folder.segments.dropLast(1).joinToString("/")
            if (!known.containsKey(parentPath)) {
                // Its parent could not be made, so neither can this. Said once
                // per folder rather than left to fail confusingly deeper down.
                failed += folder.path to "its parent folder could not be made"
                continue
            }
            val parent = known[parentPath]

            val existing = try {
                store.list(parent).firstOrNull { it.name == folder.name && it.isFolder }
            } catch (e: IOException) {
                failed += folder.path to (e.message ?: "the folder could not be read")
                continue
            } catch (e: SecurityException) {
                failed += folder.path to (e.message ?: "permission was refused")
                continue
            }

            if (existing != null) {
                known[folder.path] = existing.id
                reused += folder.path
                continue
            }

            val created = try {
                store.createFolder(parent, folder.name)
            } catch (e: IOException) {
                failed += folder.path to (e.message ?: "it could not be created")
                continue
            } catch (e: SecurityException) {
                failed += folder.path to (e.message ?: "permission was refused")
                continue
            } catch (e: UnsupportedOperationException) {
                failed += folder.path to (e.message ?: "the folder does not allow it")
                continue
            }

            if (created == null) {
                failed += folder.path to "the folder refused to make it, without saying why"
                continue
            }
            known[folder.path] = created
            made += folder.path
        }

        return BuildReport(made = made, reused = reused, failed = failed)
    }
}
