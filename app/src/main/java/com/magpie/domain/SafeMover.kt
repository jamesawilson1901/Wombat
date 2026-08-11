package com.magpie.domain

import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * §10: copy → verify → rename → delete, never a raw move. Failures are loud —
 * every problem throws [IOException] with the partial copy already cleaned up
 * and the original untouched.
 */
object SafeMover {

    const val TMP_SUFFIX = ".magpie-tmp"
    private const val HASH_LIMIT_BYTES = 50L * 1024 * 1024

    data class Moved(val finalFile: File)

    fun move(source: File, destDir: File, desiredName: String? = null): Moved {
        if (!source.isFile) throw IOException("Source no longer exists: ${source.path}")
        if (!destDir.isDirectory && !destDir.mkdirs()) {
            throw IOException("Cannot create destination folder: ${destDir.path}")
        }

        val finalName = resolveClash(destDir, desiredName ?: source.name)
        val finalFile = File(destDir, finalName)
        val tmp = File(destDir, "$finalName$TMP_SUFFIX")

        val sourceLength = source.length()
        val wantHash = sourceLength < HASH_LIMIT_BYTES

        try {
            val sourceHash = copy(source, tmp, wantHash)

            if (tmp.length() != sourceLength) {
                throw IOException("Size mismatch after copy: ${tmp.length()} vs $sourceLength for ${source.name}")
            }
            if (wantHash && sourceHash != null && digest(tmp) != sourceHash) {
                throw IOException("Checksum mismatch after copy of ${source.name}")
            }
            if (!tmp.renameTo(finalFile)) {
                throw IOException("Could not rename temp file into place for ${source.name}")
            }
        } catch (e: Exception) {
            tmp.delete()
            throw if (e is IOException) e else IOException("Copy failed for ${source.name}: ${e.message}", e)
        }

        if (!source.delete()) {
            // The copy is verified and in place; refusing to lose data, but this
            // still must be loud — the caller shows it inline.
            throw IOException("Moved ${source.name} but could not delete the original at ${source.path}")
        }
        return Moved(finalFile)
    }

    /** Never overwrite: name.pdf → name (2).pdf → name (3).pdf … */
    fun resolveClash(destDir: File, name: String): String {
        if (!File(destDir, name).exists()) return name
        val (base, ext) = RenameHeuristics.splitExtension(name)
        var n = 2
        while (true) {
            val candidate = "$base ($n)$ext"
            if (!File(destDir, candidate).exists()) return candidate
            n++
            if (n > 9999) throw IOException("Cannot find a free name for $name in ${destDir.path}")
        }
    }

    private fun copy(source: File, tmp: File, hash: Boolean): String? {
        val md = if (hash) MessageDigest.getInstance("SHA-256") else null
        source.inputStream().use { input ->
            tmp.outputStream().use { output ->
                val buffer = ByteArray(256 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    md?.update(buffer, 0, read)
                }
                output.fd.sync()
            }
        }
        return md?.digest()?.joinToString("") { "%02x".format(it) }
    }

    private fun digest(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                md.update(buffer, 0, read)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
