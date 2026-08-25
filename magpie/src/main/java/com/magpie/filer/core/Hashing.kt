package com.magpie.filer.core

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * Content fingerprints, so "is this the same file?" can be answered rather than
 * guessed at.
 *
 * Comparing names and sizes says two files are *probably* the same. Comparing
 * contents says whether they are. It costs a read of each file and nothing
 * else — no network, no cost, and nothing is written or removed.
 */
object Hashing {

    private const val ALGORITHM = "SHA-256"

    /** Big enough to keep the digest fed, small enough not to matter on a phone. */
    private const val BUFFER_BYTES = 64 * 1024

    /**
     * The fingerprint of everything [stream] yields, as lowercase hex.
     *
     * The stream is read to the end and closed.
     */
    fun of(stream: InputStream): String {
        val digest = MessageDigest.getInstance(ALGORITHM)
        stream.use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun of(file: File): String = of(file.inputStream())
}
