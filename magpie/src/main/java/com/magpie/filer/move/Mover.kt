package com.magpie.filer.move

import android.content.Context
import android.net.Uri
import com.magpie.filer.watch.SpottedFile
import com.magpie.filer.watch.WatchRoots
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Files one file into a folder chosen through the Storage Access Framework.
 *
 * All this does is gather the Android-shaped facts — which storage volumes
 * exist, what the chosen folder is called and where it is — and hand them to
 * [Filing], which does the deciding and is tested. The SAF calls themselves
 * live in [SafDocumentStore].
 *
 * Filing is a safe move: copy, verify, and only then remove the original. A
 * move that could not be verified leaves the original exactly where it was.
 */
class Mover(private val context: Context) {

    suspend fun copy(source: SpottedFile, treeUri: Uri, targetName: String): MoveOutcome =
        withContext(Dispatchers.IO) {
            Filing.file(
                source = source.file,
                originalName = source.name,
                targetName = targetName,
                store = SafDocumentStore(
                    context = context,
                    treeUri = treeUri,
                    label = Destinations.label(context, treeUri),
                ),
                volumes = WatchRoots.volumePaths(context),
                destinationFolder = Destinations.folderPath(treeUri),
            )
        }
}
