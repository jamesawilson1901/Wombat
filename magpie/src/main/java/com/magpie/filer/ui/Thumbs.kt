package com.magpie.filer.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ThumbnailUtils
import android.util.Size
import com.magpie.filer.watch.SpottedFile
import java.io.File

/**
 * Small pictures of what a run holds, so it can be recognised at a glance
 * instead of deciphered from filenames like gen_4471203.png.
 *
 * Everything happens on the phone: a downsampled decode for stills, the
 * system's own thumbnailer for video. Nothing is written anywhere and nothing
 * leaves the device.
 */
object Thumbs {

    private val IMAGES = setOf("jpg", "jpeg", "png", "webp", "gif", "heic", "heif", "bmp", "dng")
    private val VIDEOS = setOf("mp4", "mkv", "webm", "3gp", "mov", "m4v")

    /**
     * A thumbnail for [file], or null when it is not something a picture can be
     * made of — or is too broken to read, which must cost the thumbnail and
     * nothing more.
     */
    fun of(file: SpottedFile, edge: Int = 192): Bitmap? {
        val extension = file.name.substringAfterLast('.', "").lowercase()
        return try {
            when (extension) {
                in IMAGES -> still(file.file, edge)
                in VIDEOS -> ThumbnailUtils.createVideoThumbnail(file.file, Size(edge, edge), null)
                else -> null
            }
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            // A corrupt header can claim absurd dimensions. One unreadable
            // file must not take the screen down.
            null
        }
    }

    /**
     * Decode a still at roughly [edge] pixels rather than full size: the
     * bounds are read first, then the decode is downsampled, so a 12-megapixel
     * photo never exists in memory just to become a 64dp square.
     */
    private fun still(file: File, edge: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (bounds.outWidth / (sample * 2) >= edge && bounds.outHeight / (sample * 2) >= edge) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeFile(file.absolutePath, options)
    }
}
