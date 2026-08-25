package com.magpie.filer.watch

import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.IOException
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * When a file is really from.
 *
 * Grouping a backlog is only as good as the times it groups on. A photo carries
 * the moment it was taken in its EXIF, which is what you actually mean by "that
 * afternoon" — the file's modified time might be when it was copied off a
 * camera, or when a card was restored, which would scatter one shoot across
 * several days.
 *
 * Everything that is not a photo, or a photo with no EXIF, falls back to the
 * modified time, which for a download is when it arrived — and that is exactly
 * the right signal for a run of generations.
 *
 * This is the one Android-shaped part of grouping; the grouping itself is plain
 * arithmetic in [com.magpie.filer.core.Grouping] and is tested there.
 */
object Taken {

    /** EXIF writes local time with no zone, so it is read as UTC and compared like for like. */
    private const val EXIF_FORMAT = "yyyy:MM:dd HH:mm:ss"

    /** Only these can carry EXIF worth reading. */
    private val PHOTO_EXTENSIONS = setOf(".jpg", ".jpeg", ".png", ".webp", ".heic", ".heif", ".dng")

    /**
     * The moment [file] is from: its EXIF taken-at time when there is one, and
     * otherwise the time it landed.
     *
     * Never throws. A file that cannot be read, or whose EXIF is nonsense,
     * falls back rather than taking the whole grouping pass down with it.
     */
    fun of(file: SpottedFile): Long {
        val extension = file.name.substringAfterLast('.', "").lowercase()
        if (extension.isEmpty() || ".$extension" !in PHOTO_EXTENSIONS) return file.spottedAt
        return exifOf(file.file) ?: file.spottedAt
    }

    private fun exifOf(file: File): Long? {
        val stamp = try {
            val exif = ExifInterface(file)
            exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
        } catch (e: IOException) {
            null
        } catch (e: SecurityException) {
            null
        } catch (e: OutOfMemoryError) {
            // A corrupt header can make the parser ask for an absurd buffer.
            // One unreadable photo must not end the pass.
            null
        } ?: return null

        return try {
            val format = SimpleDateFormat(EXIF_FORMAT, Locale.US)
            format.timeZone = TimeZone.getTimeZone("UTC")
            format.parse(stamp)?.time
        } catch (e: ParseException) {
            null
        }
    }
}
