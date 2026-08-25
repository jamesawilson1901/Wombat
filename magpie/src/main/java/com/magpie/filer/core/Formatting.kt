package com.magpie.filer.core

import java.util.Locale

/**
 * How a file is described in the list and in its notification. Pure Kotlin, so
 * the wording is unit-testable.
 */
object Formatting {

    private val UNITS = listOf("kB", "MB", "GB", "TB")

    /** "834 B", "1.2 MB", "34 MB". Decimal units, the way storage is sold. */
    fun fileSize(bytes: Long, locale: Locale = Locale.getDefault()): String {
        if (bytes < 0) return "size unknown"
        if (bytes < 1000) return "$bytes B"
        var value = bytes.toDouble() / 1000.0
        var unit = 0
        while (value >= 1000.0 && unit < UNITS.lastIndex) {
            value /= 1000.0
            unit++
        }
        val pattern = if (value < 10.0) "%.1f %s" else "%.0f %s"
        return String.format(locale, pattern, value, UNITS[unit])
    }

    /** A short, readable file type: "PDF", "JPEG image", "ZIP archive". */
    fun typeLabel(name: String): String {
        val extension = Naming.extension(name).removePrefix(".").lowercase()
        if (extension.isEmpty()) return "File"
        return KNOWN_TYPES[extension] ?: "${extension.uppercase()} file"
    }

    private val KNOWN_TYPES = mapOf(
        "pdf" to "PDF",
        "epub" to "EPUB book",
        "mobi" to "Kindle book",
        "jpg" to "JPEG image",
        "jpeg" to "JPEG image",
        "png" to "PNG image",
        "gif" to "GIF image",
        "webp" to "WebP image",
        "heic" to "HEIC image",
        "heif" to "HEIF image",
        "bmp" to "Bitmap image",
        "svg" to "SVG image",
        "mp4" to "MP4 video",
        "m4v" to "MP4 video",
        "mkv" to "MKV video",
        "webm" to "WebM video",
        "avi" to "AVI video",
        "mov" to "QuickTime video",
        "mp3" to "MP3 audio",
        "m4a" to "M4A audio",
        "aac" to "AAC audio",
        "flac" to "FLAC audio",
        "wav" to "WAV audio",
        "ogg" to "OGG audio",
        "opus" to "Opus audio",
        "zip" to "ZIP archive",
        "rar" to "RAR archive",
        "7z" to "7-Zip archive",
        "tar" to "TAR archive",
        "gz" to "Gzip archive",
        "xz" to "XZ archive",
        "apk" to "Android app",
        "apks" to "Android app bundle",
        "xapk" to "Android app bundle",
        "torrent" to "Torrent",
        "txt" to "Text",
        "md" to "Markdown",
        "csv" to "CSV",
        "json" to "JSON",
        "xml" to "XML",
        "html" to "Web page",
        "htm" to "Web page",
        "doc" to "Word document",
        "docx" to "Word document",
        "odt" to "OpenDocument text",
        "rtf" to "Rich text",
        "xls" to "Spreadsheet",
        "xlsx" to "Spreadsheet",
        "ods" to "OpenDocument sheet",
        "ppt" to "Presentation",
        "pptx" to "Presentation",
        "odp" to "OpenDocument slides",
        "iso" to "Disc image",
        "img" to "Disc image",
    )

    /** What a provider is told when nothing better is known. */
    const val UNKNOWN_MIME = "application/octet-stream"

    /**
     * A content type for the destination provider. Getting this right matters:
     * some providers append an extension of their own when the type and the
     * name disagree.
     *
     * Returns [UNKNOWN_MIME] for anything not in the table, which is the
     * caller's cue to ask Android before giving up.
     */
    fun mimeType(name: String): String {
        val extension = Naming.extension(name).removePrefix(".").lowercase()
        return MIME_TYPES[extension] ?: UNKNOWN_MIME
    }

    private val MIME_TYPES = mapOf(
        "pdf" to "application/pdf",
        "epub" to "application/epub+zip",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "png" to "image/png",
        "gif" to "image/gif",
        "webp" to "image/webp",
        "heic" to "image/heic",
        "heif" to "image/heif",
        "bmp" to "image/bmp",
        "svg" to "image/svg+xml",
        "mp4" to "video/mp4",
        "m4v" to "video/mp4",
        "mkv" to "video/x-matroska",
        "webm" to "video/webm",
        "avi" to "video/x-msvideo",
        "mov" to "video/quicktime",
        "mp3" to "audio/mpeg",
        "m4a" to "audio/mp4",
        "aac" to "audio/aac",
        "flac" to "audio/flac",
        "wav" to "audio/wav",
        "ogg" to "audio/ogg",
        "opus" to "audio/opus",
        "zip" to "application/zip",
        "rar" to "application/vnd.rar",
        "7z" to "application/x-7z-compressed",
        "tar" to "application/x-tar",
        "gz" to "application/gzip",
        "xz" to "application/x-xz",
        "apk" to "application/vnd.android.package-archive",
        "torrent" to "application/x-bittorrent",
        "txt" to "text/plain",
        "md" to "text/markdown",
        "csv" to "text/csv",
        "json" to "application/json",
        "xml" to "text/xml",
        "html" to "text/html",
        "htm" to "text/html",
        "doc" to "application/msword",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "odt" to "application/vnd.oasis.opendocument.text",
        "rtf" to "application/rtf",
        "xls" to "application/vnd.ms-excel",
        "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "ods" to "application/vnd.oasis.opendocument.spreadsheet",
        "ppt" to "application/vnd.ms-powerpoint",
        "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "odp" to "application/vnd.oasis.opendocument.presentation",
    )

    /**
     * A group's date range in words: "15 June, 14:02–17:41", or with the dates
     * on both ends when it spans more than a day.
     *
     * Takes the zone explicitly so the wording can be tested without depending
     * on where the machine running the tests happens to be.
     */
    fun timeRange(earliest: Long, latest: Long, zone: java.time.ZoneId): String {
        val from = java.time.Instant.ofEpochMilli(earliest).atZone(zone)
        val to = java.time.Instant.ofEpochMilli(latest).atZone(zone)
        val day = java.time.format.DateTimeFormatter.ofPattern("d MMMM yyyy", java.util.Locale.UK)
        val clock = java.time.format.DateTimeFormatter.ofPattern("HH:mm", java.util.Locale.UK)

        return if (from.toLocalDate() == to.toLocalDate()) {
            if (from.toLocalTime() == to.toLocalTime()) {
                "${day.format(from)}, ${clock.format(from)}"
            } else {
                "${day.format(from)}, ${clock.format(from)}\u2013${clock.format(to)}"
            }
        } else {
            "${day.format(from)} ${clock.format(from)} \u2013 ${day.format(to)} ${clock.format(to)}"
        }
    }
}
