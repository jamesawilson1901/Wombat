package com.magpie.service

import android.content.Context
import com.magpie.Graph
import com.magpie.data.Decision
import com.magpie.data.MoveLog
import com.magpie.domain.LocalSuggester
import com.magpie.domain.SafeMover
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Executes moves end to end: guard-rail check, safe move, log row, learned
 * decision, preset bookkeeping, pending-list cleanup. Everything that changes
 * the filesystem funnels through here.
 */
class Filer(private val context: Context) {

    sealed class MoveResult {
        data class Ok(val log: MoveLog) : MoveResult()
        data class Error(val message: String) : MoveResult()
    }

    suspend fun performMove(
        sourcePath: String,
        destFolder: String,
        finalName: String? = null,
        suggestedBy: String,
        accepted: Boolean,
    ): MoveResult = withContext(Dispatchers.IO) {
        val guard = Graph.pathGuard()
        val presets = Graph.db.presets().all().map { it.path }
        val settings = Graph.settings.snapshot()
        val allowedDests = presets + settings.sweepDestination
        if (guard.isDenied(sourcePath)) {
            return@withContext MoveResult.Error("Refusing to touch $sourcePath (outside allowed folders)")
        }
        if (!guard.isAllowedDestination(destFolder, allowedDests)) {
            return@withContext MoveResult.Error("Refusing destination $destFolder (outside allowed folders)")
        }

        val source = File(sourcePath)
        try {
            val moved = SafeMover.move(source, File(destFolder), finalName)
            val log = MoveLog(
                timestamp = System.currentTimeMillis(),
                originalPath = sourcePath,
                originalName = source.name,
                destFolder = destFolder,
                finalName = moved.finalFile.name,
                suggestedBy = suggestedBy,
                accepted = accepted,
            )
            val id = Graph.db.moveLog().insert(log)
            Graph.db.pending().delete(sourcePath)
            if (suggestedBy != "sweep") {
                Graph.db.decisions().insert(
                    Decision(
                        extension = LocalSuggester.extensionOf(source.name),
                        tokens = LocalSuggester.tokensAsString(source.name),
                        chosenFolder = destFolder,
                        timestamp = System.currentTimeMillis(),
                    )
                )
                Graph.db.presets().recordUse(destFolder, System.currentTimeMillis())
            }
            MoveResult.Ok(log.copy(id = id))
        } catch (e: IOException) {
            MoveResult.Error(e.message ?: "Move failed")
        }
    }

    /** §11: reverse a logged move with the same copy-verify-delete process. */
    suspend fun undo(logId: Long): MoveResult = withContext(Dispatchers.IO) {
        val log = Graph.db.moveLog().byId(logId)
            ?: return@withContext MoveResult.Error("Log entry not found")
        if (log.undone) return@withContext MoveResult.Error("Already undone")
        val movedFile = File(log.destFolder, log.finalName)
        if (!movedFile.isFile) {
            return@withContext MoveResult.Error("File is no longer at ${movedFile.path}")
        }
        val originalDir = File(log.originalPath).parentFile
            ?: return@withContext MoveResult.Error("Original location unknown")
        try {
            SafeMover.move(movedFile, originalDir, log.originalName)
            Graph.db.moveLog().update(log.copy(undone = true))
            MoveResult.Ok(log.copy(undone = true))
        } catch (e: IOException) {
            MoveResult.Error(e.message ?: "Undo failed")
        }
    }

    /** Whether a logged move can still be undone (greyed out otherwise). */
    fun undoAvailable(log: MoveLog): Boolean =
        !log.undone && File(log.destFolder, log.finalName).isFile
}
