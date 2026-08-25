package com.magpie.filer.ai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ThumbnailUtils
import android.util.Base64
import android.util.Size
import com.magpie.filer.watch.SpottedFile
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Small snapshots of a run's files, made for showing to Claude.
 *
 * This is the one place in Magpie that prepares any part of a file's contents
 * to leave the phone, and it deliberately prepares as little as possible: a
 * single frame, scaled down to [EDGE] pixels on its long side, recompressed as
 * JPEG. The full-resolution file is never sent and never leaves the device.
 */
object Snapshots {

    /**
     * Long enough an edge to tell a portrait from a landscape from a prop on a
     * table; far too small to read fine text or reproduce the artwork. Also
     * what keeps the cost small: at this size a snapshot is a few hundred
     * visual tokens.
     */
    const val EDGE = 512

    private const val JPEG_QUALITY = 80

    private val IMAGES = setOf("jpg", "jpeg", "png", "webp", "gif", "heic", "heif", "bmp", "dng")
    private val VIDEOS = setOf("mp4", "mkv", "webm", "3gp", "mov", "m4v")

    /**
     * A base64 JPEG snapshot of [file], or null when one cannot be made — not
     * a picture, or too broken to read. For video it is a single frame, which
     * shows the scene but nothing about the motion.
     */
    fun of(file: SpottedFile): String? {
        val extension = file.name.substringAfterLast('.', "").lowercase()
        val frame = try {
            when (extension) {
                in IMAGES -> still(file.file)
                in VIDEOS -> ThumbnailUtils.createVideoThumbnail(file.file, Size(EDGE, EDGE), null)
                else -> null
            }
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        } ?: return null

        return try {
            val bytes = ByteArrayOutputStream()
            frame.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, bytes)
            Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP)
        } catch (e: Exception) {
            null
        } finally {
            frame.recycle()
        }
    }

    private fun still(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (bounds.outWidth / (sample * 2) >= EDGE && bounds.outHeight / (sample * 2) >= EDGE) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeFile(file.absolutePath, options)
    }
}
