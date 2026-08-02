package com.wombat.split.split

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.BufferedOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

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
     * Writes the planned parts into [destination]. With [zipParts] off each
     * part is a folder `<jobName>-01`, `<jobName>-02`, … preserving relative
     * paths; with it on each part becomes a single `<jobName>-01.zip` whose
     * entries are the relative paths. Reports progress as bytes copied.
     */
    suspend fun execute(
        plan: SplitPlanner.SplitPlan,
        scanned: List<ScannedFile>,
        destination: DocumentFile,
        jobName: String,
        zipParts: Boolean,
        onProgress: (bytesCopied: Long, bytesTotal: Long) -> Unit,
    ): Result {
        val byPath = scanned.associateBy { it.entry.relativePath }
        val totalBytes = plan.totalBytes
        var copied = 0L
        var fileCount = 0

        plan.parts.forEachIndexed { index, part ->
            val partName = "%s-%02d".format(jobName, index + 1)
            if (zipParts) {
                writeZipPart(destination, partName, part, byPath) { fileSize ->
                    copied += fileSize
                    fileCount++
                    onProgress(copied, totalBytes)
                }
            } else {
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
        }
        return Result(
            partCount = plan.parts.size,
            fileCount = fileCount,
            totalBytes = totalBytes,
            oversizedCount = plan.oversizedCount,
        )
    }

    private suspend fun writeZipPart(
        destination: DocumentFile,
        partName: String,
        part: SplitPlanner.SplitPart,
        byPath: Map<String, ScannedFile>,
        onFileDone: (fileSize: Long) -> Unit,
    ) {
        val target = destination.createFile("application/zip", "$partName.zip")
            ?: error("Could not create $partName.zip")
        val resolver = context.contentResolver
        val out = resolver.openOutputStream(target.uri)
            ?: error("Cannot write $partName.zip")
        ZipOutputStream(BufferedOutputStream(out)).use { zip ->
            for (file in part.files) {
                currentCoroutineContext().ensureActive()
                val source = byPath[file.relativePath]
                    ?: error("Missing source for ${file.relativePath}")
                zip.putNextEntry(ZipEntry(file.relativePath))
                resolver.openInputStream(source.document.uri).use { input ->
                    requireNotNull(input) { "Cannot read ${file.relativePath}" }
                    input.copyTo(zip)
                }
                zip.closeEntry()
                onFileDone(file.size)
            }
        }
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
