package com.wombat.split.split

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Scans a source tree (Storage Access Framework) and copies files into
 * part folders according to a [SplitPlanner.SplitPlan]. Copy only — the
 * source folder is never modified.
 */
class FolderSplitter(private val context: Context) {

    data class ScannedFile(val document: DocumentFile, val entry: SplitFile)

    data class Result(
        val partCount: Int,
        val fileCount: Int,
        val totalBytes: Long,
        val oversizedCount: Int,
    )

    suspend fun scan(tree: DocumentFile): List<ScannedFile> {
        val out = mutableListOf<ScannedFile>()
        suspend fun walk(dir: DocumentFile, prefix: String) {
            currentCoroutineContext().ensureActive()
            for (child in dir.listFiles()) {
                val name = child.name ?: continue
                val path = if (prefix.isEmpty()) name else "$prefix/$name"
                if (child.isDirectory) {
                    walk(child, path)
                } else if (child.isFile) {
                    out += ScannedFile(child, SplitFile(path, child.length()))
                }
            }
        }
        walk(tree, "")
        return out
    }

    /**
     * Copies the planned parts into [destination] as sibling folders named
     * `<jobName>-01`, `<jobName>-02`, … preserving each file's relative path
     * inside its part. Reports progress as bytes copied.
     */
    suspend fun execute(
        plan: SplitPlanner.SplitPlan,
        scanned: List<ScannedFile>,
        destination: DocumentFile,
        jobName: String,
        onProgress: (bytesCopied: Long, bytesTotal: Long) -> Unit,
    ): Result {
        val byPath = scanned.associateBy { it.entry.relativePath }
        val totalBytes = plan.totalBytes
        var copied = 0L
        var fileCount = 0

        plan.parts.forEachIndexed { index, part ->
            val partName = "%s-%02d".format(jobName, index + 1)
            val partDir = destination.createDirectory(partName)
                ?: error("Could not create folder $partName")

            for (file in part.files) {
                currentCoroutineContext().ensureActive()
                val source = byPath[file.relativePath]
                    ?: error("Missing source for ${file.relativePath}")
                copyInto(partDir, source, file.relativePath)
                copied += file.size
                fileCount++
                onProgress(copied, totalBytes)
            }
        }
        return Result(
            partCount = plan.parts.size,
            fileCount = fileCount,
            totalBytes = totalBytes,
            oversizedCount = plan.oversizedCount,
        )
    }

    private fun copyInto(partDir: DocumentFile, source: ScannedFile, relativePath: String) {
        val segments = relativePath.split('/')
        var dir = partDir
        for (segment in segments.dropLast(1)) {
            dir = dir.findFile(segment)?.takeIf { it.isDirectory }
                ?: dir.createDirectory(segment)
                ?: error("Could not create folder $segment")
        }
        val mime = source.document.type ?: "application/octet-stream"
        val target = dir.createFile(mime, segments.last())
            ?: error("Could not create file $relativePath")

        val resolver = context.contentResolver
        resolver.openInputStream(source.document.uri).use { input ->
            resolver.openOutputStream(target.uri).use { output ->
                requireNotNull(input) { "Cannot read ${source.entry.relativePath}" }
                requireNotNull(output) { "Cannot write $relativePath" }
                input.copyTo(output)
            }
        }
    }
}
