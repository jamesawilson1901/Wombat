package com.wombat.split.split

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import com.wombat.split.split.SplitPlanner.PlannedPart
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Scans a source (Storage Access Framework folder tree or single file) and
 * writes parts according to a [SplitPlanner.SplitPlan]. Copy only — the
 * source is never modified.
 *
 * Part layout, for a job named `echidna`:
 * - whole-file part, zip off: folder `echidna-01/` with the files inside
 *   (relative paths preserved)
 * - whole-file part, zip on: `echidna-01.zip` (entries are the relative paths)
 * - chunk of an over-limit file, zip off: folder `echidna-03/` containing
 *   `video.mp4.001`
 * - chunk of an over-limit file, zip on: loose file `echidna-03_video.mp4.001`
 *
 * Chunks reassemble with e.g. `cat video.mp4.* > video.mp4` — zipping a
 * partial slice would add nothing, so chunks are raw in both modes.
 */
class FolderSplitter(private val context: Context) {

    data class ScannedFile(val document: DocumentFile, val entry: SplitFile)

    data class Result(
        val partCount: Int,
        val fileCount: Int,
        val totalBytes: Long,
        val chunkedFileCount: Int,
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

    /** A single file as the source: one entry, named by its display name. */
    fun scanSingle(document: DocumentFile): List<ScannedFile> {
        val name = document.name ?: "file"
        return listOf(ScannedFile(document, SplitFile(name, document.length())))
    }

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

        val chunkReader = ChunkReader()
        try {
            plan.parts.forEachIndexed { index, part ->
                currentCoroutineContext().ensureActive()
                val partName = "%s-%02d".format(jobName, index + 1)
                when (part) {
                    is PlannedPart.Files -> {
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
                    is PlannedPart.Chunk -> {
                        val source = byPath[part.file.relativePath]
                            ?: error("Missing source for ${part.file.relativePath}")
                        val baseName = part.file.relativePath.substringAfterLast('/')
                        val chunkName = "%s.%03d".format(baseName, part.chunkIndex + 1)
                        val target = if (zipParts) {
                            destination.createFile(
                                "application/octet-stream",
                                "${partName}_$chunkName",
                            ) ?: error("Could not create ${partName}_$chunkName")
                        } else {
                            val partDir = destination.createDirectory(partName)
                                ?: error("Could not create folder $partName")
                            partDir.createFile("application/octet-stream", chunkName)
                                ?: error("Could not create $chunkName")
                        }
                        val out = context.contentResolver.openOutputStream(target.uri)
                            ?: error("Cannot write $chunkName")
                        out.use { chunkReader.copyRange(source, part.offset, part.length, it) }
                        copied += part.length
                        if (part.chunkIndex == 0) fileCount++
                        onProgress(copied, totalBytes)
                    }
                }
            }
        } finally {
            chunkReader.close()
        }
        return Result(
            partCount = plan.parts.size,
            fileCount = fileCount,
            totalBytes = totalBytes,
            chunkedFileCount = plan.chunkedFileCount,
        )
    }

    /**
     * Reads byte ranges out of source documents. Consecutive chunks of the
     * same file are planned in order, so the underlying stream is kept open
     * and read straight through instead of reopening and skipping per chunk.
     */
    private inner class ChunkReader : AutoCloseable {
        private var openPath: String? = null
        private var stream: InputStream? = null
        private var position: Long = 0

        fun copyRange(source: ScannedFile, offset: Long, length: Long, out: OutputStream) {
            val path = source.entry.relativePath
            if (openPath != path || position > offset) {
                close()
                stream = context.contentResolver.openInputStream(source.document.uri)
                    ?: error("Cannot read $path")
                openPath = path
                position = 0
            }
            val input = stream ?: error("Cannot read $path")
            skipFully(input, offset - position)
            position = offset

            val buffer = ByteArray(BUFFER_SIZE)
            var remaining = length
            while (remaining > 0) {
                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (read < 0) error("Unexpected end of $path at ${position + (length - remaining)}")
                out.write(buffer, 0, read)
                remaining -= read
            }
            position = offset + length
        }

        private fun skipFully(input: InputStream, count: Long) {
            var remaining = count
            val buffer = lazy { ByteArray(BUFFER_SIZE) }
            while (remaining > 0) {
                val skipped = input.skip(remaining)
                if (skipped > 0) {
                    remaining -= skipped
                } else {
                    val read = input.read(
                        buffer.value, 0, minOf(remaining, BUFFER_SIZE.toLong()).toInt()
                    )
                    if (read < 0) error("Unexpected end of stream while seeking")
                    remaining -= read
                }
            }
        }

        override fun close() {
            stream?.close()
            stream = null
            openPath = null
            position = 0
        }
    }

    private suspend fun writeZipPart(
        destination: DocumentFile,
        partName: String,
        part: PlannedPart.Files,
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

    private companion object {
        const val BUFFER_SIZE = 64 * 1024
    }
}
